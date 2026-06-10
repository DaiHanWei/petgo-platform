package com.petgo.auth.domain;

/**
 * 宠物状态（FR-0F，落库 varchar，枚举名即存储值/API 契约值）。
 * <ul>
 *   <li>{@code HAS_PET} —— 我有宠物（三类内容全显，解锁成长档案）。</li>
 *   <li>{@code PLANNING} —— 暂无但计划养（首页不显成长日历内容）。</li>
 *   <li>{@code ENTHUSIAST} —— 宠物爱好者（全显）。</li>
 * </ul>
 */
public enum PetStatus {
    HAS_PET,
    PLANNING,
    ENTHUSIAST
}
