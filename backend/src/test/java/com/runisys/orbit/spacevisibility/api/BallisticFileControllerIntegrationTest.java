package com.runisys.orbit.spacevisibility.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** T13 文件辅助协议：使用独立临时目录及真实字节，避免接触生产运行文件。 */
@SpringBootTest
@AutoConfigureMockMvc
class BallisticFileControllerIntegrationTest {

    @TempDir
    static Path root;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private com.runisys.orbit.spacevisibility.application.ballistic.BallisticTrajectoryService parser;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("ballistic.files.root", () -> root.toString());
        registry.add("ballistic.files.max-bytes", () -> "2097152");
    }

    @Test
    void 上传原字节下载及分页预览保留中文文件名() throws Exception {
        byte[] bytes = "名称,数值\n目标甲,1\n目标乙,2\n".getBytes(StandardCharsets.UTF_8);
        JsonNode ref = upload("弹道 示例.csv", bytes);
        assertEquals("弹道 示例.csv", ref.path("originalName").asText());
        assertEquals(bytes.length, ref.path("sizeBytes").asLong());
        assertArrayEquals(bytes, Files.readAllBytes(java.nio.file.Paths.get(ref.path("serverPath").asText())));
        String id = ref.path("fileId").asText();
        assertTrue(id.matches("[a-f0-9-]{36}"));
        byte[] downloaded = mvc.perform(get("/api/v1/ballistic/files/{id}/download", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(bytes, downloaded);
        mvc.perform(get("/api/v1/ballistic/files/{id}/preview", id).param("page", "2").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.header[0]").value("名称"))
                .andExpect(jsonPath("$.rows[0][0]").value("目标乙"))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.metadata").isEmpty());
    }

    @Test
    void 登记结果仅允许管理根并复用相同引用() throws Exception {
        Path result = root.resolve("results/结果.csv");
        Files.createDirectories(result.getParent());
        Files.write(result, "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        String body = mapper.createObjectNode().put("csvPath", result.toString())
                .put("taskName", "任务甲").put("targetId", "目标甲").toString();
        String first = mvc.perform(post("/api/v1/ballistic/files/register-result")
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = mvc.perform(post("/api/v1/ballistic/files/register-result")
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(mapper.readTree(first).path("fileId"), mapper.readTree(second).path("fileId"));
        String id = mapper.readTree(first).path("fileId").asText();
        assertArrayEquals(Files.readAllBytes(result), mvc.perform(get("/api/v1/ballistic/files/{id}/download", id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/v1/ballistic/files/{id}/preview", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.header[0]").value("a"))
                .andExpect(jsonPath("$.rows[0][1]").value("2"));
        mvc.perform(post("/api/v1/ballistic/files/register-result").contentType("application/json")
                .content(mapper.createObjectNode().put("csvPath", root.resolve("../outside.csv").toString()).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists());
    }

    @Test
    void 未知引用参数错误及超限使用辅助错误结构() throws Exception {
        mvc.perform(get("/api/v1/ballistic/files/unknown/download"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        mvc.perform(multipart("/api/v1/ballistic/files"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(multipart("/api/v1/ballistic/files")
                .file(new MockMultipartFile("file", "big.csv", "text/csv", new byte[2097153])))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        JsonNode ref = upload("small.csv", "a\n1\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/ballistic/files/{id}/preview", ref.path("fileId").asText()).param("limit", "501"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 登记基础设施不可用返回500并删除未完成上传() throws Exception {
        Path metadata = root.resolve("metadata");
        Path backup = root.resolve("metadata-test-backup");
        long before;
        try (java.util.stream.Stream<Path> files = Files.list(root.resolve("uploads"))) {
            before = files.count();
        }
        Files.move(metadata, backup);
        try {
            mvc.perform(multipart("/api/v1/ballistic/files")
                    .file(new MockMultipartFile("file", "failed.csv", "text/csv", new byte[]{1})))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
            try (java.util.stream.Stream<Path> files = Files.list(root.resolve("uploads"))) {
                assertEquals(before, files.count());
            }
        } finally {
            Files.move(backup, metadata);
        }
    }

    @Test
    void 上传参考弹道原始位置速度全部点逐位相同且不改静态历元() throws Exception {
        Path source = java.nio.file.Paths.get("../test-data/reference/ballistic/trajectory-0.1s-standard.csv");
        byte[] bytes = Files.readAllBytes(source);
        JsonNode reference = upload("参考弹道.csv", bytes);
        com.tx.alg.OrbitCalculator.ParsedBallisticCsv expected = com.tx.alg.OrbitCalculator.parseBallisticCsvWithReference(source);
        double originalEpoch = com.tx.alg.OrbitCalculator.referenceEpochSec;
        double originalOffset = com.tx.alg.OrbitCalculator.utcOffsetSec;
        try {
            com.tx.alg.OrbitCalculator.referenceEpochSec = -12345;
            com.tx.alg.OrbitCalculator.utcOffsetSec = 28800;
            com.runisys.orbit.spacevisibility.application.ballistic.BallisticTrajectoryService.ParsedTrajectory actual =
                    parser.parse(reference.path("serverPath").asText());
            assertEquals(reference.path("serverPath").asText(), actual.sourceLocation);
            assertEquals(-12345, com.tx.alg.OrbitCalculator.referenceEpochSec);
            assertEquals(Double.doubleToLongBits(expected.referenceEpochSec), Double.doubleToLongBits(actual.parsed.referenceEpochSec));
            assertEquals(expected.points.size(), actual.parsed.points.size());
            for (int i = 0; i < expected.points.size(); i++) {
                // 逐位核验时间及文件原始十二个位置/速度分量；派生大地坐标由既有业务黄金独立核验。
                for (String name : new String[]{"tSec", "ecefX", "ecefY", "ecefZ", "eciX", "eciY", "eciZ",
                        "eciVx", "eciVy", "eciVz", "ecefVx", "ecefVy", "ecefVz"}) {
                    java.lang.reflect.Field field = com.tx.alg.OrbitCalculator.BallisticPoint.class.getField(name);
                    assertEquals(Double.doubleToLongBits(field.getDouble(expected.points.get(i))),
                            Double.doubleToLongBits(field.getDouble(actual.parsed.points.get(i))), "点 " + i + " 字段 " + name);
                }
            }
            mvc.perform(get("/api/v1/ballistic/files/{id}/preview", reference.path("fileId").asText()).param("limit", "2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.metadata.timeScale").value("UTC"))
                    .andExpect(jsonPath("$.metadata.positionUnit").value("m"))
                    .andExpect(jsonPath("$.rows.length()").value(2));
        } finally {
            com.tx.alg.OrbitCalculator.referenceEpochSec = originalEpoch;
            com.tx.alg.OrbitCalculator.utcOffsetSec = originalOffset;
        }
    }

    @Test
    void CSV带引号逗号换行空尾列预览保持原单元格() throws Exception {
        JsonNode ref = upload("quoted.csv", "name,value,last\r\n\"甲,乙\",\"第一行\n第二行\",\r\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/ballistic/files/{id}/preview", ref.path("fileId").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.rows[0][0]").value("甲,乙"))
                .andExpect(jsonPath("$.rows[0][1]").value("第一行\n第二行"))
                .andExpect(jsonPath("$.rows[0][2]").value(""));
    }

    @Test
    void Excel上传预览和原解析共用登记引用且下载原字节不变() throws Exception {
        java.util.List<String> lines = Files.readAllLines(java.nio.file.Paths.get(
                "../test-data/reference/ballistic/trajectory-0.1s-standard.csv"), StandardCharsets.UTF_8);
        for (String extension : new String[]{"xls", "xlsx", "xlsm"}) {
            byte[] bytes;
            try (org.apache.poi.ss.usermodel.Workbook workbook = extension.equals("xls")
                    ? new org.apache.poi.hssf.usermodel.HSSFWorkbook() : new org.apache.poi.xssf.usermodel.XSSFWorkbook();
                 java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                if (extension.equals("xlsm")) {
                    ((org.apache.poi.xssf.usermodel.XSSFWorkbook) workbook)
                            .setWorkbookType(org.apache.poi.xssf.usermodel.XSSFWorkbookType.XLSM);
                }
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("弹道");
                for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex);
                    String[] cells = lines.get(rowIndex).split(",");
                    for (int column = 0; column < cells.length; column++) {
                        row.createCell(column).setCellValue(cells[column]);
                    }
                }
                workbook.write(output);
                bytes = output.toByteArray();
            }
            JsonNode reference = upload("上传弹道." + extension, bytes);
            String id = reference.path("fileId").asText();
            mvc.perform(get("/api/v1/ballistic/files/{id}/preview", id).param("page", "2").param("limit", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(2))
                    .andExpect(jsonPath("$.rows[0][5]").value("00.100"))
                    .andExpect(jsonPath("$.metadata.timeScale").value("UTC"));
            assertEquals(2, parser.parse(reference.path("serverPath").asText()).parsed.points.size());
            assertArrayEquals(bytes, mvc.perform(get("/api/v1/ballistic/files/{id}/download", id))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        }
    }

    private JsonNode upload(String name, byte[] bytes) throws Exception {
        return mapper.readTree(mvc.perform(multipart("/api/v1/ballistic/files")
                .file(new MockMultipartFile("file", name, "text/csv", bytes)).param("purpose", "trajectory"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
    }
}
