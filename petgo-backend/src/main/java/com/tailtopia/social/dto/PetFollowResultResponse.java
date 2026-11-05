package com.tailtopia.social.dto;

/**
 * 追更 / 取消追更的返回（V1.4.2 social-topic · Story 1.1 · D-8）—— 访客页即时 ±1 用。
 *
 * @param iFollow       写入后我是否追更这只宠物（追更恒 true、取消恒 false）
 * @param followerCount 这只宠物最新的追更人数（写入后<b>重新读出</b>，不用实体快照）
 */
public record PetFollowResultResponse(boolean iFollow, long followerCount) {
}
