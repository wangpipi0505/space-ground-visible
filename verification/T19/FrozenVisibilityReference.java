import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** 独立加载冻结原控制器及依赖，父加载器只有JDK，避免真实HTTP对照偷偷调用迁入实现。 */
public final class FrozenVisibilityReference {
    public static void main(String[] args) throws Exception {
        Path classes = Paths.get(args[0]).toRealPath();
        List<URL> urls = new ArrayList<URL>();
        urls.add(classes.toUri().toURL());
        try (Stream<Path> jars = Files.list(Paths.get(args[1]).toRealPath())) {
            for (Path jar : (Iterable<Path>) jars.filter(p -> p.toString().endsWith(".jar")).sorted()::iterator) {
                urls.add(jar.toUri().toURL());
            }
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]),
                ClassLoader.getSystemClassLoader().getParent())) {
            Class<?> controllerClass = loader.loadClass("com.tx.alg.api.OrbitVisibilityController");
            Path loaded = Paths.get(controllerClass.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!loaded.equals(classes)) throw new AssertionError("未加载指定冻结原控制器");
            Class<?> requestClass = loader.loadClass("com.tx.alg.api.VisibilityRequest");
            Class<?> mapperClass = loader.loadClass("com.fasterxml.jackson.databind.ObjectMapper");
            Object mapper = mapperClass.newInstance();
            Object request = mapperClass.getMethod("readValue", byte[].class, Class.class)
                    .invoke(mapper, Files.readAllBytes(Paths.get(args[2])), requestClass);
            Object response = controllerClass.getMethod("search", requestClass)
                    .invoke(controllerClass.newInstance(), request);
            int status = (Integer) response.getClass().getMethod("getStatusCodeValue").invoke(response);
            if (status != 200) throw new AssertionError("冻结原请求失败，状态=" + status);
            Object body = response.getClass().getMethod("getBody").invoke(response);
            Method write = loader.loadClass("org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody")
                    .getMethod("writeTo", OutputStream.class);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            write.invoke(body, bytes);
            Files.write(Paths.get(args[3]), bytes.toByteArray());
            System.out.println("冻结原控制器已独立输出 " + bytes.size() + " 字节，来源=" + classes);
        }
    }
}
