package com.runisys.orbit.spacevisibility.application.ballistic;

import com.runisys.orbit.spacevisibility.api.ballistic.BallisticFileModels.Preview;
import com.runisys.orbit.spacevisibility.infrastructure.files.BallisticFileConfiguration;
import com.runisys.orbit.spacevisibility.infrastructure.files.BallisticFileStorage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** 文件预览编排：受限分页保留原单元格文本，数值解析仍由原 OrbitCalculator 完成。 */
@Service
public class BallisticFileService {
    private final BallisticFileStorage storage;
    private final BallisticFileConfiguration config;

    public BallisticFileService(BallisticFileStorage storage, BallisticFileConfiguration config) {
        this.storage = storage;
        this.config = config;
    }

    /**
     * 返回第一个工作表或 CSV 的首个非空记录作表头。CSV 支持引号、逗号、换行和尾部空列；
     * 只保存当前页数据，totalRows 遍历得到，避免把整个文本结果存入预览响应。
     */
    public Preview preview(String id, int page, int limit) throws IOException {
        if (page < 1 || limit < 1 || limit > config.previewMaxRows) {
            throw new IllegalArgumentException("page 必须大于零，limit 必须在 1 到 " + config.previewMaxRows + " 之间");
        }
        Preview preview = new Preview();
        preview.fileId = id;
        preview.page = page;
        preview.limit = limit;
        preview.header = new ArrayList<String>();
        preview.rows = new ArrayList<List<String>>();
        preview.metadata = new LinkedHashMap<String, String>();
        try (BallisticFileStorage.Lease lease = storage.acquire(id)) {
            String name = lease.reference.originalName.toLowerCase(Locale.ROOT);
            if (name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
                try (InputStream input = Files.newInputStream(lease.path);
                     Workbook workbook = WorkbookFactory.create(input)) {
                    if (workbook.getNumberOfSheets() == 0) {
                        throw new IllegalArgumentException("Excel 未包含工作表");
                    }
                    DataFormatter formatter = new DataFormatter(Locale.ROOT);
                    for (Row row : workbook.getSheetAt(0)) {
                        List<String> cells = new ArrayList<String>();
                        for (int i = 0; i < row.getLastCellNum(); i++) {
                            cells.add(formatter.formatCellValue(row.getCell(i)));
                        }
                        accept(preview, cells);
                    }
                }
            } else if (name.endsWith(".csv") || name.endsWith(".txt")) {
                try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(lease.path, StandardCharsets.UTF_8), 1)) {
                    List<String> row;
                    while ((row = readCsvRecord(reader)) != null) {
                        accept(preview, row);
                    }
                }
            } else {
                throw new IllegalArgumentException("预览仅支持 CSV、TXT、XLS、XLSX 和 XLSM");
            }
        }
        describeKnownHeader(preview);
        return preview;
    }

    private static void accept(Preview preview, List<String> cells) {
        if (cells.isEmpty() || (cells.size() == 1 && cells.get(0).trim().isEmpty())) {
            return;
        }
        if (preview.header.isEmpty()) {
            preview.header = cells;
            return;
        }
        long start = ((long) preview.page - 1) * preview.limit;
        if (preview.totalRows >= start && preview.totalRows < start + preview.limit) {
            preview.rows.add(cells);
        }
        preview.totalRows++;
    }

    /** 标准 UTC 分列 20 列才声明原解析约定；一般结果表仅呈现原表头，不猜单位。 */
    private static void describeKnownHeader(Preview preview) {
        List<String> expected = Arrays.asList("YYYY", "MM", "dd", "HH", "mm", "ss.SSS", "ECEF_X(m)",
                "ECEF_Y(m)", "ECEF_Z(m)", "energy", "dt(ms)", "ECI_X(m)", "ECI_Y(m)", "ECI_Z(m)",
                "ECI_Vx(m/s)", "ECI_Vy(m/s)", "ECI_Vz(m/s)", "ECEF_Vx(m/s)", "ECEF_Vy(m/s)", "ECEF_Vz(m/s)");
        if (preview.header.equals(expected)) {
            preview.metadata.put("timeScale", "UTC");
            preview.metadata.put("positionUnit", "m");
            preview.metadata.put("velocityUnit", "m/s");
            preview.metadata.put("frames", "ECI,ECEF");
            preview.metadata.put("dtUnit", "ms");
        }
    }

    /** 小型 CSV 记录读取器只用于展示，绝不改变旧弹道解析器的宽容规则和数值转换。 */
    private static List<String> readCsvRecord(PushbackReader reader) throws IOException {
        List<String> cells = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean seen = false;
        int value;
        while ((value = reader.read()) != -1) {
            seen = true;
            char current = (char) value;
            if (current == '"') {
                if (quoted) {
                    int next = reader.read();
                    if (next == '"') {
                        cell.append('"');
                    } else {
                        quoted = false;
                        if (next != -1) {
                            reader.unread(next);
                        }
                    }
                } else if (cell.length() == 0) {
                    quoted = true;
                } else {
                    cell.append(current);
                }
            } else if (!quoted && current == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (!quoted && (current == '\n' || current == '\r')) {
                if (current == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.unread(next);
                    }
                }
                cells.add(cell.toString());
                return cells;
            } else {
                cell.append(current);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV 引号未闭合");
        }
        if (!seen && cells.isEmpty()) {
            return null;
        }
        cells.add(cell.toString());
        return cells;
    }
}
