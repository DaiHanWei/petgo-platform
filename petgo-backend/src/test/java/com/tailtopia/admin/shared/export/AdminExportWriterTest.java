package com.tailtopia.admin.shared.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** L0：导出统一出口（Story 2.3a AC7，规则 12）——逗号 / 引号 / 换行 / 公式注入 / 中文；xlsx 数字列为数字单元格。 */
class AdminExportWriterTest {

    @Test
    void csvEscapesPerRfc4180AndBlocksFormulaInjection() {
        String csv = AdminExportWriter.csv(List.of("名称", "备注", "金额"), List.of(
                Arrays.asList("a,b", "say \"hi\"", 12),
                Arrays.asList("多行\n第二行", "=SUM(A1:A9)", null),
                Arrays.asList("+62 812", "@mention", -5),
                Arrays.asList("-1", "\tlead", "+3.5")));
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo("名称,备注,金额");
        assertThat(lines[1]).isEqualTo("\"a,b\",\"say \"\"hi\"\"\",12");
        assertThat(lines[2]).isEqualTo("\"多行\n第二行\",'=SUM(A1:A9),");
        assertThat(lines[3]).isEqualTo("'+62 812,'@mention,-5");
        assertThat(lines[4]).isEqualTo("-1,\"\tlead\",+3.5"); // 纯数字文本不加 '；前导 Tab 加引号
        assertThat(csv).endsWith("\r\n");
    }

    @Test
    void xlsxUsesNumericCellsForNumbers() throws Exception {
        byte[] bytes = AdminExportWriter.xlsx("payments", List.of("id", "金额", "备注"),
                List.of(Arrays.asList(7L, 1234.5, "中文"), Arrays.asList(8L, null, "=x")));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = wb.getSheet("payments");
            assertThat(s.getRow(0).getCell(1).getStringCellValue()).isEqualTo("金额");
            assertThat(s.getRow(1).getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(s.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(1234.5);
            assertThat(s.getRow(1).getCell(2).getStringCellValue()).isEqualTo("中文");
            assertThat(s.getRow(2).getCell(1).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(s.getRow(2).getCell(2).getStringCellValue()).isEqualTo("=x"); // xlsx 字符串单元格不会被当公式
        }
    }
}
