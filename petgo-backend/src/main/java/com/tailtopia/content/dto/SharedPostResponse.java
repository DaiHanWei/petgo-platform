package com.tailtopia.content.dto;

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
 */
public record SharedPostResponse(
        String authorNickname,
        String authorAvatarUrl,
        boolean authorDeleted,
        String type,
        String body,
        List<String> imageUrls,
        Instant createdAt) {
}
