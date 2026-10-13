import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.domain.window.WindowReferenceScenarios;
import java.io.File;

/** 只读原class/JAR独立进程入口；同一个样例驱动也由正常JUnit调用当前实现。 */
public final class OriginalWindowProbe {
    public static void main(String[] args) throws Exception {
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File(args[0]), WindowReferenceScenarios.capture());
    }
}
