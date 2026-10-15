import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.runisys.orbit.spacevisibility.SpaceTargetVisibleApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 真实Spring上下文的HTTP路由与Jackson属性合同探针。
 * 分别绑定迁移前后构建，记录实际35操作及契约字段，不依赖OpenAPI文件推断运行结果。
 */
public final class ApiContractProbe {
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectories(output);
        SpringApplication app = new SpringApplication(SpaceTargetVisibleApplication.class);
        try (ConfigurableApplicationContext context = app.run(
                "--server.address=127.0.0.1", "--server.port=0", "--spring.profiles.active=demo",
                "--spring.datasource.url=jdbc:h2:mem:a18_contract;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "--ballistic.files.root=" + output.resolve("storage"), "--logging.level.root=WARN",
                "--space.visibility.runtime.tomcat-protocol=org.apache.coyote.http11.Http11Nio2Protocol")) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            Map<String, Object> routes = new TreeMap<>();
            context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().forEach((mapping, handler) -> {
                if (handler.getBeanType().getName().startsWith("com.runisys.orbit.spacevisibility.api.")) {
                    Map<String, Object> value = new TreeMap<>();
                    value.put("consumes", mapping.getConsumesCondition().toString());
                    value.put("produces", mapping.getProducesCondition().toString());
                    value.put("params", mapping.getParamsCondition().toString());
                    value.put("headers", mapping.getHeadersCondition().toString());
                    value.put("method", handler.getMethod().getName());
                    routes.put(mapping.getMethodsCondition() + " " + mapping.getPatternValues(), value);
                }
            });
            if (routes.size() != 35) throw new AssertionError("实际操作数不是35：" + routes.size());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("routes.json").toFile(), routes);
            Map<String, Object> properties = new TreeMap<>();
            for (String name : args[1].split(",")) {
                inspect(mapper, Class.forName(name), properties);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("jackson.json").toFile(), properties);
            System.out.println("实际HTTP操作=" + routes.size() + "，契约类型=" + properties.size());
        }
    }

    /** 比较JSON实际属性、类型、读写方向与日期格式；包改名由外部逐类映射规范化。 */
    private static void inspect(ObjectMapper mapper, Class<?> type, Map<String, Object> result) {
        if (type.isEnum() || type.isInterface()) return;
        BeanDescription description = mapper.getSerializationConfig().introspect(mapper.constructType(type));
        Map<String, Object> fields = new TreeMap<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            Map<String, Object> field = new TreeMap<>();
            field.put("type", property.getPrimaryType().toCanonical());
            field.put("serialize", property.couldSerialize());
            field.put("deserialize", property.couldDeserialize());
            JsonFormat format = property.getPrimaryMember() == null ? null : property.getPrimaryMember().getAnnotation(JsonFormat.class);
            if (format != null) field.put("format", Arrays.asList(format.pattern(), format.timezone(), format.shape().name()));
            fields.put(property.getName(), field);
        }
        result.put(type.getName(), fields);
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (java.lang.reflect.Modifier.isPublic(nested.getModifiers())) inspect(mapper, nested, result);
        }
    }
}
