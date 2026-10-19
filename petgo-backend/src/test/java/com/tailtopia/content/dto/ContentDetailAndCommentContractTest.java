package com.tailtopia.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.content.domain.ContentType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标：内容详情 + 评论两级 wire（CROSS-STORY-DECISIONS C5）。
 *
 * <p>三方同步点：
 * <ul>
 *   <li>App  —— {@code content/domain/content_detail.dart}（{@code ContentDetail.fromJson}）、
 *              {@code content/domain/comment.dart}（{@code Comment.fromJson}/{@code CommentPage.fromJson}）</li>
 *   <li>Mock —— {@code mock_backend.dart}（{@code /content-posts/{id}}、{@code .../comments}）</li>
 * </ul>
 *
 * <p>注意 record 的 boolean 分量（{@code authorDeleted}/{@code liked}/{@code isAuthor}）Jackson 按分量名原样
 * 落键（不剥 {@code is}），App 读 {@code json['isAuthor']} 等据此对齐。
 */
class ContentDetailAndCommentContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    @Test
    void contentDetailHasExactlyTheContractFields() {
        ContentDetailResponse d = new ContentDetailResponse(
                1L, 7L, "小明", "https://cdn/a.jpg", false,
                // V1.1.6 Story 5.1：运营标签随作者一起下发；**空表不下发**（NON_NULL 省略）。
                List.of(new com.tailtopia.auth.dto.UserTagView("vet", "兽医", "🩺", "已认证兽医", "#F6A609")),
                List.of(new ContentTagView("editor_pick", "编辑推荐", "🏆", "被官方选中的优质内容",
                        "#F6A609", "#F0596E")),
                ContentType.DAILY, "正文",
                List.of("https://cdn/1.jpg", "https://cdn/2.jpg"),
                // V1.3.0 Story 2.1：与 imageUrls 同序等长；第 2 张测不出来 → null 占位保持下标对齐。
                java.util.Arrays.asList(new com.tailtopia.content.domain.ImageSize(1200, 900), null),
                5L, 2L, true, false,
                // V1.1.6 Story 10.1：**恒下发**（与 FeedItemResponse 同口径）。
                // 补它的唯一理由是埋点 E-11 的加粗属性 is_private_diary。
                com.tailtopia.content.domain.ContentVisibility.PRIVATE,
                Instant.parse("2026-06-05T00:00:00Z"),
                // V1.3.0 batch-b1 Story 3.3：正文里的 @（能不能点是服务端算好的）。
                List.of(new com.tailtopia.mention.dto.MentionView(9L, "阿花", true)),
                // V1.3.2 Story 1.5：打卡场所条（普通帖为 null → 省略，见下一例）。
                new com.tailtopia.place.dto.CheckinPlaceView("p".repeat(32), "Kopi Kucing",
                        com.tailtopia.place.domain.PlaceAvailability.ACTIVE),
                // V1.3.4 follow Story 2.1：作者 token 与关注关系（AD-6 键名）。
                "t".repeat(32), new com.tailtopia.social.read.FollowState(false, true),
                // V1.3.4 Story 2.1：每张图的露出位置（与 imageUrls 同序；null 元素原样下发）。
                java.util.Arrays.asList(1.0, null),
                // V1.4.0 ① Story 3.1：作者等级三字段。
                7, "BUDDY", "BASIC",
                // V1.4.0 multi-pet Story 3.1：Moment 无关联宠物 → null 省略（形态见 diaryPetsKeyShape）。
                null,
                // V1.4.0 gift Story 2.1：Diary 礼物区（关联宠物各自的礼物汇总 + 墙前 3 行）。
                List.of(new com.tailtopia.gift.web.dto.GiftPetSummaryView(30L, "Mochi", null, 53_000L, 4L, 2,
                        List.of(new com.tailtopia.gift.web.dto.GiftWallItemView("Cake", "Kue", "https://cdn/c.png",
                                50_000L, 1L)))),
                // V1.4.0 ④ Story 2.1：是否已收藏（恒下发）+ 作者视角收藏数（非作者为 null → 省略，见下一例）。
                true, 2L);

        assertThat(wire(d).keySet()).isEqualTo(Set.of(
                "id", "authorId", "authorNickname", "authorAvatarUrl", "authorDeleted",
                "authorTags", "decorationTags", "type",
                "body", "imageUrls", "imageSizes", "likeCount", "commentCount", "liked", "isAuthor",
                "visibility", "createdAt", "mentions", "checkinPlace", "authorToken", "authorFollowState",
                "imageCropOffsets", "authorLevel", "authorTier", "authorFrame", "giftPets",
                "collected", "collectCount"));
        assertThat(wire(d)).containsEntry("authorLevel", 7).containsEntry("authorTier", "BUDDY")
                .containsEntry("authorFrame", "BASIC");
        assertThat(wire(d)).containsEntry("collected", true).containsEntry("collectCount", 2L);
        // V1.4.0 gift Story 2.1：礼物区只下发数字与 EN / ID 快照，不下发 owner / sender / 任何 token。
        @SuppressWarnings("unchecked")
        Map<String, Object> giftPet = ((List<Map<String, Object>>) wire(d).get("giftPets")).get(0);
        assertThat(giftPet.keySet()).isEqualTo(Set.of("petId", "name", "totalValue", "giftCount", "kindCount",
                "wallPreview"));
        @SuppressWarnings("unchecked")
        Map<String, Object> follow = (Map<String, Object>) wire(d).get("authorFollowState");
        assertThat(follow).isEqualTo(Map.of("iFollow", false, "followsMe", true));
        assertThat(wire(d).get("imageCropOffsets")).isEqualTo(java.util.Arrays.asList(1.0, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> place = (Map<String, Object>) wire(d).get("checkinPlace");
        assertThat(place.keySet()).isEqualTo(Set.of("token", "name", "status"));
        assertThat(place.get("status")).isEqualTo("ACTIVE");
        // 线格式是枚举名大写（客户端按字符串比 PRIVATE / PUBLIC，不做数字映射）。
        assertThat(wire(d)).containsEntry("visibility", "PRIVATE");
    }

    /** V1.3.2 Story 1.5：普通帖（无关联打卡）不下发 checkinPlace 键。 */
    @Test
    void plainPostOmitsCheckinPlace() {
        ContentDetailResponse d = new ContentDetailResponse(1L, 7L, "小明", null, false, null, null,
                ContentType.DAILY, "正文", List.of(), null, 0L, 0L, false, false,
                com.tailtopia.content.domain.ContentVisibility.PUBLIC, Instant.parse("2026-06-05T00:00:00Z"),
                null, null, null, null, null, null, null, null, null, null, false, null);
        assertThat(wire(d)).doesNotContainKey("checkinPlace");
        // V1.4.0 ① Story 3.1：growth == null → 等级三字段整体省略。
        assertThat(wire(d)).doesNotContainKeys("authorLevel", "authorTier", "authorFrame");
        // V1.4.0 multi-pet Story 3.1：无关联宠物不下发 pets。
        assertThat(wire(d)).doesNotContainKey("pets");
        // V1.4.0 ④ Story 2.1：非作者无 collectCount 键（收藏数不对外展示）；collected 恒下发。
        assertThat(wire(d)).doesNotContainKey("collectCount").containsEntry("collected", false);
        // V1.3.4：作者已注销 / 封号时 token 与关注关系不下发。
        assertThat(wire(d)).doesNotContainKey("authorToken").doesNotContainKey("authorFollowState");
        // V1.3.4 Story 2.1：存量 / 无超界图的帖不下发露出位置（App 无值即居中）。
        assertThat(wire(d)).doesNotContainKey("imageCropOffsets");
        // V1.4.0 gift Story 2.1：Moment / Tips / 无关联宠物不下发礼物区。
        assertThat(wire(d)).doesNotContainKey("giftPets");
    }

    /** V1.4.0 multi-pet Story 3.1 · AC8：Diary 关联宠物 → {@code pets} 数组，每项四键（L2 第二轮追加可空 birthday），顺序原样。 */
    @Test
    void diaryPetsKeyShape() {
        ContentDetailResponse d = new ContentDetailResponse(1L, 7L, "小明", null, false, null, null,
                ContentType.GROWTH_MOMENT, "正文", List.of(), null, 0L, 0L, false, false,
                com.tailtopia.content.domain.ContentVisibility.PUBLIC, Instant.parse("2026-06-05T00:00:00Z"),
                null, null, null, null, null,
                // V1.4.0 ① Story 3.1：作者等级三字段（null → 省略）。
                null, null, null,
                List.of(new ContentPetView(21L, "Mochi", "https://cdn/m.jpg", "CAT",
                                java.time.LocalDate.of(2024, 3, 1)),
                        new ContentPetView(22L, "Bolu", null, "DOG")),
                // V1.4.0 gift Story 2.1：礼物区不在本例关注范围（null → 省略）。
                null,
                // V1.4.0 ④ Story 2.1：收藏态不在本例关注范围。
                false, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pets = (List<Map<String, Object>>) wire(d).get("pets");
        assertThat(pets).hasSize(2);
        // L2 第二轮：追加 birthday（ISO 日期串；null → 省略）。
        assertThat(pets.get(0).keySet()).isEqualTo(Set.of("petId", "name", "avatarUrl", "petType", "birthday"));
        assertThat(pets.get(0)).containsEntry("birthday", "2024-03-01");
        assertThat(((Number) pets.get(0).get("petId")).longValue()).isEqualTo(21L);
        assertThat(pets.get(0)).containsEntry("petType", "CAT");
        // NON_NULL：没头像的那只省略 avatarUrl 键。
        assertThat(pets.get(1).keySet()).isEqualTo(Set.of("petId", "name", "petType"));
    }

    @Test
    void topLevelCommentHasModerationStatusField() {
        // story 3：新增 moderationStatus 字段（VISIBLE 无标签；TAKEN_DOWN 前端渲染「仅你可见」）。
        CommentResponse top = new CommentResponse(
                10L, 7L, "小明", "https://cdn/a.jpg", false,
                List.of(new com.tailtopia.auth.dto.UserTagView("vet", "兽医", "🩺", "已认证兽医", "#F6A609")),
                "评论正文",
                Instant.parse("2026-06-05T00:00:00Z"), 3, List.of(), "VISIBLE",
                // V1.3.0 Story 2.4：点赞数与已赞状态恒下发（实时聚合，库里没有计数列）。
                5L, true,
                // V1.3.0 batch-b1 Story 3.3：评论里的 @。
                List.of(new com.tailtopia.mention.dto.MentionView(9L, "阿花", true)),
                // V1.4.0 ① Story 3.1：作者等级（无框 → authorFrame 省略）。
                3, "NEWBIE", null);

        assertThat(wire(top).keySet()).isEqualTo(Set.of(
                "id", "authorId", "authorNickname", "authorAvatarUrl", "authorDeleted",
                "authorTags", "body", "createdAt", "replyCount", "replies", "moderationStatus",
                "likeCount", "liked", "mentions", "authorLevel", "authorTier"));
    }

    @Test
    void replyCommentOmitsReplyCountAndReplies() {
        // 二级回复无嵌套：replyCount/replies 为 null → NON_NULL 省略；moderationStatus 始终下发。
        CommentResponse reply = new CommentResponse(
                // 无标签 → authorTags 为 null → NON_NULL 省略（下方字段集里因此没有它）。
                11L, 8L, "小红", null, false, null, "回复正文",
                Instant.parse("2026-06-05T00:00:00Z"), null, null, "VISIBLE", 0L, false, null);

        assertThat(wire(reply).keySet()).isEqualTo(Set.of(
                "id", "authorId", "authorNickname", "authorDeleted", "body", "createdAt",
                "moderationStatus", "likeCount", "liked"));
        assertThat(wire(reply)).doesNotContainKey("replyCount");
        // Story 3.3：没 @ 人的评论整列省略（存量评论恒如此）。
        assertThat(wire(reply)).doesNotContainKey("mentions");
        // V1.4.0 ① Story 3.1：growth == null（14 参兼容构造）→ 等级三字段整体省略。
        assertThat(wire(reply)).doesNotContainKeys("authorLevel", "authorTier", "authorFrame");
    }

    @Test
    void commentPageEnvelopeShape() {
        CommentPageResponse page = new CommentPageResponse(List.of(), "cur", true);
        assertThat(wire(page).keySet()).isEqualTo(Set.of("items", "nextCursor", "hasMore"));

        // 末页省略 nextCursor。
        CommentPageResponse last = new CommentPageResponse(List.of(), null, false);
        assertThat(wire(last).keySet()).isEqualTo(Set.of("items", "hasMore"));
    }
}
