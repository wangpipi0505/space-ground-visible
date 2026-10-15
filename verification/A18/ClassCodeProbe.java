import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** 比较最终导入整理前后的实际class指令和属性，只排除源码行号等调试信息。 */
public final class ClassCodeProbe {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        List<String> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class")).sorted()::iterator) {
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(Files.readAllBytes(file)).accept(writer, ClassReader.SKIP_DEBUG);
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(writer.toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte value : hash) hex.append(String.format("%02x", value & 255));
                result.add(root.relativize(file).toString().replace('\\', '/') + " " + hex);
            }
        }
        Files.write(Paths.get(args[1]), result, StandardCharsets.UTF_8);
        System.out.println("无调试信息的class记录=" + result.size());
    }
}
