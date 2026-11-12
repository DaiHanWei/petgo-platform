package com.tailtopia.analytics.rollup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分段的汇总 SQL（analytics-pipeline Story 4.4）：一个指标族一个 {@code analytics/rollup/<name>.sql} 文件，文件内用整行标记
 * {@code -- @section <名字>} 分成几段（例如 {@code compute} / {@code daily} / {@code weekly} / {@code monthly}），
 * 每段再按 {@link SqlRollupFamily#splitStatements} 拆成语句。第一个标记之前只允许注释（文件头说明）。
 */
final class RollupSqlSections {

    /** 段标记：整行 {@code -- @section name}。 */
    static final Pattern MARKER = Pattern.compile("(?m)^--\\s*@section\\s+([a-z_]+)\\s*$");

    private RollupSqlSections() {
    }

    /** 读 classpath 上 {@code analytics/rollup/<family>.sql} 并分段。 */
    static Map<String, List<String>> load(String family) {
        return parse(SqlRollupFamily.readScript(family), SqlRollupFamily.resourcePath(family));
    }

    /**
     * 分段：返回「段名 → 语句」（按出现顺序）。段名重复、某段没有语句、第一个标记前有语句 → {@link IllegalStateException}。
     */
    static Map<String, List<String>> parse(String script, String where) {
        Matcher m = MARKER.matcher(script);
        Map<String, List<String>> out = new LinkedHashMap<>();
        int prevEnd = -1;
        String prevName = null;
        while (m.find()) {
            if (prevName == null) {
                if (!SqlRollupFamily.splitStatements(script.substring(0, m.start())).isEmpty()) {
                    throw new IllegalStateException(where + "：第一个 @section 之前不能有语句");
                }
            } else {
                put(out, prevName, script.substring(prevEnd, m.start()), where);
            }
            prevName = m.group(1);
            prevEnd = m.end();
        }
        if (prevName == null) {
            throw new IllegalStateException(where + "：没有任何 @section");
        }
        put(out, prevName, script.substring(prevEnd), where);
        return java.util.Collections.unmodifiableMap(out);
    }

    private static void put(Map<String, List<String>> out, String name, String body, String where) {
        List<String> stmts = SqlRollupFamily.splitStatements(body);
        if (stmts.isEmpty()) {
            throw new IllegalStateException(where + "：@section " + name + " 没有语句");
        }
        if (out.put(name, stmts) != null) {
            throw new IllegalStateException(where + "：@section " + name + " 重复");
        }
    }

    /** 取一段；没有 → {@link IllegalStateException}。 */
    static List<String> require(Map<String, List<String>> sections, String name, String where) {
        List<String> s = sections.get(name);
        if (s == null) {
            throw new IllegalStateException(where + "：缺 @section " + name);
        }
        return s;
    }
}
