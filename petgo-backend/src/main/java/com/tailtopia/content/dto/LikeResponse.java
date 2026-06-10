package com.tailtopia.content.dto;

/**
 * 点赞开关响应（Story 3.4）。返回服务端真值供前端校正乐观更新。
 *
 * @param liked     当前用户是否已赞
 * @param likeCount 该内容当前点赞总数
 */
public record LikeResponse(boolean liked, long likeCount) {
}
