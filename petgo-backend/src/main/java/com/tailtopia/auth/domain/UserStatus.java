package com.tailtopia.auth.domain;

/**
 * 普通用户账号状态（Story 3.2，落库 varchar + UPPER_SNAKE）。与「删除/注销」（物理删 + deletedAt）正交。
 *
 * <ul>
 *   <li>{@link #ACTIVE}：正常，可登录。</li>
 *   <li>{@link #DEACTIVATED}：运营停用，即时不可登录（可重新激活恢复）。</li>
 * </ul>
 */
public enum UserStatus {
    ACTIVE,
    DEACTIVATED
}
