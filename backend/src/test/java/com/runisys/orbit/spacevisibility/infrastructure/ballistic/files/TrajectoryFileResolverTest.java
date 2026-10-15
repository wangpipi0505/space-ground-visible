package com.runisys.orbit.spacevisibility.infrastructure.ballistic.files;

import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结原算法回归，断言及容差保持原版；参考样例位于独立test-data目录。 */
class TrajectoryFileResolverTest {

    private static final String MINI_CSV =
            "YYYY,MM,dd,HH,mm,ss.SSS,ECEF_X(m),ECEF_Y(m),ECEF_Z(m),energy,dt(ms),ECI_X(m),ECI_Y(m),ECI_Z(m),ECI_Vx(m/s),ECI_Vy(m/s),ECI_Vz(m/s),ECEF_Vx(m/s),ECEF_Vy(m/s),ECEF_Vz(m/s)\n"
                    + "2026,07,01,07,57,00.000,-1832845.456,4636330.230,3967380.757,0,0,-4301581.621,2502635.615,3978467.175,1443.381682,1559.351439,574.644986,2436.089309,463.729635,578.427477\n"
                    + "2026,07,01,07,57,00.100,-1832601.847,4636376.602,3967438.600,0,100,-4301437.288,2502791.545,3978524.640,1443.364909,1559.369072,574.637628,2436.092878,463.720791,578.420077\n";

    @Test
    void isRemoteLocation识别各前缀() {
        assertTrue(TrajectoryFileResolver.isRemoteLocation("http://example.com/a.csv"));
        assertTrue(TrajectoryFileResolver.isRemoteLocation("https://example.com/a.csv"));
        assertTrue(TrajectoryFileResolver.isRemoteLocation("minio://host:9000/bucket/a.csv"));
    }

    @Test
    void isRemoteLocation前缀大小写不敏感并忽略首尾空白() {
        assertTrue(TrajectoryFileResolver.isRemoteLocation("  HTTP://example.com/a.csv  "));
        assertTrue(TrajectoryFileResolver.isRemoteLocation("MinIO://host/bucket/a.csv"));
    }

    @Test
    void isRemoteLocation对本地路径和空值返回false() {
        assertFalse(TrajectoryFileResolver.isRemoteLocation("D:/data/trajectory.csv"));
        assertFalse(TrajectoryFileResolver.isRemoteLocation("trajectory-0.1s-standard.csv"));
        assertFalse(TrajectoryFileResolver.isRemoteLocation("miniox://host/bucket/a.csv"));
        assertFalse(TrajectoryFileResolver.isRemoteLocation(null));
        assertFalse(TrajectoryFileResolver.isRemoteLocation(""));
    }

    @Test
    void 本地路径原样返回() throws IOException {
        Path resolved = TrajectoryFileResolver.resolveToLocalFile("data/trajectory-0.1s-standard.csv");
        assertEquals(Paths.get("data/trajectory-0.1s-standard.csv"), resolved);
        // 不存在的本地路径也原样返回，存在性错误沿用现有 Path 解析行为
        Path missing = TrajectoryFileResolver.resolveToLocalFile("data/not-exist.csv");
        assertEquals(Paths.get("data/not-exist.csv"), missing);
    }

    @Test
    void http地址下载后可被String入口解析() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/trajectory/mini.csv", exchange -> {
            byte[] body = MINI_CSV.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/trajectory/mini.csv";
            Path resolved = TrajectoryFileResolver.resolveToLocalFile(url);
            assertTrue(Files.exists(resolved));
            assertEquals(MINI_CSV, new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8));

            List<OrbitCalculator.BallisticPoint> points = TrajectoryFileResolver.parseBallisticCsv(url);
            assertEquals(2, points.size());
            assertEquals(0.0, points.get(0).tSec, 1e-9);
            assertEquals(0.1, points.get(1).tSec, 1e-3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void http非2xx响应抛出含状态码的IOException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing.csv", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing.csv";
            IOException ex = assertThrows(IOException.class, () -> TrajectoryFileResolver.resolveToLocalFile(url));
            assertTrue(ex.getMessage().contains("404"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void minio地址解析完整形式() {
        TrajectoryFileResolver.MinioLocation location =
                TrajectoryFileResolver.parseMinioLocation("minio://ak:sk@192.168.1.10:9001/bucket/dir/target.csv?secure=true");
        assertEquals("192.168.1.10", location.host);
        assertEquals(9001, location.port);
        assertEquals("bucket", location.bucket);
        assertEquals("dir/target.csv", location.object);
        assertEquals("ak", location.accessKey);
        assertEquals("sk", location.secretKey);
        assertTrue(location.secure);
    }

    @Test
    void minio地址解析缺省值() {
        TrajectoryFileResolver.MinioLocation location =
                TrajectoryFileResolver.parseMinioLocation("minio://minio.example.com/bucket/target.xlsx");
        assertEquals("minio.example.com", location.host);
        assertEquals(9000, location.port);
        assertEquals("bucket", location.bucket);
        assertEquals("target.xlsx", location.object);
        assertNull(location.accessKey);
        assertNull(location.secretKey);
        assertFalse(location.secure);
    }

    @Test
    void minio地址secure时缺省端口为443() {
        TrajectoryFileResolver.MinioLocation location =
                TrajectoryFileResolver.parseMinioLocation("minio://minio.example.com/bucket/target.csv?secure=true");
        assertEquals(443, location.port);
        assertTrue(location.secure);
    }

    @Test
    void minio地址object名按URL解码() {
        TrajectoryFileResolver.MinioLocation location =
                TrajectoryFileResolver.parseMinioLocation("minio://host/bucket/%E5%BC%B9%E9%81%93%20a.csv");
        assertEquals("弹道 a.csv", location.object);
    }

    @Test
    void 非法minio地址抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> TrajectoryFileResolver.parseMinioLocation("minio://host/onlybucket"));
        assertThrows(IllegalArgumentException.class,
                () -> TrajectoryFileResolver.parseMinioLocation("minio://host"));
        assertThrows(IllegalArgumentException.class,
                () -> TrajectoryFileResolver.parseMinioLocation("minio://host/bucket/"));
    }

    @Test
    void 空位置字符串抛异常() {
        assertThrows(IllegalArgumentException.class, () -> TrajectoryFileResolver.resolveToLocalFile(null));
        assertThrows(IllegalArgumentException.class, () -> TrajectoryFileResolver.resolveToLocalFile("   "));
    }

    @Test
    void String入口解析本地文件行为不变() throws IOException {
        Path tempFile = Files.createTempFile("alg-trajectory-test-", ".csv");
        tempFile.toFile().deleteOnExit();
        Files.write(tempFile, MINI_CSV.getBytes(StandardCharsets.UTF_8));
        List<OrbitCalculator.BallisticPoint> points = TrajectoryFileResolver.parseBallisticCsv(tempFile.toString());
        assertEquals(2, points.size());
        assertNotEquals(0.0, OrbitCalculator.referenceEpochSec);
    }
}
