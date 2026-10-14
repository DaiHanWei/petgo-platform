package com.tailtopia.social.dto;

import java.util.List;

/** 关注头像排（V1.3.4 following 流）：最多 20 个，无分页。 */
public record FollowingStripResponse(List<Item> items) {

    public record Item(long userId, String userToken, String nickname, String avatarUrl) {
    }
}
