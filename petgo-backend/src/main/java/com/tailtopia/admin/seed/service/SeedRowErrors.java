package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.dto.RowError;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code seed_batch_rows.error_message}（varchar 500）的存取格式（bug 20260924-565，<b>不加迁移</b>）。
 *
 * <h2>格式</h2>
 * 每条错误一行，多条用 {@code \n} 分隔；一条是 {@code i18n:<key>|<arg1>|<arg2>…}。
 * 实参里的 {@code \}、{@code |}、换行转义为 {@code \\}、{@code \|}、{@code \n}（昵称、文件名里什么都可能有）。
 *
 * <p>🛡 <b>没有 {@code i18n:} 前缀的一律当历史原文</b>：库里已经存着的中文句子（以及测试 / 运维手写的说明）
 * 原样显示，不做任何解析 —— 这是本格式能不加迁移上线的前提。
 *
 * <h2>长度</h2>
 * 多条拼起来超过 500 时<b>按整条截断</b>（丢掉后面的整条，不留半条）：半条编码解不出来，
 * 会以一段乱码的形态出现在运营面前。只有第一条本身就超长时才硬截（实体层也会兜一次）。
 */
public final class SeedRowErrors {

    public static final String PREFIX = "i18n:";
    public static final int MAX_LEN = 500;

    private SeedRowErrors() {
    }

    /** 一条文案码错误的存储串。 */
    public static String encode(String key, Object... args) {
        return encode(List.of(new RowError(key, args)));
    }

    /** 多条错误 → 存储串；空列表 → {@code null}（= 清空错误）。 */
    public static String encode(List<RowError> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (RowError e : errors) {
            String one = encodeOne(e);
            int needed = out.isEmpty() ? one.length() : one.length() + 1;
            if (out.length() + needed > MAX_LEN) {
                if (out.isEmpty()) {
                    // 第一条就超长：只能硬截，至少把它的开头（文案码）留下。
                    return one.substring(0, MAX_LEN);
                }
                break;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(one);
        }
        return out.toString();
    }

    private static String encodeOne(RowError e) {
        if (e.isRaw()) {
            // 原文不参与转义；换行压成空格，免得被读回时拆成两条。
            return e.rawText().replace("\r", " ").replace("\n", " ");
        }
        StringBuilder sb = new StringBuilder(PREFIX).append(e.key());
        for (Object a : e.args()) {
            sb.append('|').append(escape(a == null ? "" : String.valueOf(a)));
        }
        return sb.toString();
    }

    /** 存储串 → 错误列表；{@code null}/空白 → 空列表。 */
    public static List<RowError> decode(String stored) {
        List<RowError> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        if (!stored.startsWith(PREFIX) && !stored.contains("\n" + PREFIX)) {
            // 🛡 历史中文（可能带「；」拼接的多条）：整段原样当一条。
            out.add(RowError.raw(stored));
            return out;
        }
        for (String line : stored.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (!line.startsWith(PREFIX)) {
                out.add(RowError.raw(line));
                continue;
            }
            List<String> parts = splitUnescaped(line.substring(PREFIX.length()));
            String key = parts.get(0);
            out.add(new RowError(key, parts.subList(1, parts.size()).toArray()));
        }
        return out;
    }

    /**
     * 发布失败的异常 → 存储串。挂了文案码的 {@link AppException} 存码 + 实参（按后台语言渲染）；
     * 其余存原文；原文也没有就存兜底码。
     */
    public static String fromException(RuntimeException e, String fallbackKey) {
        if (e instanceof AppException ae && ae.getMessageCode() != null) {
            Object[] args = ae.getMessageArgs() == null ? new Object[0] : ae.getMessageArgs();
            return encode(ae.getMessageCode(), args);
        }
        String m = e == null ? null : e.getMessage();
        return m == null || m.isBlank() ? encode(fallbackKey) : encode(List.of(RowError.raw(m)));
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '|' -> sb.append("\\|");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<String> splitUnescaped(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                cur.append(switch (n) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else if (c == '|') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else if (c != '\\') {
                // 截断在转义符中间时残留的孤立 \ 直接丢掉。
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }
}
