package com.tailtopia.avatarmoderation.domain;

/**
 * 头像审核对象类型（内容审核 story 5，落库 UPPER_SNAKE）。与名称侧 {@code NameTargetType} 并列同构。
 *
 * <ul>
 *   <li>{@link #USER_AVATAR} 用户头像（{@code subject_id} = users.id，FR-0E）。</li>
 *   <li>{@link #PET_AVATAR} 宠物头像（{@code subject_id} = pet_profiles.id，FR-11）。</li>
 * </ul>
 */
public enum AvatarSubjectType {
    USER_AVATAR,
    PET_AVATAR
}
