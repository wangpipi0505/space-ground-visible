package com.tx.alg;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 实际本地 HTTP 和 S3 兼容响应验证资源所有权，不使用假的 resolver 返回值。 */
class TrajectoryFileScopeTest {
    @TempDir
    Path temp;

    @Test
    void 重定向保留Excel后缀作用域关闭立即删除本地文件不删除() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/data.xlsx");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/data.xlsx", exchange -> {
            byte[] bytes = "原文件字节".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Path resolved;
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/start";
            try (TrajectoryFileResolver.ResolvedFile file = TrajectoryFileResolver.resolveScoped(url, temp, 1024)) {
                resolved = file.path;
                assertTrue(resolved.getFileName().toString().endsWith(".xlsx"));
                assertEquals(temp.toRealPath(), resolved.getParent());
                assertEquals("原文件字节", new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8));
            }
            assertFalse(Files.exists(resolved));
            Path local = Files.write(temp.resolve("local.csv"), new byte[]{1, 2, 3});
            try (TrajectoryFileResolver.ResolvedFile file = TrajectoryFileResolver.resolveScoped(local.toString(), temp, 1)) {
                assertEquals(local, file.path);
            }
            assertTrue(Files.exists(local));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 超限及HTTP失败不遗留下载临时文件() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large.csv", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(new byte[100]);
            exchange.close();
        });
        server.createContext("/missing.csv", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThrows(IOException.class, () -> TrajectoryFileResolver.resolveScoped(base + "/large.csv", temp, 99));
            assertThrows(IOException.class, () -> TrajectoryFileResolver.resolveScoped(base + "/missing.csv", temp, 99));
            try (java.util.stream.Stream<Path> files = Files.list(temp)) {
                assertEquals(0, files.count());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void MinioSDK真实下载本地S3兼容响应并按作用域删除() throws Exception {
        byte[] bytes = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        AtomicInteger objects = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bucket", exchange -> {
            byte[] response;
            if (exchange.getRequestURI().getQuery() != null && exchange.getRequestURI().getQuery().contains("location")) {
                response = "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
            } else {
                objects.incrementAndGet();
                response = bytes;
                exchange.getResponseHeaders().set("Content-Type", "text/csv");
                exchange.getResponseHeaders().set("ETag", "\"test-etag\"");
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        Path downloaded;
        try {
            String location = "minio://127.0.0.1:" + server.getAddress().getPort() + "/bucket/trajectory.csv";
            try (TrajectoryFileResolver.ResolvedFile file = TrajectoryFileResolver.resolveScoped(location, temp, 1024)) {
                downloaded = file.path;
                assertArrayEquals(bytes, Files.readAllBytes(file.path));
            }
            assertEquals(1, objects.get());
            assertFalse(Files.exists(downloaded));
        } finally {
            server.stop(0);
        }
    }
}
