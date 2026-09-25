package com.tailtopia.admin.dashboard.metrics;

/**
 * 「运营马甲 / 种子号」判定的<b>唯一 SQL 片段</b>（V1.3.0 Story 3.1，AD-8）。全部 Java 原生查询只引用这两个常量拼接，
 * 禁止散写 {@code LIKE 'virtual:%'} / {@code 'seed-tailtopia-%'}（AC4 有 grep 守卫）。
 * 表别名约定：{@code u = users}。与 {@code User.isSyntheticAccount()} 同口径（account_type / role 列，不看前缀）；
 * Apple 用户（google_sub NULL）天然为真实用户。
 */
public final class SyntheticAccountSql {

    /** 真实用户过滤条件（WHERE 片段，无外层括号）：排除虚拟号与官方作者 shim。需要否定时用 {@link #IS_SYNTHETIC}，勿前置 {@code NOT}。 */
    public static final String EXCLUDE_WHERE = "u.account_type = 'REAL' AND u.role <> 'ADMIN'";

    /** 是否马甲 / 种子号（布尔表达式，可放 SELECT / CASE）。 */
    public static final String IS_SYNTHETIC = "(u.account_type = 'VIRTUAL' OR u.role = 'ADMIN')";

    private SyntheticAccountSql() {
    }
}
