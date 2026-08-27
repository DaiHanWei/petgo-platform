package com.tailtopia.content.larksync;

import java.util.ArrayList;
import java.util.List;

/**
 * Lark 表格行 → 待发内容（spec-lark-scheduled-posts）。纯函数，L0 可测。
 *
 * <p>列位置<b>不硬编码</b>——运营会调整表结构（2026-08-24 实测：B 列被插入「内容分类」，
 * 全部列右移导致旧硬编码把图片编号当成上传状态、整表静默跳过）。改为按<b>表头行文字</b>
 * 定位各列（{@link #mapColumns}），必需列缺失按平台级异常处理（中止本轮，绝不误读）。
 *
 * <p>表头匹配规则（2026-08-27 起）：表头文字可带括号说明，如「文案部分(最多1000字)」「备注(代码填写，人不填)」——
 * 取<b>括号前</b>的文字与约定名精确比较。「发布账号(邮箱)」是<b>输入</b>列（运营指定发布邮箱），
 * 「发布账号」（无括号说明）是<b>输出</b>列（代码回写作者昵称），二者靠括号内是否含「邮箱」区分。
 */
final class LarkRowParser {

    /** 各列的表头文字（括号前部分；与运营约定的《List of Content for Automatic Upload》表头一致）。 */
    static final String H_CODE = "内容编号";
    static final String H_TEXT = "文案部分";
    static final String H_IMAGE = "图片编号";
    static final String H_STATUS = "上传状态";
    static final String H_ACCOUNT = "发布账号";
    static final String H_NOTE = "备注";
    /** 「发布账号」括号说明里含此字样 → 输入列（邮箱）。 */
    static final String EMAIL_HINT = "邮箱";

    /**
     * 各列的 0 基下标（相对 A 列）。
     *
     * @param email 「发布账号(邮箱)」输入列；表里没有时为 -1（全部走随机虚拟账号）
     */
    record Columns(int code, int text, int image, int status, int account, int note, int email) {

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
     * @param imageCodes     图片编号<b>前缀</b>列表（按半角/全角逗号、分号拆分，已去空白；可空）。
     *                       云盘文件名 = {前缀}-{整数}.jpg，按整数升序全部取用
     * @param status         上传状态列原文（空 = 待发布）
     * @param email          发布账号(邮箱) 列原文（空 = 随机虚拟账号）
     */
    record Row(int sheetRowNumber, String contentCode, String text,
            List<String> imageCodes, String status, String email) {

        boolean pending() {
            return status == null || status.isBlank();
        }
    }

    private LarkRowParser() {
    }

    /**
     * 按表头行文字定位各列。任何必需列缺失 → {@link LarkContentClient.LarkApiException}
     * （平台级：表结构变了，中止本轮等人修，绝不按错位的列硬读）。「发布账号(邮箱)」为可选列。
     */
    static Columns mapColumns(List<String> header) {
        int email = -1;
        int account = -1;
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i);
            if (h == null || !H_ACCOUNT.equals(baseName(h))) {
                continue;
            }
            if (h.contains(EMAIL_HINT)) {
                if (email < 0) {
                    email = i;
                }
            } else if (account < 0) {
                account = i;
            }
        }
        if (account < 0) {
            throw missing(H_ACCOUNT);
        }
        return new Columns(
                indexOf(header, H_CODE),
                indexOf(header, H_TEXT),
                indexOf(header, H_IMAGE),
                indexOf(header, H_STATUS),
                account,
                indexOf(header, H_NOTE),
                email);
    }

    private static int indexOf(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i);
            if (h != null && name.equals(baseName(h))) {
                return i;
            }
        }
        throw missing(name);
    }

    private static LarkContentClient.LarkApiException missing(String name) {
        return new LarkContentClient.LarkApiException(
                "表头找不到「" + name + "」列——表结构可能又变了，请核对表格后重试");
    }

    /** 表头去掉括号说明（半角/全角括号均可）与首尾空白：「文案部分(最多1000字)」→「文案部分」。 */
    static String baseName(String header) {
        String h = header.trim();
        int cut = h.length();
        for (char c : new char[] {'(', '（'}) {
            int p = h.indexOf(c);
            if (p >= 0 && p < cut) {
                cut = p;
            }
        }
        return h.substring(0, cut).trim();
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
                    cell(cells, cols.status()),
                    cols.email() >= 0 ? cell(cells, cols.email()) : ""));
        }
        return out;
    }

    /** 图片编号拆多前缀：兼容半角/全角逗号与分号，去空白、去空段。 */
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
