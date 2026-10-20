package com.tailtopia.admin.growth.service;

import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 批量发放的用户 ID 解析（V1.4.0 ① Story 4.3 · AC3.1）。纯函数，可单测。
 *
 * <p>粘贴框按 {@code [,，;\s]+} 切（同一行内多个 ID 也认）；CSV 每行只取第一列，首行是表头（{@code user_id / uid / id}）则忽略；
 * 两者取并集。去 UTF-8 BOM 与首尾引号；<b>重复只算一次、不算无效</b>；非数字计入「格式不正确」。
 *
 * <p>⚠️ 不复用 AB-31 的 {@code parseIdLines}：那边每行只取第一列，本页要求同一行逗号 / 空格分隔多个 ID。
 */
public final class GrowthBatchParser {

    /** 去重后的 ID 上限（防误粘贴整张表；确认弹层 hidden 字段与逐人事务的耗时可控）。 */
    public static final int MAX_IDS = 5_000;

    /** CSV 上传上限 1 MB。 */
    public static final long MAX_CSV_BYTES = 1024 * 1024;

    private static final Pattern TOKEN_SEP = Pattern.compile("[,，;\\s]+");
    private static final Pattern CSV_FIELD_SEP = Pattern.compile("[,，;\\t]");
    private static final Pattern ID = Pattern.compile("\\d{1,18}");
    private static final Set<String> HEADERS = Set.of("user_id", "uid", "id");

    private GrowthBatchParser() {
    }

    /**
     * 解析结果。{@code ids} 保持首次出现顺序；{@code malformed} 为去重后的非数字 token（原样，供预览「格式不正确」行）。
     */
    public record Parsed(List<Long> ids, List<String> malformed) {

        /** 规范化后的全部 token（数字 + 格式不正确），逗号拼接：确认 / 执行时据此重新解析、重新归类。 */
        public String normalized() {
            List<String> all = new ArrayList<>(ids.size() + malformed.size());
            ids.forEach(id -> all.add(String.valueOf(id)));
            all.addAll(malformed);
            return String.join(",", all);
        }
    }

    /**
     * @param pasted 粘贴框原文（可空）
     * @param csv    CSV 文件文本（可空）
     * @throws AppException 422：一个 token 都没有 / 去重后超过 {@link #MAX_IDS}
     */
    public static Parsed parse(String pasted, String csv) {
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> malformed = new LinkedHashSet<>();
        if (pasted != null) {
            for (String t : TOKEN_SEP.split(stripBom(pasted))) {
                accept(t, ids, malformed);
            }
        }
        if (csv != null) {
            String[] lines = stripBom(csv).split("\\r?\\n|\\r");
            for (int i = 0; i < lines.length; i++) {
                String first = clean(CSV_FIELD_SEP.split(lines[i], 2)[0]);
                if (i == 0 && HEADERS.contains(first.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                accept(first, ids, malformed);
            }
        }
        if (ids.isEmpty() && malformed.isEmpty()) {
            throw AppException.validation("请填写或上传用户 ID").code("admin.err.growth.batch.idsRequired");
        }
        if (ids.size() + malformed.size() > MAX_IDS) {
            throw AppException.validation("用户 ID 不能超过 " + MAX_IDS + " 个")
                    .code("admin.err.growth.batch.idsTooMany", MAX_IDS);
        }
        return new Parsed(List.copyOf(ids), List.copyOf(malformed));
    }

    private static void accept(String raw, Set<Long> ids, Set<String> malformed) {
        String t = clean(raw);
        if (t.isEmpty()) {
            return;
        }
        if (ID.matcher(t).matches()) {
            ids.add(Long.parseLong(t));
        } else {
            // CSV 单元格里可能含空白（「10001 alice」）：折叠成 _，否则写回 hidden 后被粘贴口径按空白再切，
            // 确认 / 执行时会冒出预览里没有的有效 ID（code-review）。截断防超长垃圾撑爆预览与 hidden 字段。
            String m = TOKEN_SEP.matcher(t).replaceAll("_");
            malformed.add(m.length() > 40 ? m.substring(0, 40) : m);
        }
    }

    private static String clean(String raw) {
        String t = stripBom(raw).trim();
        while (t.length() >= 1 && (t.charAt(0) == '"' || t.charAt(0) == '\'')) {
            t = t.substring(1);
        }
        while (!t.isEmpty() && (t.charAt(t.length() - 1) == '"' || t.charAt(t.length() - 1) == '\'')) {
            t = t.substring(0, t.length() - 1);
        }
        return t.trim();
    }

    private static String stripBom(String s) {
        return s.replace("﻿", "");
    }
}
