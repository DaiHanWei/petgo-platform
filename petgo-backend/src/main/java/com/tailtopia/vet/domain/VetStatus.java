package com.tailtopia.vet.domain;

/**
 * 兽医账号状态（落库 varchar + UPPER，Story 5.1）。
 *
 * <ul>
 *   <li>{@link #ACTIVE}：可登录、可接单。</li>
 *   <li>{@link #BANNED}：运营封禁，不可登录（Story 5.7 在此基础上加「封禁时中断进行中会话」）。</li>
 * </ul>
 */
public enum VetStatus {
    ACTIVE,
    BANNED
}
