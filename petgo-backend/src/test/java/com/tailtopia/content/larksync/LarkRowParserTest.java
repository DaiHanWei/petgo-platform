package com.tailtopia.content.larksync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 行解析纯函数（spec-lark-scheduled-posts I/O 矩阵：表头映射/空行/短行/多图/状态列）。 */
class LarkRowParserTest {

    /** 2026-08-24 实测表头（运营在 B 列插入了「内容分类」）。 */
    private static final List<String> HEADER = List.of(
            "序号", "内容分类", "内容编号", "文案部分", "图片编号", "上传状态", "发布账号");
    private static final LarkRowParser.Columns COLS = LarkRowParser.mapColumns(HEADER);

    @Test
    void 表头映射_按文字定位不吃列序() {
        assertEquals(2, COLS.code());
        assertEquals(3, COLS.text());
        assertEquals(4, COLS.image());
        assertEquals(5, COLS.status());
        assertEquals(6, COLS.account());
        // 旧结构（无内容分类列）同样能映射——列位置变化由表头驱动。
        LarkRowParser.Columns old = LarkRowParser.mapColumns(List.of(
                "序号", "内容编号", "文案部分", "图片编号", "上传状态", "发布账号"));
        assertEquals(1, old.code());
        assertEquals(4, old.status());
    }

    @Test
    void 表头缺必需列_抛平台级异常() {
        assertThrows(LarkContentClient.LarkApiException.class,
                () -> LarkRowParser.mapColumns(List.of("序号", "内容分类", "文案部分")));
    }

    @Test
    void 列字母换算() {
        assertEquals("A", LarkRowParser.Columns.letter(0));
        assertEquals("F", LarkRowParser.Columns.letter(5));
        assertEquals("Z", LarkRowParser.Columns.letter(25));
        assertEquals("AA", LarkRowParser.Columns.letter(26));
    }

    @Test
    void 正常行_解析出编号文案图片并保留表格行号() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(List.of(
                List.of("1", "Moment", "DR260823001", "Ketemu si oren", "DR260823001-1", "", "")),
                COLS);
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
                List.of("", "", "", "", "", "", ""),
                List.of(),
                Arrays.asList("3", "Moment", "DR260823003", "x", "DR260823003-1")), COLS);
        assertEquals(1, rows.size());
        assertEquals("DR260823003", rows.get(0).contentCode());
        assertEquals(4, rows.get(0).sheetRowNumber()); // index 2 → 表格第 4 行
    }

    @Test
    void 短行_缺状态账号列按空处理仍算待发() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(List.of(
                Arrays.asList("1", "Moment", "DR260823001", "文案", "DR260823001-1")), COLS);
        assertTrue(rows.get(0).pending());
    }

    @Test
    void 状态列非空_不算待发() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(List.of(
                List.of("1", "Moment", "DR260823001", "文案", "DR260823001-1",
                        "已发布 2026-08-24 12:07 WIB", "Si Oyen")), COLS);
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
