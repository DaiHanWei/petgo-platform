package com.tailtopia.admin.virtual.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class AdminSeedBatchControllerTest {

    @Test
    void templateReturnsReadableExcelExample() throws Exception {
        var controller = new AdminSeedBatchController(null);

        var response = controller.template();

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("seed-batch-template.xlsx");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(response.getBody()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("正文");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isNotBlank();
        }
    }
}
