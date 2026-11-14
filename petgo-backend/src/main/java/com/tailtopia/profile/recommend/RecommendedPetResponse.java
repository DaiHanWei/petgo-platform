package com.tailtopia.profile.recommend;

import com.tailtopia.profile.domain.PetType;
import java.time.LocalDate;
import java.util.List;

/**
 * 推荐给用户看的一只别人家的宠物（V1.3.0 batch-b1 Story 4.1 · AC4）。
 *
 * <h2>🔴 两个图片字段，两个不同来源 —— 不是同一张图重复摆放</h2>
 * UI 稿 UX-DR15 专门点过这一处：
 * <table>
 *   <tr><th>位置</th><th>字段</th><th>来源</th></tr>
 *   <tr><td>卡片大图</td><td>{@link #coverImageUrl}</td>
 *       <td>该宠物<b>最近一条带配图的公开帖</b>的首图（V1.5.0 起不限 Diary，与 {@link #coverPostId} 同源）</td></tr>
 *   <tr><td>左下角小圆头像</td><td>{@link #avatarUrl}</td>
 *       <td><b>宠物档案自身</b>的头像（{@code PetProfile.avatarUrl}）</td></tr>
 * </table>
 * ⚠️ {@code avatarUrl} 恒非空（「有头像」是 AC1 的入池门槛）；
 * {@code coverImageUrl} <b>可空</b> —— 3 条公开记录全都没配图的宠物照样在池子里
 * （AC1 的门槛里没有「必须有配图」这一条），客户端按占位渲染。
 *
 * <h2>年龄交给客户端算</h2>
 * 下发 {@link #birthday} 而不是算好的岁数串 —— 与 Story 2.3 的
 * {@code PublicProfilePetResponse} 逐字同构，客户端那份 {@code pet_age.dart}
 * 是全 App 唯一的年龄文案口径（本地化也在那儿）。
 *
 * <h2>⚠️ 不下发 cardToken</h2>
 * 点击落点是 Story 2.3 的**站内**访客入口（按 petId），对外分享 token 不该给访客
 * （AD-4 / Story 2.3 的既定口径）。
 *
 * <h2>V1.5.0 onboarding-recall Story 1.1 · OR-4：大图与点赞是同一条帖子</h2>
 * {@link #coverImageUrl} 的来源改为这只宠物<b>最近一条带图公开帖</b>（不再限 Diary），
 * 与 {@link #coverPostId} / {@link #coverLikeCount} / {@link #coverLikedByViewer} <b>同源</b>；
 * 纯文字帖不当封面（四个字段一起为空 / 0 / false）。新字段只追加在末尾，旧字段语义不变。
 *
 * @param petId          宠物档案 id —— 点击落点用它（复用 Story 2.3 的站内入口，不新建通道）
 * @param name           宠物名
 * @param avatarUrl      宠物档案头像（小圆头像；恒非空）
 * @param petType        物种
 * @param birthday       生日（可空；客户端据此算年龄文案）
 * @param coverImageUrl  封面帖首图（卡片大图；<b>可空</b>）
 * @param companionDays  陪伴天数（「一起 238 天」）——与 H5 名片同一个算法
 * @param breed          品种（可空；卡片第二行「品种 · Bersama N hari」，无品种客户端回落物种）
 * @param coverPostId    封面帖 id（可空；点赞胶囊绑它，帖子详情 {@code /content/{id}} 的现有口径）
 * @param coverLikeCount 封面帖当前赞数（无封面帖为 0）
 * @param coverLikedByViewer 查看者是否已赞封面帖（游客恒 false）
 * @param match          命中哪一段（埋点用）：{@code SAME_BREED / SAME_SPECIES / GLOBAL}
 */
public record RecommendedPetResponse(
        long petId,
        String name,
        String avatarUrl,
        PetType petType,
        LocalDate birthday,
        String coverImageUrl,
        long companionDays,
        String breed,
        Long coverPostId,
        long coverLikeCount,
        boolean coverLikedByViewer,
        RecommendMatch match) {

    /**
     * 一页宠物卡（Story 4.3 · AC3 起带游标）。
     *
     * <p>🔴 {@code nextCursor} 是 base64url 不可枚举串，客户端<b>原样回传，不要解析</b>
     * （形态见 {@link PetRecommendCursor}）。
     * <p>⚠️ {@code hasMore} 与「{@code items} 是不是空的」**不等价**：一页里的宠物
     * 全被过滤掉（拉黑 / 注销 / 没头像）时会回**空 items + 有游标 + hasMore=true** ——
     * 客户端据此继续往下翻，而不是把空 items 当作到底了。
     * 判「到底」只看 {@code hasMore}。
     */
    public record Page(List<RecommendedPetResponse> items, String nextCursor, boolean hasMore) {

        /** 最后一页（没有更多了）。 */
        public static Page last(List<RecommendedPetResponse> items) {
            return new Page(items, null, false);
        }
    }
}
