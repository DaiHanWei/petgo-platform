/**
 * 头像审核领域事件（内容审核 story 5，过去式）：{@code AvatarReviewRequestedEvent}（换头像 → 异步送审）、
 * {@code AvatarResetEvent}（违规重置 → 推送 AVATAR_RESET）。触发方经事件解耦，不直调 avatarmoderation service。
 */
package com.tailtopia.avatarmoderation.event;
