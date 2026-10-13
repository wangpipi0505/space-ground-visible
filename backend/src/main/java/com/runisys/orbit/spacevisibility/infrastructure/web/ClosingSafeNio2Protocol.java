/*
 * 接收器分支改编自 Apache Tomcat 9.0.83 Nio2Endpoint（Apache Software Foundation）。
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.runisys.orbit.spacevisibility.infrastructure.web;

import org.apache.coyote.http11.Http11Nio2Protocol;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.Acceptor.AcceptorState;
import org.apache.tomcat.util.net.Nio2Endpoint;
import org.apache.tomcat.util.net.Nio2Channel;
import org.apache.tomcat.util.net.SecureNio2Channel;
import org.apache.tomcat.util.net.SocketBufferHandler;
import org.apache.tomcat.util.net.SocketEvent;

import java.io.IOException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;

/**
 * 保留 Tomcat 原 NIO2 HTTP 协议，使接收操作和监听通道关闭互斥。
 *
 * <p>Tomcat 9.0.83 的接收循环在检查 paused 后才读取 serverSock；另一线程关闭时先
 * close 再将该字段置 null，而接收执行器稍后才退出，两者之间存在空指针竞争。
 * 此适配通过官方构造器注入端点，仍由 Tomcat 管理连接、工作线程和异步回调。
 * 默认 NIO 不使用本类，原显式 NIO2 配置值由定制器映射到此协议子类。</p>
 */
public final class ClosingSafeNio2Protocol extends Http11Nio2Protocol {
    public ClosingSafeNio2Protocol() {
        super(new ClosingSafeEndpoint());
    }

    /**
     * 在同一监视器内重新检查运行状态并提交下一次 accept，关闭则在该监视器内执行原实现。
     * 锁不覆盖等待连接限额或 HTTP 业务，避免串行化计算或改变长请求关闭的等待策略。
     * 原生关闭仍释放 OS 句柄、清空引用，并按原顺序关闭通道组和执行器。
     */
    static class ClosingSafeEndpoint extends Nio2Endpoint {
        private final Object acceptLifecycle = new Object();

        @Override
        protected void startAcceptorThread() {
            if (acceptor == null) {
                acceptor = new ClosingSafeAcceptor();
                // NIO2 接收器是执行器任务，没有独立线程；保留原执行器线程名称即可。
            }
            super.startAcceptorThread();
        }

        @Override
        protected void doCloseServerSocket() throws IOException {
            synchronized (acceptLifecycle) {
                super.doCloseServerSocket();
            }
        }

        /** 暂停和连接限额解锁在监视器外完成；只将原停止状态、连接表关闭提交、缓存释放与登记互斥。 */
        @Override
        public void stopInternal() {
            if (!isPaused()) {
                pause();
            }
            synchronized (acceptLifecycle) {
                super.stopInternal();
            }
        }

        /**
         * 沿用9.0.83原包装器/SSL/缓冲区和超时配置，只保护连接登记阶段。
         * 关闭可发生在acceptNext返回后；因此必须在同一生命周期锁内再次检查并完成登记，
         * 不能仅在锁外多读一次running。原HTTP处理仍同步运行在当前回调线程，但在锁外执行。
         */
        @Override
        protected boolean setSocketOptions(AsynchronousSocketChannel socket) {
            Nio2SocketWrapper wrapper = null;
            try {
                synchronized (acceptLifecycle) {
                    if (!isRunning() || isPaused() || getServerSocket() == null) {
                        destroySocket(socket);
                        // 该socket已由本方法收回，返回true避免原completed再次closeSocket重复计数。
                        return true;
                    }
                    Nio2Channel channel = getNioChannels() == null ? null : getNioChannels().pop();
                    if (channel == null) {
                        SocketBufferHandler buffer = new SocketBufferHandler(socketProperties.getAppReadBufSize(),
                                socketProperties.getAppWriteBufSize(), socketProperties.getDirectBuffer());
                        channel = isSSLEnabled() ? new SecureNio2Channel(buffer, this) : new Nio2Channel(buffer);
                    }
                    Nio2SocketWrapper created = new Nio2SocketWrapper(channel, this);
                    channel.reset(socket, created);
                    connections.put(socket, created);
                    wrapper = created;
                    socketProperties.setProperties(socket);
                    wrapper.setReadTimeout(getConnectionTimeout());
                    wrapper.setWriteTimeout(getConnectionTimeout());
                    wrapper.setKeepAliveLeft(getMaxKeepAliveRequests());
                }
                return processSocket(wrapper, SocketEvent.OPEN_READ, false);
            } catch (Throwable failure) {
                ExceptionUtils.handleThrowable(failure);
                getLog().error(sm.getString("endpoint.socketOptionsError"), failure);
                if (wrapper == null) {
                    destroySocket(socket);
                }
                return false;
            }
        }

