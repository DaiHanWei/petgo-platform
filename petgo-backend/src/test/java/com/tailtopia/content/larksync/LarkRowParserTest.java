package com.tailtopia.content.larksync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 行解析纯函数（spec-lark-scheduled-posts I/O 矩阵：空行/短行/多图/状态列）。 */
class LarkRowParserTest {

    @Test
    void 正常行_解析出编号文案图片并保留表格行号() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(List.of(
                List.of("1", "DR260823001", "Ketemu si oren", "DR260823001-1", "", "")));
        assertEquals(1, rows.size());
        LarkRowParser.Row r = rows.get(0);
        assertEquals(2, r.sheetRowNumber()); // 数据区第一条 = 表格第 2 行
        assertEquals("DR260823001", r.contentCode());
        assertEquals("Ketemu si oren", r.text());
        assertEquals(List.of("DR260823001-1"), r.imageCodes());
        assertTrue(r.pending());
    }

    @Test
    void 无内容编号的空行与垫行_直接丢弃() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(Arrays.asList(
                List.of("", "", "", "", "", ""),
                List.of(),
                Arrays.asList("3", "DR260823003", "x", "DR260823003-1")));
        assertEquals(1, rows.size());
        assertEquals("DR260823003", rows.get(0).contentCode());
        assertEquals(4, rows.get(0).sheetRowNumber()); // index 2 → 表格第 4 行
    }

    @Test
    void 短行_缺EF列按空处理仍算待发() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(List.of(
                Arrays.asList("1", "DR260823001", "文案", "DR260823001-1")));
        assertTrue(rows.get(0).pending());
    }

    @Test
    void 状态列非空_不算待发() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(List.of(
                List.of("1", "DR260823001", "文案", "DR260823001-1", "已发布 2026-08-24 12:07 WIB", "Si Oyen")));
        assertFalse(rows.get(0).pending());
    }

    @Test
    void 多图_兼容半角全角逗号分号并去空白() {
        assertEquals(List.of("A-1", "A-2", "A-3", "A-4"),
                LarkRowParser.splitImageCodes("A-1, A-2，A-3；A-4"));
        assertTrue(LarkRowParser.splitImageCodes("  ").isEmpty());
        assertTrue(LarkRowParser.splitImageCodes(null).isEmpty());
    }
}
