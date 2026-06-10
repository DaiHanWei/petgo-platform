package com.tailtopia.vet.domain;

/**
 * 兽医在线态（Story 5.2，架构「在线/忙/离线」三态）。
 *
 * <ul>
 *   <li>{@link #ONLINE}：可接新请求，出现在可接单队列。</li>
 *   <li>{@link #BUSY}：进行中会话占用（Story 5.5 接单时置位，本故事仅留枚举位）。</li>
 *   <li>{@link #OFFLINE}：不接新请求（显式离线或 TTL 到期兜底）。</li>
 * </ul>
 * 本故事只读写 ONLINE/OFFLINE（在线态以「TTL 窗口内是否有心跳」判定）。
 */
public enum VetPresenceStatus {
    ONLINE,
    BUSY,
    OFFLINE
}
