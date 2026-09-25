package com.tailtopia.admin.anomaly.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 异常工单内部备注时间线的一行（V1.3.0 Story 2.6 AC2）。存储格式：{@code internal_note} 一列 TEXT，按行追加
 * {@code <epochMillis>|<操作人>|<内容>}（操作人与内容里的换行折叠为空格；操作人里的竖线换成全角；
 * 内容取第二个竖线之后的全部，可含竖线）。旧数据（覆盖式单段文本）解析为一条无人无时间的行，原样展示。
 *
 * @param author 操作人显示名（后台账号）；旧数据为 null
 * @param at     写入时刻；旧数据为 null
 */
public record AnomalyNoteLine(String author, Instant at, String text) {

    /** 编码行首段必须是 13 位毫秒时间戳（2001-09 之后）；旧自由文本形如 {@code 12|a|b} 不会被误判。 */
    static final long MIN_EPOCH_MILLIS = 1_000_000_000_000L;

    public static String encode(Instant at, String author, String text) {
        return at.toEpochMilli() + "|" + flat(author).replace("|", "｜") + "|" + flat(text);
    }

    /** 解析整列文本为时间线（按写入顺序）；空 → 空列表。 */
    public static List<AnomalyNoteLine> parse(String raw) {
        List<AnomalyNoteLine> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int p1 = line.indexOf('|');
            int p2 = p1 < 0 ? -1 : line.indexOf('|', p1 + 1);
            if (p1 > 0 && p2 > p1) {
                try {
                    long ms = Long.parseLong(line.substring(0, p1));
                    if (ms < MIN_EPOCH_MILLIS) {
                        throw new NumberFormatException("not an epoch-millis prefix");
                    }
                    out.add(new AnomalyNoteLine(line.substring(p1 + 1, p2), Instant.ofEpochMilli(ms),
                            line.substring(p2 + 1)));
                    continue;
                } catch (NumberFormatException ignore) {
                    // 不是编码行 → 当旧数据
                }
            }
            out.add(new AnomalyNoteLine(null, null, line));
        }
        return out;
    }

    private static String flat(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }
}
