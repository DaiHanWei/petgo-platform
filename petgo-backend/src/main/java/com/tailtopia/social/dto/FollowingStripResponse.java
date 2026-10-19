package com.tailtopia.social.dto;

import java.util.List;

/** 关注头像排（V1.3.4 following 流）：最多 20 个，无分页。 */
public record FollowingStripResponse(List<Item> items) {

    /** {@code level / tier / frame}：等级展示（V1.4.0 ① Story 3.1 · AD-14）；停用 / 无等级时省略，无框省略 frame。 */
    public record Item(long userId, String userToken, String nickname, String avatarUrl,
            Integer level, String tier, String frame) {
    }
}
