package com.tailtopia.analytics.rollup;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * 纯 SQL 指标族（analytics-pipeline Story 4.1，架构「汇总 SQL 每个指标族一个纯文本文件」）：
 * 从 classpath 读 {@code analytics/rollup/<name>.sql}，拆成语句，在单元事务内逐条用埋点库
 * {@code NamedParameterJdbcTemplate} 执行，参数由 {@link RollupContext#dayParams} 传入。
 *
 * <p>拆分：Spring 7 的 {@code ScriptUtils.splitSqlScript} 已不再公开，这里用 {@link #splitStatements} 自带的小拆分器——
 * 去掉 {@code --} 行注释与 {@code /* *}{@code /} 块注释，按字符串 / 引号标识符之外的 {@code ;} 拆。
 * 汇总 SQL 不写 {@code $$} 函数体（不支持美元引号）。
 */
public class SqlRollupFamily implements RollupFamily {

    /** classpath 目录。 */
    public static final String LOCATION = "analytics/rollup/";

    private final String name;
    private final int recomputeDays;
    private final Set<String> dependsOn;
    private final List<String> statements;

    public SqlRollupFamily(String name) {
        this(name, 3, Set.of());
    }

    public SqlRollupFamily(String name, int recomputeDays, Set<String> dependsOn) {
        if (name == null || !name.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("指标族名非法：" + name);
        }
        if (recomputeDays <= 0) {
            throw new IllegalArgumentException("recomputeDays 必须为正");
        }
        this.name = name;
        this.recomputeDays = recomputeDays;
        this.dependsOn = Set.copyOf(dependsOn);
        this.statements = splitStatements(readScript(name));
        if (statements.isEmpty()) {
            throw new IllegalStateException(resourcePath(name) + " 没有任何语句");
        }
    }

    /** {@code analytics/rollup/<name>.sql}。 */
    public static String resourcePath(String family) {
        return LOCATION + family + ".sql";
    }

    /** 读 classpath 上的汇总 SQL 原文（UTF-8）。找不到 → {@link IllegalStateException}。 */
    public static String readScript(String family) {
        Resource r = new ClassPathResource(resourcePath(family));
        if (!r.exists()) {
            throw new IllegalStateException("找不到汇总 SQL：" + resourcePath(family));
        }
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取汇总 SQL 失败：" + resourcePath(family), e);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int recomputeDays() {
        return recomputeDays;
    }

    @Override
    public Set<String> dependsOn() {
        return dependsOn;
    }

    /** 拆好的语句（不含注释与结尾分号）。 */
    public List<String> statements() {
        return statements;
    }

    @Override
    public void computeDay(LocalDate statDate, LocalDate asOf, RollupContext ctx) {
        MapSqlParameterSource params = ctx.dayParams(statDate);
        for (String sql : statements) {
            ctx.jdbc().update(sql, params);
        }
    }

    /**
     * 把脚本拆成语句：去注释，按字符串（{@code '…'}，{@code ''} 转义）与引号标识符（{@code "…"}）之外的分号拆分，
     * 去首尾空白，丢弃空语句。
     */
    static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = script.length();
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            char next = i + 1 < n ? script.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                while (i < n && script.charAt(i) != '\n') {
                    i++;
                }
                cur.append('\n');
                continue;
            }
            if (c == '/' && next == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                cur.append(' ');
                continue;
            }
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n) {
                    if (script.charAt(j) == c) {
                        if (j + 1 < n && script.charAt(j + 1) == c) {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                if (j >= n) {
                    throw new IllegalArgumentException("汇总 SQL 引号未闭合");
                }
                cur.append(script, i, j + 1);
                i = j + 1;
                continue;
            }
            if (c == ';') {
                flush(cur, out);
                i++;
                continue;
            }
            cur.append(c);
            i++;
        }
        flush(cur, out);
        return List.copyOf(out);
    }

    private static void flush(StringBuilder cur, List<String> out) {
        String s = cur.toString().strip();
        if (!s.isEmpty()) {
            out.add(s);
        }
        cur.setLength(0);
    }
}
