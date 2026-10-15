package com.tailtopia.social.dto;

/**
 * 关注 / 取关的返回（V1.3.4 follow · Story 1.1 · AD-4）—— 主页即时 ±1 用。
 *
 * @param iFollow       写入后我是否关注对方（关注恒 true、取关恒 false）
 * @param followsMe     对方是否关注我
 * @param followerCount 对方最新的粉丝数（写入后<b>重新读出</b>，不用实体快照）
 */
public record FollowResultResponse(boolean iFollow, boolean followsMe, long followerCount) {
}
