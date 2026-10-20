package com.tailtopia.content.dto;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import java.time.Instant;
import java.util.List;

/**
 * 内容详情投影（Story 3.3）。Jackson NON_NULL；时间 ISO-8601 UTC。
 *
 * <p>{@code likeCount}/{@code liked} 在本 Story 为占位（0 / false），由 Story 3.4 接入真实点赞表；
 * {@code commentCount} 取自 comments 表实计。{@code isAuthor} 供前端「···」删除入口可见性（行为在 3.6）。
 * 作者注销匿名化（NFR-8）→ 仍 200，作者投影为「已注销」（前端本地化），非 404。
 *
 * @param liked    当前用户是否已赞（游客 false）；3.4 接入
 * @param isAuthor 当前用户是否作者（删除入口可见性）
 */
public record ContentDetailResponse(
        Long id,
        long authorId,
        String authorNickname,
        String authorAvatarUrl,
        boolean authorDeleted,
        // 运营标签（V1.1.6 Story 5.1 · FR-74）。最多 3 个；注销作者恒为空表。
        java.util.List<com.tailtopia.auth.dto.UserTagView> authorTags,
        // 内容装饰标签（V1.1.6 Story 5.2 · FR-75）。空表不下发。
        java.util.List<ContentTagView> decorationTags,
        ContentType type,
        String body,
        List<String> imageUrls,
        /**
         * 图片原始宽高（V1.3.0 批次 A · Story 2.1 · AD-A12），与 {@link #imageUrls()}
         * <b>同序等长</b>；测不出来的位置为 {@code null}，存量内容（V1.1.6 之前发布的）整列各位为
         * {@code null}。无图时整个字段省略（NON_NULL）。
         *
         * <p><b>为什么详情页需要它</b>：没有尺寸，页面只能等图片下载完才知道该留多高 ——
         * 于是首屏加载时整页跳动。PRD 把 FR-113 归为「纯前端」是一处疏漏：详情响应此前
         * 只有 {@code imageUrls}（v1.1.6 AD-5 明写「既有五处图片读取点一处不动」，
         * 详情页正是其中之一）。
         *
         * <p>🛡 <b>只有原始宽高。</b>展示比例的收敛（clamp）与高度护栏<b>一律客户端算</b> ——
         * 护栏依赖可视区高度，服务端算不了；服务端先 clamp 一遍、客户端再 clamp 一遍
         * 就是<b>双重裁切</b>（v1.1.6 AD-6 Rule 6）。
         * ⚠️ 想在这里加「比例」或「已算好的高度」字段之前，先读一遍这段。
         *
         * <p>元素类型与 {@code FeedItemResponse.imageSizes()} <b>完全一致</b>，客户端两处共用一套解析。
         */
        List<com.tailtopia.content.domain.ImageSize> imageSizes,
        long likeCount,
        long commentCount,
        boolean liked,
        boolean isAuthor,
        /**
         * 可见性（V1.1.6 Story 10.1 补下发）。<b>恒下发</b>，与 {@code FeedItemResponse} 同口径。
         *
         * <p>补它的唯一理由是埋点 E-11 的 {@code is_private_diary} —— 那是个<b>加粗属性</b>，
         * 用来回答「用户到底会不会把私密日记分享出去」。分享私密内容本身是<b>允许</b>的
         * （AD-15 Rule 6：visibility 约束平台自动分发，不约束用户自己按分享键），
         * 所以这个数是产品判断，不是拦人的依据。
         */
        ContentVisibility visibility,
        Instant createdAt,
        /**
         * 正文里的 @（V1.3.0 batch-b1 Story 3.3 · AC1/AC3/AC4）。
         *
         * <p>🔴 <b>能不能点、显示什么昵称都是服务端算好的</b>（见 {@link com.tailtopia.mention.dto.MentionView}）。空表不下发。
         */
        List<com.tailtopia.mention.dto.MentionView> mentions,
        /**
         * 打卡场所条（V1.3.2 Story 1.5 · AC5 · AD-10）：帖子关联了打卡时下发 {@code {token, name, status}}；
         * 普通帖为 null → NON_NULL 省略。{@code status} ∈ ACTIVE / UNAVAILABLE（点击是否能进场所详情）。
         */
        com.tailtopia.place.dto.CheckinPlaceView checkinPlace,
        /** 作者对外 token（V1.3.4 follow · Story 2.1）；作者已注销 / 封号（D-8）时 null（不下发）。 */
        String authorToken,
        /** 当前查看者与作者的关注关系（Story 2.1）；游客 / 本人恒 false。 */
        com.tailtopia.social.read.FollowState authorFollowState,
        /**
         * 每张图的露出位置（V1.3.4 Story 2.1 · AD-5 · FR-125），与 {@link #imageUrls()} 同序；
         * 元素为 {@code [0, 1]} 或 {@code null}（原样下发）。存量 / 无超界图为 null → 省略；App 无值即居中。
         * 与 {@code FeedItemResponse.imageCropOffsets()} 同口径。
         */
        List<Double> imageCropOffsets,
        /**
         * 作者等级展示（V1.4.0 ① Story 3.1 · AD-14）：等级 / 段位（{@code GrowthTier} 名）/ 生效头像框（无框省略）。
         * 作者已注销 / 已停用 / 不展示等级时三者均为 null（NON_NULL 省略）。与 {@code FeedItemResponse} 同口径。
         */
        Integer authorLevel,
        String authorTier,
        String authorFrame,
        /**
         * 关联宠物（V1.4.0 multi-pet Story 3.1 · AC8，<b>末尾追加</b>，合并时排在 ① 的作者等级三字段之后）：只有 Diary 且至少关联 1 只宠物时下发，
         * 按「第一只」口径（宠物创建顺序）排序；Moment / Tips / 无关联 → null（NON_NULL 省略键）。
         * 只读宠物照常列出；已删宠物因级联已不在关联表。Feed 卡片不加（决策日志 F6）。
         */
        List<ContentPetView> pets,
        /**
         * Diary 礼物区（V1.4.0 gift Story 2.1 · AC5 · AD-10）：这条 Diary 关联的宠物各自收到的礼物汇总 + 墙前 3 行。
         * 只有 {@code GROWTH_MOMENT} 且经 {@code GiftPetDirectory.petsOfPost} 取到宠物时下发；Moment / Tips /
         * 无关联宠物（含私密 / 审核中 / 举报隐藏的 Diary）为 null → NON_NULL 省略，App 不渲染礼物区。
         * 宠物有关联但 0 礼物 → 照常一项（数值 0、{@code wallPreview} 空表）。
         * 字段顺序 {@code …, pets, giftPets}（gift Story 4.1 · AC3.2：两列表并存，不并进 {@code ContentPetView}）。
         */
        List<com.tailtopia.gift.web.dto.GiftPetSummaryView> giftPets,
        /** 当前用户是否已收藏（V1.4.0 ④ Story 2.1 · AD-10）。游客恒 false；<b>恒下发</b>。四线合并后排在 {@code giftPets} 之后（push-collect 3.1 · AC3.2）。 */
        boolean collected,
        /**
         * 收藏数（V1.4.0 ④ Story 2.1 · AD-10 · PRD AC10）：<b>仅作者本人看时</b>下发，不计作者自己；
         * 非作者为 null → NON_NULL 省略该键（收藏数不对外展示）。
         */
        Long collectCount) {

    /**
     * @param imageSizes 已由 {@code ImageSizeResolver.alignForRead} 对齐到 {@code imageUrls} 长度的尺寸列；
     *                   无图时为 {@code null}。**不要在这里现场取 {@code p.getImageSizes()}** ——
     *                   那是未对齐的原始列，存量内容整列为 null，下标会对不上（Story 2.1 · AC1/AC2）。
     */
    public static ContentDetailResponse of(ContentPost p, AuthorView author, long likeCount,
            long commentCount, boolean liked, boolean isAuthor,
            List<ContentTagView> decorationTags,
            List<com.tailtopia.content.domain.ImageSize> imageSizes,
            List<com.tailtopia.mention.dto.MentionView> mentions) {
        return of(p, author, likeCount, commentCount, liked, isAuthor, decorationTags, imageSizes, mentions,
                null, null, null, null, null, false, null);
    }

    /** 带打卡场所条的工厂（V1.3.2 Story 1.5）；{@code checkinPlace} 为 null = 普通帖（省略该键）。不带关联宠物 / 礼物区 / 收藏态。 */
    public static ContentDetailResponse of(ContentPost p, AuthorView author, long likeCount,
            long commentCount, boolean liked, boolean isAuthor,
            List<ContentTagView> decorationTags,
            List<com.tailtopia.content.domain.ImageSize> imageSizes,
            List<com.tailtopia.mention.dto.MentionView> mentions,
            com.tailtopia.place.dto.CheckinPlaceView checkinPlace,
            String authorToken,
            com.tailtopia.social.read.FollowState authorFollowState) {
        return of(p, author, likeCount, commentCount, liked, isAuthor, decorationTags, imageSizes, mentions,
                checkinPlace, authorToken, authorFollowState, null, null, false, null);
    }

    /**
     * 完整工厂（V1.4.0 multi-pet Story 3.1 + gift Story 2.1 + ④ Story 2.1）。
     * {@code pets} 为 null / 空，或帖子不是 Diary → 省略该键；{@code giftPets} 为 null 或空表 = 不下发礼物区；
     * {@code collectCount} 为 null = 非作者（省略该键）。
     */
    public static ContentDetailResponse of(ContentPost p, AuthorView author, long likeCount,
            long commentCount, boolean liked, boolean isAuthor,
            List<ContentTagView> decorationTags,
            List<com.tailtopia.content.domain.ImageSize> imageSizes,
            List<com.tailtopia.mention.dto.MentionView> mentions,
            com.tailtopia.place.dto.CheckinPlaceView checkinPlace,
            String authorToken,
            com.tailtopia.social.read.FollowState authorFollowState,
            List<ContentPetView> pets,
            List<com.tailtopia.gift.web.dto.GiftPetSummaryView> giftPets,
            boolean collected,
            Long collectCount) {
        List<ContentPetView> linkedPets = p.getType() == ContentType.GROWTH_MOMENT
                && pets != null && !pets.isEmpty() ? List.copyOf(pets) : null;
        return new ContentDetailResponse(
                p.getId(), p.getAuthorId(), author.nickname(), author.avatarUrl(),
                author.deleted(), author.tags().isEmpty() ? null : author.tags(),
                (decorationTags == null || decorationTags.isEmpty()) ? null : decorationTags,
                p.getType(), p.getText(), p.getImageUrls(), imageSizes,
                likeCount, commentCount, liked, isAuthor, p.getVisibility(),
                p.getCreatedAt(), mentions, checkinPlace,
                author.deleted() ? null : authorToken, authorFollowState, p.getImageCropOffsets(),
                author.badgeLevel(), author.badgeTier(), author.badgeFrame(), linkedPets,
                (giftPets == null || giftPets.isEmpty()) ? null : giftPets,
                collected, collectCount);
    }
}
