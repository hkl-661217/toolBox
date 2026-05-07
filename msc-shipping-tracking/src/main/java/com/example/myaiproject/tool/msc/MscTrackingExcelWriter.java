package com.example.myaiproject.tool.msc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class MscTrackingExcelWriter {
    private static final String[] HEADERS = {
            "查询号码",
            "查询类型",
            "是否成功",
            "状态",
            "页面可见文本",
            "当前状态",
            "ETA",
            "最新轨迹节点",
            "截图路径",
            "失败原因",
            "查询时间"
    };

    public void write(Path outputFile, List<MscTrackingResult> results) throws IOException {
        Files.createDirectories(outputFile.toAbsolutePath().getParent());
        try (Workbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("MSC查询结果");
            sheet.createFreezePane(0, 1);

            CellStyle headerStyle = headerStyle(workbook);
            CellStyle textStyle = textStyle(workbook);
            CellStyle wrappedTextStyle = wrappedTextStyle(workbook);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            for (int rowIndex = 0; rowIndex < results.size(); rowIndex++) {
                MscTrackingResult result = results.get(rowIndex);
                Row row = sheet.createRow(rowIndex + 1);
                row.setHeightInPoints(72);
                writeCell(row, 0, result.trackingNoMasked(), textStyle);
                writeCell(row, 1, result.queryType(), textStyle);
                writeCell(row, 2, result.success() ? "是" : "否", textStyle);
                writeCell(row, 3, result.status().name(), textStyle);
                writeCell(row, 4, result.rawText(), wrappedTextStyle);
                writeCell(row, 5, result.parsedCurrentStatus(), textStyle);
                writeCell(row, 6, result.parsedEta(), textStyle);
                writeCell(row, 7, result.parsedLatestNode(), wrappedTextStyle);
                writeCell(row, 8, result.screenshotPath(), wrappedTextStyle);
                writeCell(row, 9, result.errorReason(), wrappedTextStyle);
                writeCell(row, 10, result.queriedAt(), textStyle);
            }

            int[] widths = {18, 18, 12, 16, 72, 20, 18, 36, 64, 44, 28};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }

            try (OutputStream outputStream = Files.newOutputStream(outputFile)) {
                workbook.write(outputStream);
            }
        }
    }

    private static void writeCell(Row row, int columnIndex, String value, CellStyle style) {
        Cell cell = row.createCell(columnIndex);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = borderedStyle(workbook);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GOLD.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static CellStyle textStyle(Workbook workbook) {
        CellStyle style = borderedStyle(workbook);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }

    private static CellStyle wrappedTextStyle(Workbook workbook) {
        CellStyle style = textStyle(workbook);
        style.setWrapText(true);
        return style;
    }

    private static CellStyle borderedStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }
}
