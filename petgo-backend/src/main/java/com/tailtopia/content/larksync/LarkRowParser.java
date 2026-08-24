package com.tailtopia.content.larksync;

import java.util.ArrayList;
import java.util.List;

/**
 * Lark 表格行 → 待发内容（spec-lark-scheduled-posts）。纯函数，L0 可测。
 *
 * <p>列约定（与《List of Content for Automatic Upload》一致）：
 * A 序号 / B 内容编号 / C 文案部分 / D 图片编号 / E 上传状态 / F 发布账号。
 * 数据从表格第 2 行起——{@code rows} 的 index 0 对应表格行号 2。
 */
final class LarkRowParser {

    /**
     * @param sheetRowNumber 表格实际行号（回写用）
     * @param contentCode    内容编号（如 DR260823001）
     * @param text           文案（可空——纯图帖）
     * @param imageCodes     图片编号列表（D 列按半角/全角逗号、分号拆分，已去空白；可空）
     * @param status         上传状态列原文（空 = 待发布）
     */
    record Row(int sheetRowNumber, String contentCode, String text,
            List<String> imageCodes, String status) {

        boolean pending() {
            return status == null || status.isBlank();
        }
    }

    private LarkRowParser() {
    }

    /** 解析数据区原始行：无内容编号的行（空行/垫行）直接丢弃。 */
    static List<Row> parse(List<List<String>> rows) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            String code = cell(cells, 1);
            if (code.isBlank()) {
                continue;
            }
            out.add(new Row(
                    i + 2,
                    code,
                    cell(cells, 2),
                    splitImageCodes(cell(cells, 3)),
                    cell(cells, 4)));
        }
        return out;
    }

    /** D 列拆多图：兼容半角/全角逗号与分号，去空白、去空段。 */
    static List<String> splitImageCodes(String raw) {
        List<String> codes = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return codes;
        }
        for (String part : raw.split("[,，;；]")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                codes.add(p);
            }
        }
        return codes;
    }

    /** 越界/null 安全取格（Lark 短行只返回有值的前几列）。 */
    private static String cell(List<String> cells, int idx) {
        if (cells == null || idx >= cells.size() || cells.get(idx) == null) {
            return "";
        }
        return cells.get(idx).trim();
    }
}
