import com.runisys.orbit.spacevisibility.SpaceTargetVisibleApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 验收专用入口：从正式 Boot 包加载应用，以本事项控制文件触发真实 context.close，不增加产品 HTTP 接口。 */
public final class ManagedServer {
    public static void main(String[] args) throws Exception {
        final AtomicInteger uncaught = new AtomicInteger();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            uncaught.incrementAndGet();
            System.err.println("T26_UNCAUGHT thread=" + thread.getName());
            error.printStackTrace(System.err);
        });
        Path control = Paths.get(System.getProperty("t26.control")).toAbsolutePath();
        Files.createDirectories(control);
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            control.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            ConfigurableApplicationContext context = SpringApplication.run(SpaceTargetVisibleApplication.class, args);
            System.out.println("T26_READY pid=" + ManagementFactory.getRuntimeMXBean().getName()
                    + " headless=" + java.awt.GraphicsEnvironment.isHeadless()
                    + " source=" + SpaceTargetVisibleApplication.class.getProtectionDomain().getCodeSource().getLocation());
            Files.write(control.resolve("READY"), ManagementFactory.getRuntimeMXBean().getName().getBytes(StandardCharsets.UTF_8));
            try {
                while (!Files.exists(control.resolve("STOP"))) {
                    WatchKey key = watcher.take();
                    key.pollEvents();
                    if (!key.reset()) throw new IllegalStateException("验收控制目录不可用");
                }
            } finally {
                System.out.println("T26_CONTEXT_CLOSE_BEGIN");
                context.close();
                System.out.println("T26_CONTEXT_CLOSE_END uncaught=" + uncaught.get());
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    if (thread.isAlive() && !thread.isDaemon() && thread != Thread.currentThread())
                        System.out.println("T26_NON_DAEMON_REMAINING " + thread.getName() + " " + thread.getState());
                }
            }
        }
        if (uncaught.get() != 0) throw new AssertionError("存在未处理后台异常：" + uncaught.get());
        System.out.println("T26_MAIN_RETURN");
    }
}
