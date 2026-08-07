package com.tailtopia.consult.dto;

import com.tailtopia.consult.domain.ClosedReason;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.RatingPromptState;
import java.time.Instant;

/**
 * 咨询会话视图（Story 5.3）。
 *
 * <p>{@code timedOut}/{@code waitingElapsedSeconds} 由服务端据 {@code waitingStartedAt} 计算，
 * 供前端轮询判断是否弹「继续等待 / 先用 AI」（超时<b>不迁移状态</b>，仍 WAITING）。
 * {@code alreadyActive}=发起时已有占用态会话（前端据此显示「查看进行中 →」跳转）。
 *
 * <p>{@code rated}=本次会话是否已提交评分。前端据此<b>关闭评分入口</b>，避免对已评分会话再次评分
 * （后端 {@code submitRating} 已 409 兜底，但 closedReason 仍可能为 UNRATED——补评分只清补弹标记、
 * 不改 closedReason，故前端不能只看 closedReason 判断已评分）。
 *
 * <p><b>{@code vetDisplayName}/{@code vetAvatarUrl}/{@code vetOnline}（2026-08-07 补）</b>：
 * 用户侧会话页顶栏要显示「我在跟谁聊」。改前这三样在 App 里是**写死的占位**
 * （`drh. Dewi Santoso` / 首字母 D / 恒亮的在线点），不管谁接单都显示同一个人 ——
 * 而该名字恰好是 staging 上真实存在的兽医账号，于是看起来像「串号」，排查成本很高。
 *
 * <p>三者均**可空**：WAITING（尚无兽医）为 null；富化失败也降级为 null 而非报错
 * （见 {@code ConsultSessionService#vetPeerOf}）。前端取不到时必须回落到**中性文案**，
 * 不得再填任何具体人名。
 */
public record ConsultSessionResponse(
        long id,
        String status,
        String source,
        Long vetId,
        long waitingElapsedSeconds,
        boolean timedOut,
        boolean alreadyActive,
        String imConversationId,
        String closedReason,
        String interruptedReason,
        boolean rated,
        Instant suspendDeadlineAt,
        String vetDisplayName,
        String vetAvatarUrl,
        Boolean vetOnline) {

    /** 不带兽医身份的基础视图（写路径 / 尚无兽医的 WAITING 用）。 */
    public static ConsultSessionResponse of(ConsultSession s, long timeoutSeconds, boolean alreadyActive) {
        return of(s, timeoutSeconds, alreadyActive, VetPeer.UNKNOWN);
    }

    /**
     * 带兽医身份的视图（读路径用）。
     *
     * <p>⚠️ 只在**读路径**富化：写路径（发起 / 取消 / 评分 / 逃生）的事务已提交，再挂一次跨模块
     * 查询，一旦失败就会把「已成功的写」翻成 500 —— 兽医侧 {@code VetConsultController} 早就
     * 踩过这个坑并留了同款告诫，此处沿用同一口径。
     */
    public static ConsultSessionResponse of(ConsultSession s, long timeoutSeconds, boolean alreadyActive,
            VetPeer vetPeer) {
        // null 按「没有对端信息」处理：顶栏身份不值得为一个 NPE 把会话轮询打成 500。
        VetPeer peer = vetPeer == null ? VetPeer.UNKNOWN : vetPeer;
        long elapsed = s.getWaitingStartedAt() == null
                ? 0L
                : Math.max(0L, (System.currentTimeMillis() - s.getWaitingStartedAt().toEpochMilli()) / 1000L);
        return new ConsultSessionResponse(
                s.getId(),
                s.getStatus().name(),
                s.getSource().name(),
                s.getVetId(),
                elapsed,
                s.isTimedOut(timeoutSeconds),
                alreadyActive,
                s.getImConversationId(),
                s.getClosedReason() == null ? null : s.getClosedReason().name(),
                s.getInterruptedReason() == null ? null : s.getInterruptedReason().name(),
                isRated(s),
                s.getSuspendDeadlineAt(), // Story 3.8：非空=封禁挂起中，前端显逃生入口 + 倒计时
                peer.displayName(),
                peer.avatarUrl(),
                peer.online());
    }

    /**
     * 会话对端（兽医）身份快照。三者可空 —— 无兽医 / 富化失败均为 [UNKNOWN]。
     *
     * <p>不含诊所名：后端没有这个字段。改前 App 顶栏写死了「Klinik Hewan Sehat」，
     * 那是原型占位，不是任何真实数据；本次一并删除，**不要**为了填满这一行再造一个假值。
     */
    public record VetPeer(String displayName, String avatarUrl, Boolean online) {

        public static final VetPeer UNKNOWN = new VetPeer(null, null, null);
    }

    /**
     * 是否已评分（纯由会话终态推导，无需查评分表）：
     * RATED 关闭即已评分；UNRATED 关闭下补弹标记被清回 NONE 仅发生在「补评分成功」后
     * （超时未评为 PENDING/PROMPTED）——故 UNRATED+NONE 亦视为已评分。
     */
    private static boolean isRated(ConsultSession s) {
        ClosedReason reason = s.getClosedReason();
        if (reason == ClosedReason.RATED) {
            return true;
        }
        return reason == ClosedReason.UNRATED && s.getRatingPromptState() == RatingPromptState.NONE;
    }
}
