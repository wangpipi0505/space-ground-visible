import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 两个版本共用的窗口输出探针：仅反射选择真实请求与控制器，不变换请求及响应字段。
 * 控制器按自身实现计算，再执行自身流式写出回调；本探针不模拟HTTP序列化处理器。
 */
public final class WindowOutputProbe {
    /** 参数依次为版本标识、请求文件及全新结果文件；两个进程使用完全相同的请求字节。 */
    public static void main(String[] args) throws Exception {
        boolean original = "original".equals(args[0]);
        String prefix = original ? "com.tx.alg.api."
                : "com.runisys.orbit.spacevisibility.api.ballistic.";
        Class<?> requestType = Class.forName(prefix + "VisibilityRequest");
        Object request = new ObjectMapper().readValue(Files.readAllBytes(Paths.get(args[1])), requestType);
        Class<?> controllerType = Class.forName(prefix
                + (original ? "OrbitVisibilityController" : "BallisticVisibilityController"));
        Object controller;
        if (original) {
            controller = controllerType.newInstance();
        } else {
            Class<?> serviceType = Class.forName(
                    "com.runisys.orbit.spacevisibility.application.ballistic.BallisticVisibilityService");
            Constructor<?> constructor = controllerType.getConstructor(serviceType);
            controller = constructor.newInstance(serviceType.newInstance());
        }
        ResponseEntity<?> response = (ResponseEntity<?>) controllerType.getMethod("search", requestType)
                .invoke(controller, request);
        if (response.getStatusCodeValue() != 200 || !(response.getBody() instanceof StreamingResponseBody)) {
            throw new IllegalStateException("真实计算入口未成功：" + response.getStatusCodeValue()
                    + "，" + response.getBody());
        }
        Path output = Paths.get(args[2]);
        Files.createDirectories(output.getParent());
        try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
            ((StreamingResponseBody) response.getBody()).writeTo(stream);
        }
        System.out.println("完成 " + args[0] + "，Java=" + System.getProperty("java.version")
                + "，输出字节=" + Files.size(output));
    }
}
