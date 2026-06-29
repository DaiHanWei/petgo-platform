package com.tailtopia.admin.account.domain;

/**
 * 后台账号类型（落库 varchar + UPPER，Story 1.1）。
 *
 * <ul>
 *   <li>{@link #SUPER_ADMIN}：超级管理员，全平台最高权限（上限 5，Story 1.5 校验）。</li>
 *   <li>{@link #STAFF}：普通后台账号，按 {@code admin_account_permissions} 的模块权限授权（Story 1.5）。</li>
 * </ul>
 */
public enum AdminAccountType {
    SUPER_ADMIN,
    STAFF
}
