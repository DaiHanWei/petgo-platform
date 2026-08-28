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

    /** 2026-08-27 实测表头（运营加了「发布账号(邮箱)」输入列与「备注」列，表头带括号说明）。 */
    private static final List<String> HEADER = List.of(
            "序号", "内容分类", "内容编号", "文案部分(最多1000字)", "图片编号",
            "发布账号(邮箱)，不填默认虚拟账号随机", "上传状态", "发布账号", "备注(代码填写，人不填)");
    private static final LarkRowParser.Columns COLS = LarkRowParser.mapColumns(HEADER);

    @Test
    void 表头映射_按文字定位不吃列序() {
        assertEquals(2, COLS.code());
        assertEquals(3, COLS.text());
        assertEquals(4, COLS.image());
        assertEquals(5, COLS.email());
        assertEquals(6, COLS.status());
        assertEquals(7, COLS.account());
        assertEquals(8, COLS.note());
        assertEquals(1, COLS.category());
        // 无「发布账号(邮箱)」列的旧结构同样能映射（email=-1 全走随机）——列位置变化由表头驱动。
        LarkRowParser.Columns old = LarkRowParser.mapColumns(List.of(
                "序号", "内容编号", "文案部分", "图片编号", "上传状态", "发布账号", "备注"));
        assertEquals(1, old.code());
        assertEquals(4, old.status());
        assertEquals(5, old.account());
        assertEquals(-1, old.email());
        assertEquals(-1, old.category());
    }

    @Test
    void 表头括号说明_取括号前文字匹配() {
        assertEquals("文案部分", LarkRowParser.baseName("文案部分(最多1000字)"));
        assertEquals("备注", LarkRowParser.baseName(" 备注（代码填写） "));
        assertEquals("发布账号", LarkRowParser.baseName("发布账号"));
    }

    @Test
    void 表头缺备注列_抛平台级异常() {
        assertThrows(LarkContentClient.LarkApiException.class,
                () -> LarkRowParser.mapColumns(List.of(
                        "序号", "内容编号", "文案部分", "图片编号", "上传状态", "发布账号")));
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
                List.of("1", "Moment", "DR260823001", "Ketemu si oren", "DR260823001", "", "", "", "")),
                COLS);
        assertEquals(1, rows.size());
        LarkRowParser.Row r = rows.get(0);
        assertEquals(2, r.sheetRowNumber()); // 数据区第一条 = 表格第 2 行
        assertEquals("DR260823001", r.contentCode());
        assertEquals("Ketemu si oren", r.text());
        assertEquals(List.of("DR260823001"), r.imageCodes());
        assertEquals("", r.email());
        assertEquals("Moment", r.category());
        assertTrue(r.pending());
    }

    @Test
    void 无内容编号的空行与垫行_直接丢弃() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(Arrays.asList(
                List.of("", "", "", "", "", "", "", "", ""),
                List.of(),
                Arrays.asList("3", "Moment", "DR260823003", "x", "DR260823003")), COLS);
        assertEquals(1, rows.size());
        assertEquals("DR260823003", rows.get(0).contentCode());
        assertEquals(4, rows.get(0).sheetRowNumber()); // index 2 → 表格第 4 行
    }

    @Test
    void 短行_缺状态账号列按空处理仍算待发() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(List.of(
                Arrays.asList("1", "Moment", "DR260823001", "文案", "DR260823001")), COLS);
        assertTrue(rows.get(0).pending());
        assertEquals("", rows.get(0).email());
    }

    @Test
    void 邮箱列有值_解析进Row() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(List.of(
                Arrays.asList("1", "Moment", "DR260823001", "文案", "DR260823001",
                        " someone@example.com ")), COLS);
        assertEquals("someone@example.com", rows.get(0).email());
    }

    @Test
    void 状态列非空_不算待发() {
        List<LarkRowParser.Row> rows = LarkRowParser.parse(List.of(
                List.of("1", "Moment", "DR260823001", "文案", "DR260823001", "",
                        "已发布 2026-08-24 12:07 WIB", "Si Oyen", "")), COLS);
        assertFalse(rows.get(0).pending());
    }

    @Test
    void 多图_兼容半角全角逗号分号并去空白() {
        assertEquals(List.of("A1", "A2", "A3", "A4"),
                LarkRowParser.splitImageCodes("A1, A2，A3；A4"));
        assertTrue(LarkRowParser.splitImageCodes("  ").isEmpty());
        assertTrue(LarkRowParser.splitImageCodes(null).isEmpty());
    }
}
