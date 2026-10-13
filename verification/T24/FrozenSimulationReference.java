import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 独立模拟/重定时对照：加载冻结原Controller/DTO/算法，父加载器只包含JDK。
 * 同一浏览器实际请求原值不变，仅将输出目录替换为本次独立验证目录，避免写原目录。
 * args：原classes、原依赖目录、实际请求JSON、验证输出目录、响应正文JSON、状态文本。
 * 可选第七参数retime启用重定时；不传时沿用T24 simulate命令合同。
 */
public final class FrozenSimulationReference {
    public static void main(String[] args) throws Exception {
        String operation = args.length > 6 ? args[6] : "simulate";
        if (!"simulate".equals(operation) && !"retime".equals(operation)) {
            throw new IllegalArgumentException("只支持已验证的simulate或retime操作");
        }
        Path original = Paths.get(args[0]).toRealPath();
        Path output = Paths.get(args[3]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        List<URL> urls = new ArrayList<URL>();
        urls.add(original.toUri().toURL());
        try (Stream<Path> jars = Files.list(Paths.get(args[1]).toRealPath())) {
            for (Path jar : (Iterable<Path>) jars.filter(p -> p.toString().endsWith(".jar")).sorted()::iterator) {
                urls.add(jar.toUri().toURL());
            }
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]),
                ClassLoader.getSystemClassLoader().getParent())) {
            Class<?> controller = loader.loadClass("com.tx.alg.api.TargetTrajectoryFitController");
            Class<?> algorithm = loader.loadClass("com.tx.alg.trajectory.TargetTrajectoryFitter");
            for (Class<?> type : new Class<?>[]{controller, algorithm}) {
                Path origin = Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                if (!origin.equals(original)) throw new AssertionError("未使用指定冻结原实现：" + type.getName());
            }
            String dtoName = "retime".equals(operation) ? "RetimeTrajectoryRequest" : "SimulationRequest";
            Class<?> dto = loader.loadClass("com.tx.alg.trajectory.TargetTrajectoryFitter$" + dtoName);
            Class<?> mapperClass = loader.loadClass("com.fasterxml.jackson.databind.ObjectMapper");
            Object mapper = mapperClass.newInstance();
            Object request = mapperClass.getMethod("readValue", byte[].class, Class.class)
                    .invoke(mapper, Files.readAllBytes(Paths.get(args[2])), dto);
            dto.getField("outputDir").set(request, output.toString());
            Object response = controller.getMethod(operation, dto).invoke(controller.newInstance(), request);
            int status = (Integer) response.getClass().getMethod("getStatusCodeValue").invoke(response);
            Object body = response.getClass().getMethod("getBody").invoke(response);
            Method serialize = mapperClass.getMethod("writeValueAsBytes", Object.class);
            Files.write(Paths.get(args[4]), (byte[]) serialize.invoke(mapper, body));
            Files.write(Paths.get(args[5]), String.valueOf(status).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("冻结原" + operation + "控制器状态=" + status + "，算法来源=" + original);
        }
    }
}
