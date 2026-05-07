package com.example.myaiproject.tool.msc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MscTrackingExcelWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesChineseHeadersAndDataRows() throws Exception {
        Path excelFile = tempDir.resolve("output.xlsx");
        MscTrackingResult result = MscTrackingResult.of(
                "177A****05V",
                MscTrackingQueryType.BOOKING,
                MscTrackingStatus.NO_RESULT,
                "未找到与该订舱号匹配的结果。",
                MscTrackingParsedFields.empty(),
                "/tmp/screenshot.png",
                "MSC page returned no visible result for this tracking number.",
                ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        new MscTrackingExcelWriter().write(excelFile, List.of(result));

        try (var workbook = WorkbookFactory.create(excelFile.toFile())) {
            var sheet = workbook.getSheet("MSC查询结果");
            Row header = sheet.getRow(0);
            Row data = sheet.getRow(1);

            assertEquals("查询号码", header.getCell(0).getStringCellValue());
            assertEquals("查询类型", header.getCell(1).getStringCellValue());
            assertEquals("页面可见文本", header.getCell(4).getStringCellValue());
            assertEquals("177A****05V", data.getCell(0).getStringCellValue());
            assertEquals("订舱号", data.getCell(1).getStringCellValue());
            assertEquals("NO_RESULT", data.getCell(3).getStringCellValue());
            assertEquals("未找到与该订舱号匹配的结果。", data.getCell(4).getStringCellValue());
        }
    }
}
