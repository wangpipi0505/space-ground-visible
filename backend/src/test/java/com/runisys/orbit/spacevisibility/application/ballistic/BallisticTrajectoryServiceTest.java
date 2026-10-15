package com.runisys.orbit.spacevisibility.application.ballistic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.infrastructure.ballistic.files.BallisticFileConfiguration;
import com.runisys.orbit.spacevisibility.infrastructure.ballistic.files.BallisticFileStorage;
import com.sun.net.httpserver.HttpServer;
import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 原格式真实解析验证：参考 CSV 前两点生成 Excel 单元格，不改原解析算法和数值期望。 */
class BallisticTrajectoryServiceTest {
    @TempDir
    Path root;

    private String sample() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("../test-data/reference/ballistic/trajectory-0.1s-standard.csv"), StandardCharsets.UTF_8);
        return lines.get(0) + "\n" + lines.get(1) + "\n" + lines.get(2) + "\n";
    }

    private BallisticTrajectoryService service() throws Exception {
        BallisticFileConfiguration config = new BallisticFileConfiguration(root.toString(), "", "", "", "", 2097152, 60000, 500);
        return new BallisticTrajectoryService(new BallisticFileStorage(config, new ObjectMapper()), config);
    }

    @Test
    void XLS及XLSX及XLSM沿用首工作表原解析数值() throws Exception {
        BallisticTrajectoryService service = service();
        String sample = sample();
        OrbitCalculator.ParsedBallisticCsv expected = OrbitCalculator.parseBallisticCsvWithReference(new java.io.StringReader(sample));
        for (String extension : new String[]{"xls", "xlsx", "xlsm"}) {
            byte[] bytes = workbook(sample, extension);
            Path path = Files.write(root.resolve("trajectory." + extension), bytes);
            BallisticTrajectoryService.ParsedTrajectory actual = service.parse(path.toString());
            assertEquals(2, actual.parsed.points.size());
            assertEquals(expected.referenceEpochSec, actual.parsed.referenceEpochSec);
            assertEquals(expected.points.get(1).eciX, actual.parsed.points.get(1).eciX);
            assertEquals(expected.points.get(1).eciVx, actual.parsed.points.get(1).eciVx);
            assertEquals(expected.points.get(1).tSec, actual.parsed.points.get(1).tSec);
        }
    }

    @Test
    void 已登记路径两侧空白保留原串且全量解析与独立历元不变() throws Exception {
        BallisticFileConfiguration config = new BallisticFileConfiguration(root.toString(), "", "", "", "", 2097152, 60000, 500);
        BallisticFileStorage storage = new BallisticFileStorage(config, new ObjectMapper());
        BallisticTrajectoryService service = new BallisticTrajectoryService(storage, config);
        // 全量来源为已有带来源及校验值的参考 CSV；次日仅平移 UTC 日期，不修改数值模型。
        byte[] csv = Files.readAllBytes(Paths.get("../test-data/reference/ballistic/trajectory-0.1s-standard.csv"));
        String serverPath = storage.upload(new ByteArrayInputStream(csv), "day1.csv", "text/csv", "trajectory").serverPath;
        byte[] nextDay = new String(csv, StandardCharsets.UTF_8).replace("2026,07,01", "2026,07,02")
                .getBytes(StandardCharsets.UTF_8);
        String nextPath = storage.upload(new ByteArrayInputStream(nextDay), "day2.csv", "text/csv", "trajectory").serverPath;
        double initial = OrbitCalculator.referenceEpochSec;
        String paddedPath = "  " + serverPath + "  ";
        BallisticTrajectoryService.ParsedTrajectory plain = service.parse(serverPath);
        BallisticTrajectoryService.ParsedTrajectory padded = service.parse(paddedPath);
        BallisticTrajectoryService.ParsedTrajectory shifted = service.parse(" " + nextPath + " ");
        assertEquals(paddedPath, padded.sourceLocation, "旧输出回显必须保留调用方原位置串");
        assertEquals(8560, plain.parsed.points.size());
        assertEquals(8560, padded.parsed.points.size());
        assertEquals(8560, shifted.parsed.points.size());
        assertEquals(plain.parsed.referenceEpochSec, padded.parsed.referenceEpochSec);
        assertEquals(86400, shifted.parsed.referenceEpochSec - padded.parsed.referenceEpochSec);
        assertEquals(initial, OrbitCalculator.referenceEpochSec, "每个目标独立携带源历元，不改全局历元");
        // 逐点比较原输入时间及全部 ECI/ECEF 位置速度；派生大地高不加入已知 JIT 舍入敏感断言。
        for (int i = 0; i < plain.parsed.points.size(); i++) {
            OrbitCalculator.BallisticPoint expected = plain.parsed.points.get(i);
            OrbitCalculator.BallisticPoint actual = padded.parsed.points.get(i);
            assertArrayEquals(new double[]{expected.tSec, expected.eciX, expected.eciY, expected.eciZ,
                            expected.eciVx, expected.eciVy, expected.eciVz, expected.ecefX, expected.ecefY,
                            expected.ecefZ, expected.ecefVx, expected.ecefVy, expected.ecefVz},
                    new double[]{actual.tSec, actual.eciX, actual.eciY, actual.eciZ,
                            actual.eciVx, actual.eciVy, actual.eciVz, actual.ecefX, actual.ecefY,
                            actual.ecefZ, actual.ecefVx, actual.ecefVy, actual.ecefVz});
        }
    }

    @Test
    void HTTP原URI独立源历元和失败后temp目录为空() throws Exception {
        BallisticTrajectoryService service = service();
        byte[] csv = sample().getBytes(StandardCharsets.UTF_8);
        byte[] nextDay = sample().replace("2026,07,01", "2026,07,02").getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/day1.csv", exchange -> {
            exchange.sendResponseHeaders(200, csv.length);
            exchange.getResponseBody().write(csv);
            exchange.close();
        });
        server.createContext("/day2.csv", exchange -> {
            exchange.sendResponseHeaders(200, nextDay.length);
            exchange.getResponseBody().write(nextDay);
            exchange.close();
        });
        server.createContext("/invalid.csv", exchange -> {
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write(new byte[]{1, 2, 3});
            exchange.close();
        });
        server.start();
        double initial = OrbitCalculator.referenceEpochSec;
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            BallisticTrajectoryService.ParsedTrajectory one = service.parse(base + "/day1.csv");
            BallisticTrajectoryService.ParsedTrajectory two = service.parse(base + "/day2.csv");
            assertEquals(base + "/day1.csv", one.sourceLocation);
            assertEquals(base + "/day2.csv", two.sourceLocation);
            assertEquals(86400, two.parsed.referenceEpochSec - one.parsed.referenceEpochSec);
            assertEquals(initial, OrbitCalculator.referenceEpochSec);
            assertThrows(IllegalArgumentException.class, () -> service.parse(base + "/invalid.csv"));
            try (java.util.stream.Stream<Path> temporary = Files.list(root.resolve("temp"))) {
                assertEquals(0, temporary.count());
            }
        } finally {
            server.stop(0);
        }
    }

    /** 生成真实 POI 文件：首表保留原数值文本，次表放无效内容证明仍按原格式只读首表。 */
    private static byte[] workbook(String csv, String extension) throws Exception {
        try (Workbook workbook = extension.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (extension.equals("xlsm")) {
                ((XSSFWorkbook) workbook).setWorkbookType(org.apache.poi.xssf.usermodel.XSSFWorkbookType.XLSM);
            }
            Sheet sheet = workbook.createSheet("弹道");
            String[] lines = csv.split("\n");
            for (int r = 0; r < lines.length; r++) {
                Row row = sheet.createRow(r);
                String[] cells = lines[r].split(",");
                for (int c = 0; c < cells.length; c++) {
                    row.createCell(c).setCellValue(cells[c]);
                }
            }
            workbook.createSheet("不参与解析").createRow(0).createCell(0).setCellValue("ignored");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
