package com.tailtopia.admin.account.domain;

/**
 * 后台账号状态（落库 varchar + UPPER，Story 1.1）。
 *
 * <ul>
 *   <li>{@link #ACTIVE}：可登录。</li>
 *   <li>{@link #DISABLED}：停用，保留账号但不可登录（Story 1.5 停用/重新激活；账号不可永久删除）。</li>
 * </ul>
 */
public enum AdminAccountStatus {
    ACTIVE,
    DISABLED
}
