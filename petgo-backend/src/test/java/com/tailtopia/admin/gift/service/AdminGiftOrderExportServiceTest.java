package com.tailtopia.admin.gift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.gift.dto.GiftOrderAdminRow;
import com.tailtopia.admin.gift.service.AdminGiftOrderQueryService.Filter;
import com.tailtopia.admin.gift.service.AdminGiftOrderQueryService.StatusGroup;
import com.tailtopia.support.TestMessages;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;

/** L0：送礼记录导出 xlsx（V1.4.0 ③ gift Story 3.2 · AC4 / AC3.1 / AC6）。真实三语文案、mock 查询与审计。 */
class AdminGiftOrderExportServiceTest {

    private final AdminGiftOrderQueryService query = mock(AdminGiftOrderQueryService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminGiftOrderExportService svc = new AdminGiftOrderExportService(query, audit, TestMessages.real());

    private static final Filter ALL = new Filter(null, null, null, null, null, null);
    private static final Filter ANOMALY = new Filter(7L, null, "QRIS", StatusGroup.UNDELIVERED,
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 17));

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static GiftOrderAdminRow delivered() {
        return new GiftOrderAdminRow(Instant.parse("2026-10-17T06:20:00Z"), 10233L, "Andi", false, 10241L, "Rina",
                false, "Mochi", false, "Salmon", "Ikan salmon", 5000L, 2, 10000L, "QRIS", "DELIVERED", null, 0, null,
                "pi8d0aZ9kLmN3a3b");
    }

    private static GiftOrderAdminRow anomaly(String status, String reason, int attempts, Instant refundedAt) {
        return new GiftOrderAdminRow(Instant.parse("2026-10-16T13:10:00Z"), 10233L, null, true, 10301L, "Dewi",
                false, "Boba", true, "Tennis ball", "Bola tenis", 10000L, 2, 20000L, "QRIS", status, reason, attempts,
                refundedAt, "piXXXXXXXXXXXXXX");
    }

    private static Sheet sheet(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes)).getSheetAt(0);
    }

    private static List<String> headers(Sheet s) {
        List<String> out = new ArrayList<>();
        Row h = s.getRow(0);
        for (int i = 0; i < h.getLastCellNum(); i++) {
            out.add(h.getCell(i).getStringCellValue());
        }
        return out;
    }

    @Test
    void headersFollowTheUiLanguageAndNumbersAreNumericCells() throws Exception {
        when(query.searchAll(any(), anyInt())).thenReturn(List.of(delivered()));

        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        Sheet zh = sheet(svc.exportXlsx(1L, ALL));
        assertThat(headers(zh)).containsExactly("时间（WIB）", "送礼人 ID", "送礼人", "收礼宠物", "宠物主人 ID", "宠物主人",
                "礼物", "数量", "单价", "总价", "渠道", "状态", "支付意图");
        Row r = zh.getRow(1);
        assertThat(r.getCell(0).getStringCellValue()).isEqualTo("2026-10-17 13:20 WIB");
        assertThat(r.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(r.getCell(1).getNumericCellValue()).isEqualTo(10233d);
        assertThat(r.getCell(2).getStringCellValue()).isEqualTo("Andi");
        assertThat(r.getCell(6).getStringCellValue()).as("中文界面取 EN 快照名").isEqualTo("Salmon");
        for (int c : new int[] {7, 8, 9}) {
            assertThat(r.getCell(c).getCellType()).as("数量 / 单价 / 总价是数字单元格").isEqualTo(CellType.NUMERIC);
        }
        assertThat(r.getCell(9).getNumericCellValue()).isEqualTo(10000d);
        assertThat(r.getCell(11).getStringCellValue()).isEqualTo("已送达");
        assertThat(r.getCell(12).getStringCellValue()).as("导出给全值，便于回查").isEqualTo("pi8d0aZ9kLmN3a3b");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("id"));
        Sheet id = sheet(svc.exportXlsx(1L, ALL));
        assertThat(headers(id).get(0)).isEqualTo("Waktu (WIB)");
        assertThat(id.getRow(1).getCell(6).getStringCellValue()).as("印尼文界面取 ID 快照名").isEqualTo("Ikan salmon");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(headers(sheet(svc.exportXlsx(1L, ALL))).get(1)).isEqualTo("Sender ID");
    }

    @Test
    void over5000RowsAddsTruncationNoteInFirstColumnAndAuditsTruncated() throws Exception {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        when(query.searchAll(any(), eq(AdminGiftOrderExportService.EXPORT_MAX_ROWS + 1)))
                .thenReturn(Collections.nCopies(5001, delivered()));

        Sheet s = sheet(svc.exportXlsx(42L, ALL));

        assertThat(s.getLastRowNum()).as("表头 + 5000 行 + 截断说明").isEqualTo(5001);
        assertThat(s.getRow(5001).getCell(0).getStringCellValue())
                .isEqualTo("仅导出前 5000 行，请缩小筛选范围后再导出");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(42L), eq(AuditActions.GIFT_ORDER_LIST_EXPORTED), eq("gift_order"), eq("-"),
                detail.capture());
        assertThat(detail.getValue()).contains("rows=5000").contains("truncated=true");
    }

    @Test
    void anomalyViewSwapsIntentForReasonAndRefundColumns() throws Exception {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        when(query.searchAll(any(), anyInt())).thenReturn(List.of(
                anomaly("UNDELIVERED_REFUNDED", "PET_DELETED", 1, Instant.parse("2026-10-16T13:12:00Z")),
                anomaly("UNDELIVERED_REFUND_PENDING", "SENDER_BLOCKED", 2, null),
                anomaly("REFUND_MANUAL", "SOMETHING_NEW", 5, null)));

        Sheet s = sheet(svc.exportXlsx(9L, ANOMALY));

        List<String> h = headers(s);
        assertThat(h).doesNotContain("支付意图").endsWith("原因", "PawCoin 退回");
        assertThat(h).hasSize(14);
        Row refunded = s.getRow(1);
        assertThat(refunded.getCell(2).getStringCellValue()).as("注销送礼人不显示快照昵称").isEqualTo("（已注销）#10233");
        assertThat(refunded.getCell(3).getStringCellValue()).isEqualTo("（已删除）Boba");
        assertThat(refunded.getCell(11).getStringCellValue()).isEqualTo("已付款未送达");
        assertThat(refunded.getCell(12).getStringCellValue()).isEqualTo("宠物已删除");
        assertThat(refunded.getCell(13).getStringCellValue()).isEqualTo("已退回 Rp20,000 · 2026-10-16 20:12 WIB");
        assertThat(s.getRow(2).getCell(12).getStringCellValue()).isEqualTo("送礼人已拉黑主人");
        assertThat(s.getRow(2).getCell(13).getStringCellValue()).isEqualTo("退回失败 · 待重试 · 已重试 2 次");
        assertThat(s.getRow(3).getCell(12).getStringCellValue()).as("未知原因原样显示代码").isEqualTo("SOMETHING_NEW");
        assertThat(s.getRow(3).getCell(13).getStringCellValue()).isEqualTo("退回失败 · 需人工 · 已重试 5 次");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(anyLong(), anyString(), anyString(), anyString(), detail.capture());
        assertThat(detail.getValue()).contains("rows=3").contains("truncated=false").contains("senderId=7")
                .contains("ownerId=null").contains("channel=QRIS").contains("status=UNDELIVERED")
                .contains("from=2026-10-01").contains("to=2026-10-17");
    }
}
