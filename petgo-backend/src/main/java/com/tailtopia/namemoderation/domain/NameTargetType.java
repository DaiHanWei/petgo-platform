package com.tailtopia.namemoderation.domain;

/**
 * 名称审核对象类型（内容审核 story 4，落库 UPPER_SNAKE）。
 *
 * <ul>
 *   <li>{@link #NICKNAME} 用户昵称（{@code target_ref_id} = users.id，FR-0E）。</li>
 *   <li>{@link #PET_NAME} 宠物名字（{@code target_ref_id} = pet_profiles.id，FR-11）。</li>
 * </ul>
 */
public enum NameTargetType {
    NICKNAME,
    PET_NAME
}
