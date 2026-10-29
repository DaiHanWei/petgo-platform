package com.tailtopia.content.dto;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.MediaType;
import java.time.Instant;
import java.util.List;

/**
 * 单条分享内容的**公开只读投影**（Story 9.3 · AD-15 Rule 5）。
 *
 * <p>🛡 <b>刻意只有这几个字段</b>：作者昵称/头像、类型、正文、图片、时间。
 * 没有 postId、没有 authorId、没有 petId、没有 cardToken —— 结构上就<b>拿不到</b>
 * 任何能用来去看该宠物其它内容的把手。
 *
 * <p>「落地页上没放入口」是不够的：将来有人给页面加个「看更多」，
 * 只要投影里有 id 就能无声地把整本档案漏出去。所以边界画在投影上，不在页面上。
 *
 * <p>同理没有 likeCount / commentCount / liked：那些是站内互动，
 * 未登录访客既看不到也不该看到。
 *
 * <p>V1.4.0 ① Story 3.1：加作者等级三字段（只是等级值，不是把手，不破坏上述边界）；注销 / 停用 / 无等级时省略。
 */
public record SharedPostResponse(
        String authorNickname,
        String authorAvatarUrl,
        boolean authorDeleted,
        String type,
        String body,
        List<String> imageUrls,
        Instant createdAt,
        Integer authorLevel,
        String authorTier,
        String authorFrame,
        /** 媒体类型（专题线 video Story 2.1）：恒下发。 */
        MediaType mediaType,
        /** 视频信息（Story 2.1）：只在视频帖下发；分享页是匿名访问，恒为公开地址。 */
        VideoView video) {

    /**
     * 唯一组装点（专题线 video Story 2.1 · 一致性规则 4）。
     *
     * <p>🛡 仍然<b>不放任何 id</b>（见类注释）：视频信息只有地址、时长、宽高；作者等级三字段只是等级值。
     */
    public static SharedPostResponse of(ContentPost post, AuthorView author, PostMedia media) {
        List<String> images = media.imageUrls() == null ? List.of() : List.copyOf(media.imageUrls());
        return new SharedPostResponse(
                author.nickname(),
                author.avatarUrl(),
                author.deleted(),
                post.getType().name(),
                post.getText(),
                images,
                post.getCreatedAt(),
                author.badgeLevel(),
                author.badgeTier(),
                author.badgeFrame(),
                media.mediaType(),
                media.video());
    }
}