        /** 只在锁内执行非阻塞提交；停止后旧队列任务及回调不会再访问已释放资源。 */
        private boolean acceptNext(Nio2Acceptor handler, boolean dispatch) {
            synchronized (acceptLifecycle) {
                if (!isRunning() || isPaused() || getServerSocket() == null) {
                    return false;
                }
                if (dispatch) {
                    getExecutor().execute(handler);
                } else {
                    ((AsynchronousServerSocketChannel) getServerSocket()).accept(null, handler);
                }
                return true;
            }
        }

        /**
         * 三条接收路径沿用 Apache Tomcat 9.0.83 Nio2Endpoint.Nio2Acceptor 的分支与计数逻辑，
         * 唯一变化是将直接 accept/execute 替换为生命周期互斥提交；业务处理在锁外。
         * 上游来源：https://github.com/apache/tomcat/blob/9.0.83/java/org/apache/tomcat/util/net/Nio2Endpoint.java
         * 上游代码按 Apache License 2.0 授权。
         */
        private final class ClosingSafeAcceptor extends Nio2Acceptor {
            ClosingSafeAcceptor() {
                super(ClosingSafeEndpoint.this);
            }

            @Override
            public void run() {
                if (!isPaused()) {
                    try {
                        countUpOrAwaitConnection();
                    } catch (InterruptedException exception) {
                        // 保留原接收循环的中断边界；真正停止由 paused/running 控制。
                    }
                    if (!isPaused()) {
                        acceptNext(this, false);
                    } else {
                        state = AcceptorState.PAUSED;
                    }
                } else {
                    state = AcceptorState.PAUSED;
                }
            }

            @Override
            public void completed(AsynchronousSocketChannel socket, Void attachment) {
                errorDelay = 0;
                if (isRunning() && !isPaused()) {
                    boolean accepted;
                    if (getMaxConnections() == -1) {
                        accepted = acceptNext(this, false);
                    } else if (getConnectionCount() < getMaxConnections()) {
                        try {
                            countUpOrAwaitConnection();
                        } catch (InterruptedException exception) {
                            // 与原实现一致；该分支仅在尚未达到连接限额时进入。
                        }
                        accepted = acceptNext(this, false);
                    } else {
                        accepted = acceptNext(this, true);
                    }
                    if (!accepted) {
                        // 成功回调可能已越过外层检查；关闭后只释放该新socket，不再启动HTTP处理。
                        destroySocket(socket);
                        return;
                    }
                    if (!setSocketOptions(socket)) {
                        closeSocket(socket);
                    }
                } else {
                    if (isRunning()) {
                        state = AcceptorState.PAUSED;
                    }
                    destroySocket(socket);
                }
            }

            @Override
            public void failed(Throwable failure, Void attachment) {
                if (isRunning()) {
                    if (!isPaused()) {
                        acceptNext(this, getMaxConnections() != -1);
                    } else {
                        state = AcceptorState.PAUSED;
                    }
                    countDownConnection();
                    errorDelay = handleExceptionWithDelay(errorDelay);
                    ExceptionUtils.handleThrowable(failure);
                    getLog().error(sm.getString("endpoint.accept.fail"), failure);
                } else {
                    countDownConnection();
                }
            }
        }
    }
}
