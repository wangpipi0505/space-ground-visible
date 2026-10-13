package com.runisys.orbit.spacevisibility.infrastructure.web;

import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.Nio2Channel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.channels.NetworkChannel;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用真实 NIO2 监听通道和原接收循环重现关闭交错；只在第二次暂停状态读取后设置屏障，
 * 不修改 Tomcat 字段或伪造异常。未处理异常同时保留到 stderr 与断言，不能被测试框架漏报。
 */
class Nio2ShutdownRaceTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void 业务并发和连接限额等待都不持有关闭锁(int connections) throws Exception {
        InspectableEndpoint endpoint = new InspectableEndpoint();
        endpoint.setAddress(InetAddress.getLoopbackAddress()); endpoint.setPort(0);
        endpoint.setName("t26-business-lock"); endpoint.setMaxConnections(connections);
        endpoint.setExecutorTerminationTimeoutMillis(100);
        CountDownLatch entered = new CountDownLatch(connections), release = new CountDownLatch(1);
        endpoint.setHandler(new ClosingHandler() {
            @Override
            public SocketState process(SocketWrapperBase<Nio2Channel> socket, SocketEvent event) {
                entered.countDown();
                boolean interrupted = false;
                for (;;) {
                    try { release.await(); break; }
                    catch (InterruptedException exception) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return SocketState.CLOSED;
            }
        });
        List<Socket> clients = new java.util.ArrayList<Socket>();
        ExecutorService executor = null;
        try {
            endpoint.init(); endpoint.start();
            executor = (ExecutorService) endpoint.getExecutor();
            for (int index = 0; index < connections; index++) {
                clients.add(new Socket(InetAddress.getLoopbackAddress(), endpoint.getLocalPort()));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "多个业务回调必须可并行进入");
            assertTrue(endpoint.limitReached.await(5, TimeUnit.SECONDS), "接收线程必须到达连接限额");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                Thread acceptThread = endpoint.limitThread.get();
                if (acceptThread != null && acceptThread.getState() == Thread.State.WAITING) {
                    for (StackTraceElement frame : acceptThread.getStackTrace()) {
                        if (frame.getClassName().equals("org.apache.tomcat.util.threads.LimitLatch")
                                && frame.getMethodName().equals("countUpOrAwait")) waiting = true;
                    }
                }
                if (!waiting) java.util.concurrent.locks.LockSupport.parkNanos(1000000);
            }
            assertTrue(waiting, "须实际观察LimitLatch.countUpOrAwait中的WAITING后才开始关闭");
            System.out.println("T26_LIMIT_WAIT_CONFIRMED maxConnections=" + connections);
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            Thread closer = new Thread(() -> {
                try { endpoint.stop(); endpoint.destroy(); }
                catch (Throwable exception) { failure.set(exception); }
            }, "t26-business-close");
            closer.start(); closer.join(5000);
            assertFalse(closer.isAlive(), "原有限关闭等待不能变为等待业务释放的无界锁等待");
            assertNull(failure.get());
            assertNull(endpoint.channel());
        } finally {
            release.countDown();
            for (Socket client : clients) client.close();
            endpoint.stop(); endpoint.destroy();
            if (executor != null) assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void 接收已提交但注册尚未开始时关闭不得再配置已关闭连接() throws Exception {
        LateEndpoint endpoint = new LateEndpoint();
        endpoint.setAddress(InetAddress.getLoopbackAddress());
        endpoint.setPort(0);
        endpoint.setName("t26-late-options");
        endpoint.setHandler(new ClosingHandler());
        endpoint.setExecutorTerminationTimeoutMillis(100);
        List<LogRecord> errors = new CopyOnWriteArrayList<LogRecord>();
        Logger logger = Logger.getLogger("org.apache.tomcat.util.net.Nio2Endpoint");
        Handler capture = new Handler() {
            public void publish(LogRecord record) { if (record.getLevel().intValue() >= Level.SEVERE.intValue()) errors.add(record); }
            public void flush() { }
            public void close() { }
        };
        logger.addHandler(capture);
        Socket socket = null;
        try {
            endpoint.init(); endpoint.start();
            socket = new Socket(InetAddress.getLoopbackAddress(), endpoint.getLocalPort());
            assertTrue(endpoint.entered.await(5, TimeUnit.SECONDS));
            // 原关闭有有限执行器等待；故意阻塞的回调超时警告必须保留，随后放行并证明线程收敛。
            endpoint.stop(); endpoint.destroy();
            endpoint.resume.countDown();
            assertTrue(endpoint.finished.await(5, TimeUnit.SECONDS));
            assertTrue(errors.isEmpty(), "关闭后配置socket产生后台错误: " + errors);
        } finally {
            endpoint.resume.countDown();
            if (socket != null) socket.close();
            endpoint.stop(); endpoint.destroy();
            logger.removeHandler(capture);
        }
    }

    @Test
    void 同一端点关闭后再次绑定会替换通道并释放端口与执行器() throws Exception {
        InspectableEndpoint endpoint = new InspectableEndpoint();
        endpoint.setAddress(InetAddress.getLoopbackAddress());
        endpoint.setPort(0);
        endpoint.setName("t26-rebind");
        endpoint.setHandler(new ClosingHandler());
        NetworkChannel previous = null;
        // 三轮覆盖首次init、同一实例再次bind、再次释放；不以重复次数代替确定交错回归。
        for (int round = 0; round < 3; round++) {
            endpoint.init();
            endpoint.start();
            NetworkChannel channel = endpoint.channel();
            assertNotSame(previous, channel);
            int port = endpoint.getLocalPort();
            ExecutorService executor = (ExecutorService) endpoint.getExecutor();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.setSoTimeout(5000);
                assertEquals(-1, client.getInputStream().read(), "实际连接应由CLOSED处理器关闭");
            } finally {
                endpoint.stop();
                endpoint.destroy();
            }
            assertFalse(channel.isOpen(), "原监听句柄必须关闭");
            assertNull(endpoint.channel(), "不能靠永久保留已关闭引用掩盖竞争");
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "原执行器必须退出");
            try (ServerSocket rebound = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                assertEquals(port, rebound.getLocalPort(), "原端口应可重新绑定");
            }
            previous = channel;
        }
    }

    @Test
    void 关闭不得在已通过暂停检查的接收循环中清空通道() throws Exception {
        verifyRace("run", 100);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1, 100})
    void 成功回调与关闭竞争覆盖无限额直接接收和限额重新排队(int maxConnections) throws Exception {
        verifyRace("completed", maxConnections);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 100})
    void 失败回调与关闭竞争覆盖直接接收和重新排队(int maxConnections) throws Exception {
        verifyRace("failed", maxConnections);
    }

    /** 注入真实 socket 成功/关闭失败，并在原回调状态检查后暂停，验证全部接收提交分支。 */
    private void verifyRace(String method, int maxConnections) throws Exception {
        PausingEndpoint endpoint = new PausingEndpoint(method);
        endpoint.setAddress(InetAddress.getLoopbackAddress());
        endpoint.setPort(0);
        endpoint.setMaxConnections(maxConnections);
        endpoint.setName("t26-race");
        endpoint.setHandler(new ClosingHandler());
        AtomicReference<Throwable> uncaught = new AtomicReference<Throwable>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (thread.getName().startsWith("t26-race")) {
                uncaught.compareAndSet(null, failure);
            }
            if (previous != null) {
                previous.uncaughtException(thread, failure);
            } else {
                failure.printStackTrace(System.err);
            }
        });
        AtomicReference<Throwable> closeFailure = new AtomicReference<Throwable>();
        Thread closer = new Thread(() -> {
            try {
                endpoint.stop();
                endpoint.destroy();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        }, "t26-close");
        Socket client = null;
        try {
            endpoint.init();
            endpoint.start();
            if (method.equals("completed")) {
                client = new Socket(InetAddress.getLoopbackAddress(), endpoint.getLocalPort());
            } else if (method.equals("failed")) {
                endpoint.failPendingAccept();
            }
            assertTrue(endpoint.checked.await(5, TimeUnit.SECONDS), "接收循环未到达第二次检查");
            closer.start();
            assertTrue(endpoint.closed.await(5, TimeUnit.SECONDS), "通道未关闭");
            endpoint.resumeChecked.countDown();
            closer.join(10000);
            assertFalse(closer.isAlive(), "关闭线程必须退出");
            assertNull(closeFailure.get(), "生命周期关闭不得失败");
            assertNull(uncaught.get(), "接收线程不得出现未处理异常");
            assertEquals(0, endpoint.lateSocketOptions, "关闭后延迟到达的成功回调必须释放socket，不得启动HTTP处理");
        } finally {
            endpoint.resumeChecked.countDown();
            closer.join(10000);
            endpoint.stop();
            endpoint.destroy();
            if (client != null) client.close();
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    /** 读取旧值后等待关闭，只控制操作次序；实际 run、stop、unbind 均来自运行依赖。 */
    private static final class PausingEndpoint extends ClosingSafeNio2Protocol.ClosingSafeEndpoint {
        final CountDownLatch checked = new CountDownLatch(1);
        final CountDownLatch resumeChecked = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        private final String method;
        private int checks;
        private volatile int lateSocketOptions;

        PausingEndpoint(String method) { this.method = method; }

        void failPendingAccept() throws java.io.IOException { getServerSocket().close(); }

        @Override
        protected boolean setSocketOptions(java.nio.channels.AsynchronousSocketChannel socket) {
            if (!isRunning()) lateSocketOptions++;
            return super.setSocketOptions(socket);
        }

        @Override
        public boolean isPaused() {
            boolean result = super.isPaused();
            boolean inCallback = false;
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (frame.getClassName().contains("Acceptor") && frame.getMethodName().equals(method)) {
                    inCallback = true;
                    break;
                }
            }
            if (Thread.currentThread().getName().startsWith("t26-race") && inCallback
                    && ++checks == (method.equals("run") ? 2 : 1)) {
                checked.countDown();
                try {
                    if (!resumeChecked.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("接收线程屏障等待超时");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return result;
        }

        @Override
        protected void doCloseServerSocket() throws java.io.IOException {
            super.doCloseServerSocket();
            closed.countDown();
        }
    }

    /** 只暴露受保护通道用于句柄关闭断言，不改变端点行为。 */
    private static final class InspectableEndpoint extends ClosingSafeNio2Protocol.ClosingSafeEndpoint {
        final CountDownLatch limitReached = new CountDownLatch(1);
        final AtomicReference<Thread> limitThread = new AtomicReference<Thread>();
        NetworkChannel channel() { return getServerSocket(); }
        @Override
        protected void countUpOrAwaitConnection() throws InterruptedException {
            if (getMaxConnections() > 0 && getConnectionCount() >= getMaxConnections()) {
                limitThread.set(Thread.currentThread());
                limitReached.countDown();
            }
            super.countUpOrAwaitConnection();
        }
    }

    /** 独立审查发现的精确交错：acceptNext返回后，在socket注册前让完整关闭先完成。 */
    private static final class LateEndpoint extends ClosingSafeNio2Protocol.ClosingSafeEndpoint {
        final CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1), finished = new CountDownLatch(1);
        @Override
        protected boolean setSocketOptions(java.nio.channels.AsynchronousSocketChannel socket) {
            entered.countDown();
            boolean interrupted = false;
            for (;;) {
                try { resume.await(); break; }
                catch (InterruptedException exception) { interrupted = true; }
            }
            try { return super.setSocketOptions(socket); }
            finally { finished.countDown(); if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    /** 本测试不解析 HTTP，接受到的实际 socket 立即按 Tomcat CLOSED 状态释放。 */
    private static class ClosingHandler implements AbstractEndpoint.Handler<Nio2Channel> {
        public SocketState process(SocketWrapperBase<Nio2Channel> socket, SocketEvent event) { return SocketState.CLOSED; }
        public Object getGlobal() { return null; }
        public Set<Nio2Channel> getOpenSockets() { return Collections.emptySet(); }
        public void release(SocketWrapperBase<Nio2Channel> socket) { }
        public void pause() { }
        public void recycle() { }
    }
}
