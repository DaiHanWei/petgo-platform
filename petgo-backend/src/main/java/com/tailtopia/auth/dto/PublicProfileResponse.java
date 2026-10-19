package com.tailtopia.auth.dto;

import java.time.Instant;
import java.util.List;

/**
 * 公开主页投影（V1.3.0 batch-b1 Story 2.1 · FR-118）。Jackson NON_NULL。
 *
 * <h2>它取代了迷你卡（{@link MiniProfileResponse}）的展示职责</h2>
 * 迷你卡是一张"什么都点不动的小卡片"；本 story 之后每一处头像入口**直接进完整主页**。
 * <p>⚠️ 字段**不是照抄迷你卡**：这里多了 {@code joinedAt} 与 {@code likeCount}（本批次新补的两个）
 * 与 {@code self}（同页两视角，Story 2.4 用它切换）。
 *
 * <h2>🔴 注销用户：一个身份字段都不下发（AC5 / NFR-8）</h2>
 * 昵称 / 头像 / 签名 / 标签全部为 null 或空 —— 不是"给个默认头像"，是**根本不给**。
 *
 * @param nickname    昵称（注销时省略）
 * @param avatarUrl   头像（注销时省略）
 * @param signature   个性签名（既有字段，直接复用；未设置 / 注销时省略）
 * @param tags        运营标签（最多 3 个；注销时空表 → 省略）
 * @param joinedAt    加入时间（{@code users.created_at}）——**本批次新补的字段**。
 *                    注销时省略（那也是身份信息的一部分）
 * @param postCount   已发布（未软删）**PUBLIC** 内容数。⚠️ 复用既有统计，**不要重新实现**；
 *                    ⚠️ 只数 PUBLIC —— 否则这个数与下方网格对不上，而那个差值
 *                    就是「这人有几篇私密内容」（V1.3.0 Story 2.2 改口，见
 *                    {@code ContentService#countPublicPostsByAuthor}）
 * @param likeCount   获赞总数（该用户全部 PUBLIC 内容被赞数之和）——
 *                    **本批次新补的第二个字段**（Story 2.2）。口径与 {@code postCount} 同源
 * @param self        是不是本人视角 —— 前端据此切换两种视角（Story 2.4）。
 *                    🔴 **服务端算给它**：让客户端拿 id 自己比，两边口径迟早会漂
 * @param isDeactivated 是否已注销。⚠️ 名字里的 {@code is} 前缀是**照抄
 *                    {@link MiniProfileResponse#isDeactivated}**（App 侧同一套解析口径），
 *                    不是笔误；record 里也不能叫 {@code deactivated} —— 会和下面那个
 *                    静态工厂方法撞名（访问器与工厂返回类型不同，编译期直接拒）
 * @param reported    当前查看者<b>是否举报过</b>此人。
 *                    <b>⚠️ 必须是装箱 {@code Boolean} 且游客时为 null</b> ——
 *                    全局 Jackson {@code NON_NULL} 会把 null 字段整个键省略掉，
 *                    游客拿到的 key 集合因此一字未变（同 {@link MiniProfileResponse#reported} 的理由）。
 *                    写成 primitive 就永远出现在 JSON 里，当场破坏游客契约
 * @param userToken   对外不可枚举用户 token（V1.3.4 follow D-2 / AD-6，关注接口用）；注销 / 封号时省略
 * @param followingCount 目标关注的人数（冗余列直读）。⚠️ <b>被拉黑方视角也给真实值</b>（PRD「拉黑与关注」），
 *                    与上面两个被伪装归零的计数不同
 * @param followerCount  目标的粉丝数（同上）
 * @param iFollow     我是否关注了对方。⚠️ 有意用 primitive：游客 / 本人 / 兽医恒 {@code false} 且键恒在 ——
 *                    App 要靠两布尔恒有值决定按钮三态（与 {@code reported} 的装箱有意不同）
 * @param followsMe   对方是否关注了我（同上）
 * @param official    是否官方内容账号（{@code role = ADMIN} 的作者 shim）。<b>只在 true 时下发</b>（装箱，平时 null → NON_NULL 省略，
 *                    普通用户与游客的 key 集合不变）。2026-10-13 决定：官方号主页不显示「···」（不可拉黑 / 举报）
 * @param level       等级展示（V1.4.0 ① Story 3.1 · AD-14）；停用 / 无等级时省略。{@link #deactivated()} 恒省略
 * @param tier        段位（{@code GrowthTier} 名）
 * @param frame       生效头像框（BASIC / UPGRADED / ANIMATED），无框省略
 */
public record PublicProfileResponse(
        String nickname,
        String avatarUrl,
        String signature,
        List<UserTagView> tags,
        Instant joinedAt,
        long postCount,
        long likeCount,
        boolean self,
        boolean isDeactivated,
        Boolean reported,
        String userToken,
        long followingCount,
        long followerCount,
        boolean iFollow,
        boolean followsMe,
        Boolean official,
        Integer level,
        String tier,
        String frame) {

    /**
     * 已注销：不暴露任何身份信息（AC5 / NFR-8）。
     *
     * <p>⚠️ 连 {@code joinedAt} 都不给 —— "这个账号是 2024 年注册的"同样是身份信息，
     * 而注销的含义是这个人在站内不再可识别。
     */
    public static PublicProfileResponse deactivated() {
        return new PublicProfileResponse(null, null, null, List.of(), null, 0, 0, false, true, null,
                null, 0, 0, false, false, null, null, null, null);
    }

    /**
     * @param reported 登录者传 true/false；<b>游客传 null</b>（键会被 NON_NULL 省略）。
     * @param follow   当前查看者与目标的关注关系（游客 / 本人传 {@code FollowState.NONE}）
     */
    public static PublicProfileResponse of(AuthorView author, String signature, Instant joinedAt,
            long postCount, long likeCount, boolean self, Boolean reported,
            String userToken, long followingCount, long followerCount,
            com.tailtopia.social.read.FollowState follow, boolean official) {
        return new PublicProfileResponse(
                author.nickname(),
                author.avatarUrl(),
                signature,
                author.tags().isEmpty() ? null : author.tags(),
                joinedAt,
                postCount,
                likeCount,
                self,
                false,
                reported,
                userToken,
                followingCount,
                followerCount,
                follow.iFollow(),
                follow.followsMe(),
                official ? Boolean.TRUE : null,
                author.badgeLevel(),
                author.badgeTier(),
                author.badgeFrame());
    }
}
