package com.tailtopia.content.larksync;

import java.util.ArrayList;
import java.util.List;

/**
 * Lark 表格行 → 待发内容（spec-lark-scheduled-posts）。纯函数，L0 可测。
 *
 * <p>列位置<b>不硬编码</b>——运营会调整表结构（2026-08-24 实测：B 列被插入「内容分类」，
 * 全部列右移导致旧硬编码把图片编号当成上传状态、整表静默跳过）。改为按<b>表头行文字</b>
 * 定位各列（{@link #mapColumns}），必需列缺失按平台级异常处理（中止本轮，绝不误读）。
 */
final class LarkRowParser {

    /** 必需列的表头文字（与运营约定的《List of Content for Automatic Upload》表头一致）。 */
    static final String H_CODE = "内容编号";
    static final String H_TEXT = "文案部分";
    static final String H_IMAGE = "图片编号";
    static final String H_STATUS = "上传状态";
    static final String H_ACCOUNT = "发布账号";

    /** 各列的 0 基下标（相对 A 列）。 */
    record Columns(int code, int text, int image, int status, int account) {

        /** 0 基下标 → 表格列字母（0=A，25=Z，26=AA…）。 */
        static String letter(int zeroBased) {
            StringBuilder sb = new StringBuilder();
            int n = zeroBased;
            do {
                sb.insert(0, (char) ('A' + n % 26));
                n = n / 26 - 1;
            } while (n >= 0);
            return sb.toString();
        }
    }

    /**
     * @param sheetRowNumber 表格实际行号（回写用）
     * @param contentCode    内容编号（如 DR260823001）
     * @param text           文案（可空——纯图帖）
     * @param imageCodes     图片编号列表（按半角/全角逗号、分号拆分，已去空白；可空）
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

    /**
     * 按表头行文字定位各列。任何必需列缺失 → {@link LarkContentClient.LarkApiException}
     * （平台级：表结构变了，中止本轮等人修，绝不按错位的列硬读）。
     */
    static Columns mapColumns(List<String> header) {
        return new Columns(
                indexOf(header, H_CODE),
                indexOf(header, H_TEXT),
                indexOf(header, H_IMAGE),
                indexOf(header, H_STATUS),
                indexOf(header, H_ACCOUNT));
    }

    private static int indexOf(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i);
            if (h != null && name.equals(h.trim())) {
                return i;
            }
        }
        throw new LarkContentClient.LarkApiException(
                "表头找不到「" + name + "」列——表结构可能又变了，请核对表格后重试");
    }

    /** 解析数据区原始行（{@code rows} 的 index 0 对应表格行号 2）：无内容编号的行直接丢弃。 */
    static List<Row> parse(List<List<String>> rows, Columns cols) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            String code = cell(cells, cols.code());
            if (code.isBlank()) {
                continue;
            }
            out.add(new Row(
                    i + 2,
                    code,
                    cell(cells, cols.text()),
                    splitImageCodes(cell(cells, cols.image())),
                    cell(cells, cols.status())));
        }
        return out;
    }

    /** 图片编号拆多图：兼容半角/全角逗号与分号，去空白、去空段。 */
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
