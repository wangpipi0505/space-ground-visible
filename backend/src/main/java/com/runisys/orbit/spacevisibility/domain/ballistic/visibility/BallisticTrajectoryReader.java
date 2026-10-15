package com.runisys.orbit.spacevisibility.domain.ballistic.visibility;

import com.runisys.orbit.spacevisibility.domain.time.LegacyJ2000Time;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import static com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator.*;

/**
 * 弹道CSV/Excel读取实现，统一文件、输入流和字符流的解析流程。
 * 列顺序、UTC/J2000时间、单位、首有效记录历元及错误文本保持既有文件约定。
 * 带参考历元的入口不写全局状态；仅兼容的纯轨迹列表入口回填referenceEpochSec。
 */
final class BallisticTrajectoryReader {
    private BallisticTrajectoryReader() {
    }

    private static final class BallisticParseState {
        boolean epochParsed;
        double referenceEpochSec = Double.NaN;
    }

    static List<BallisticPoint> parseBallisticCsv(Path filePath) throws IOException {
        ParsedBallisticCsv parsed = parseBallisticCsvWithReference(filePath);
        referenceEpochSec = parsed.referenceEpochSec;
        return parsed.points;
    }

    static ParsedBallisticCsv parseBallisticCsvWithReference(Path filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("弹道文件路径不能为空。");
        }
        if (isExcelFile(filePath)) {
            return parseBallisticExcelWithReference(filePath);
        }
        BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
        try {
            return parseBallisticCsvWithReference(reader);
        } finally {
            reader.close();
        }
    }

    static List<BallisticPoint> parseBallisticCsv(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("弹道CSV输入流不能为空。");
        }
        ParsedBallisticCsv parsed = parseBallisticCsvWithReference(inputStream);
        referenceEpochSec = parsed.referenceEpochSec;
        return parsed.points;
    }

    static ParsedBallisticCsv parseBallisticCsvWithReference(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("弹道CSV输入流不能为空。");
        }
        return parseBallisticCsvWithReference(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    static List<BallisticPoint> parseBallisticCsv(Reader sourceReader) throws IOException {
        ParsedBallisticCsv parsed = parseBallisticCsvWithReference(sourceReader);
        referenceEpochSec = parsed.referenceEpochSec;
        return parsed.points;
    }

    static ParsedBallisticCsv parseBallisticCsvWithReference(Reader sourceReader) throws IOException {
        if (sourceReader == null) {
            throw new IllegalArgumentException("弹道CSV字符流不能为空。");
        }
        BufferedReader reader = sourceReader instanceof BufferedReader
                ? (BufferedReader) sourceReader
                : new BufferedReader(sourceReader);
        List<BallisticPoint> points = new ArrayList<BallisticPoint>();
        BallisticParseState state = new BallisticParseState();
        String rawLine;
        while ((rawLine = reader.readLine()) != null) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (isBallisticHeader(lower)) {
                continue;
            }

            BallisticPoint point = tryParseNewUtcSplitBallistic(line, state);
            if (point != null) {
                points.add(point);
                continue;
            }

            String[] parts = line.split(",");
            if (parts.length >= 18) {
                point = parseLegacyBallistic(parts, state);
                points.add(point);
            }
        }

        if (points.isEmpty()) {
            throw new IllegalArgumentException("弹道文件未解析到有效数据。");
        }
        Collections.sort(points, new Comparator<BallisticPoint>() {
            @Override
            public int compare(BallisticPoint a, BallisticPoint b) {
                return Double.compare(a.tSec, b.tSec);
            }
        });
        double sourceReference = Double.isNaN(state.referenceEpochSec) ? 0.0 : state.referenceEpochSec;
        return new ParsedBallisticCsv(points, sourceReference);
    }

    private static ParsedBallisticCsv parseBallisticExcelWithReference(Path excelPath) throws IOException {
        InputStream input = Files.newInputStream(excelPath);
        Workbook workbook = null;
        try {
            workbook = WorkbookFactory.create(input);
            if (workbook.getNumberOfSheets() <= 0) {
                throw new IllegalArgumentException("弹道Excel未包含工作表。");
            }
            Sheet sheet = workbook.getSheetAt(0);
            List<String> csvLines = new ArrayList<String>();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (Row row : sheet) {
                String line = excelRowToCsvLine(row, formatter);
                if (!line.trim().isEmpty()) {
                    csvLines.add(line);
                }
            }
            return parseBallisticCsvWithReference(new java.io.StringReader(joinLines(csvLines)));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            IOException io = new IOException("弹道Excel解析失败：" + ex.getMessage());
            io.initCause(ex);
            throw io;
        } finally {
            if (workbook != null) {
                workbook.close();
            }
            input.close();
        }
    }

    private static String excelRowToCsvLine(Row row, DataFormatter formatter) {
        int lastCell = row.getLastCellNum();
        if (lastCell <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lastCell; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            sb.append(excelCellText(cell, formatter));
        }
        return sb.toString();
    }

    private static String excelCellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        if (type == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (Math.abs(value - Math.rint(value)) < 1e-9) {
                return String.format(Locale.ROOT, "%.0f", value);
            }
            return String.format(Locale.ROOT, "%.12f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        if (type == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static boolean isExcelFile(Path filePath) {
        String name = filePath.getFileName() == null ? "" : filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".xlsm");
    }

    private static boolean isBallisticHeader(String lowerLine) {
        return lowerLine.startsWith("time")
                || lowerLine.startsWith("yyyy")
                || lowerLine.contains("utc_time")
                || lowerLine.contains("eci_x")
                || lowerLine.contains("ecef_x");
    }

    private static BallisticPoint tryParseNewUtcSplitBallistic(String line, BallisticParseState state) {
        String[] parts = line.trim().replace(',', ' ').split("\\s+");
        if (parts.length < 20 || !isIntegerText(parts[0]) || !isIntegerText(parts[1]) || !isIntegerText(parts[2])) {
            return null;
        }
        int year = Integer.parseInt(parts[0]);
        if (year < 1900 || year > 2200) {
            return null;
        }
        int month = Integer.parseInt(parts[1]);
        int day = Integer.parseInt(parts[2]);
        int hour = Integer.parseInt(parts[3]);
        int minute = Integer.parseInt(parts[4]);
        double second = parseDouble(parts[5]);
        double absoluteUtcSec = utcPartsToJ2000Sec(year, month, day, hour, minute, second, false);
        if (!state.epochParsed) {
            state.referenceEpochSec = absoluteUtcSec;
            state.epochParsed = true;
        }

        BallisticPoint point = new BallisticPoint();
        point.tSec = absoluteUtcSec - state.referenceEpochSec;
        point.ecefX = parseDouble(parts[6]);
        point.ecefY = parseDouble(parts[7]);
        point.ecefZ = parseDouble(parts[8]);
        point.eciX = parseDouble(parts[11]);
        point.eciY = parseDouble(parts[12]);
        point.eciZ = parseDouble(parts[13]);
        point.eciVx = parseDouble(parts[14]);
        point.eciVy = parseDouble(parts[15]);
        point.eciVz = parseDouble(parts[16]);
        point.ecefVx = parseDouble(parts[17]);
        point.ecefVy = parseDouble(parts[18]);
        point.ecefVz = parseDouble(parts[19]);
        double[] geo = ecefToGeodetic(new Vector3(point.ecefX, point.ecefY, point.ecefZ));
        point.latDeg = geo[0];
        point.lonDeg = geo[1];
        point.heightM = geo[2];
        point.speedMs = Math.sqrt(point.eciVx * point.eciVx + point.eciVy * point.eciVy + point.eciVz * point.eciVz);
        return point;
    }

    private static BallisticPoint parseLegacyBallistic(String[] parts, BallisticParseState state) {
        if (!state.epochParsed) {
            state.referenceEpochSec = utcToJ2000Sec(parts[1].trim());
            state.epochParsed = true;
        }
        BallisticPoint point = new BallisticPoint();
        point.tSec = parseDouble(parts[0]);
        point.eciX = parseDouble(parts[2]);
        point.eciY = parseDouble(parts[3]);
        point.eciZ = parseDouble(parts[4]);
        point.eciVx = parseDouble(parts[5]);
        point.eciVy = parseDouble(parts[6]);
        point.eciVz = parseDouble(parts[7]);
        point.ecefX = parseDouble(parts[8]);
        point.ecefY = parseDouble(parts[9]);
        point.ecefZ = parseDouble(parts[10]);
        point.ecefVx = parseDouble(parts[11]);
        point.ecefVy = parseDouble(parts[12]);
        point.ecefVz = parseDouble(parts[13]);
        point.heightM = parseDouble(parts[14]);
        point.latDeg = Math.toDegrees(parseDouble(parts[15]));
        point.lonDeg = Math.toDegrees(parseDouble(parts[16]));
        point.speedMs = parseDouble(parts[17]);
        return point;
    }

    static double parseBallisticEpochSec(Path filePath) throws IOException {
        return parseBallisticCsvWithReference(filePath).referenceEpochSec;
    }

    static double parseBallisticEpochSec(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("弹道CSV输入流不能为空。");
        }
        return parseBallisticEpochSec(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    static double parseBallisticEpochSec(Reader sourceReader) throws IOException {
        if (sourceReader == null) {
            throw new IllegalArgumentException("弹道CSV字符流不能为空。");
        }
        BufferedReader reader = sourceReader instanceof BufferedReader
                ? (BufferedReader) sourceReader
                : new BufferedReader(sourceReader);
        String rawLine;
        while ((rawLine = reader.readLine()) != null) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (isBallisticHeader(lower)) {
                continue;
            }
            String[] splitParts = line.trim().replace(',', ' ').split("\\s+");
            if (splitParts.length >= 6
                    && isIntegerText(splitParts[0])
                    && isIntegerText(splitParts[1])
                    && isIntegerText(splitParts[2])
                    && isIntegerText(splitParts[3])
                    && isIntegerText(splitParts[4])) {
                int year = Integer.parseInt(splitParts[0]);
                if (year >= 1900 && year <= 2200) {
                    int month = Integer.parseInt(splitParts[1]);
                    int day = Integer.parseInt(splitParts[2]);
                    int hour = Integer.parseInt(splitParts[3]);
                    int minute = Integer.parseInt(splitParts[4]);
                    double second = parseDouble(splitParts[5]);
                    return utcPartsToJ2000Sec(year, month, day, hour, minute, second, false);
                }
            }
            String[] parts = line.split(",");
            if (parts.length >= 2) {
                return utcToJ2000Sec(parts[1].trim());
            }
        }
        return 0.0;
    }

    private static double utcPartsToJ2000Sec(int year, int month, int day, int hour, int minute, double second,
            boolean applyConfiguredOffset) {
        return LegacyJ2000Time.utcPartsToJ2000Sec(year, month, day, hour, minute, second, applyConfiguredOffset, utcOffsetSec);
    }

    private static double parseDouble(String text) {
        return Double.parseDouble(text.trim());
    }

    private static boolean isIntegerText(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        String text = value.trim();
        int start = text.charAt(0) == '-' || text.charAt(0) == '+' ? 1 : 0;
        if (start >= text.length()) {
            return false;
        }
        for (int i = start; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
