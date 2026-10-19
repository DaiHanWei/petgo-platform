package com.tailtopia.notify.service;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * 互动通知文案（V1.3.4 follow · Story 3.1 · FR-141.4 / AD-10 / AD-11）—— <b>推送与通知列表（Story 3.3）共用这一个类</b>，
 * 保证两边说的是同一句话。
 *
 * <p>触发人过滤（FR-141.9）：最新触发人与「and N others」都只算<b>还对外有效</b>、且<b>接收者没有隐藏</b>的人：
 * <ul>
 *   <li>已注销与封号（未注销但非 ACTIVE，D-8）一律剔除（{@link ActorView#active()}）；</li>
 *   <li>接收者隐藏了的人（拉黑与举报隐藏都算，T-30，{@link UserHideRelationReader#hiddenByHolder}）剔除。</li>
 * </ul>
 * 最新触发人被剔除时顺延到下一个；全部被剔除 → 空。
 *
 * <p>🔴 日志：昵称与渲染出的文案<b>一律不进日志</b>。
 */
@Component
public class InteractionCopyRenderer {

    /**
     * 最新可显示的触发人与其余人数。{@code total()} = 过滤后的触发人总数（含最新那一位）。
     *
     * @param latestToken 最新触发人的 {@code users.public_token}（列表的回关按钮用）
     */
    public record ActorSummary(long latestActorId, String latestNickname, String latestToken, int others) {

        public int total() {
            return others + 1;
        }
    }

    private final AccountQueryService accounts;
    private final UserHideRelationReader hideRelations;
    private final MessageSource messages;

    public InteractionCopyRenderer(AccountQueryService accounts, UserHideRelationReader hideRelations,
            MessageSource messages) {
        this.accounts = accounts;
        this.hideRelations = hideRelations;
        this.messages = messages;
    }

    /**
     * 批量解析<b>一条</b>通知的触发人（固定 2 次查询：触发人投影、接收者隐藏集合），再交给 {@link #summarize}。
     *
     * <p>⚠️ 只给推送用（一次一条）。通知<b>列表</b>（Story 3.3）不得逐行调本方法（那是 N+1，违反 AD-11）：
     * 应先对整页全部触发人各批量查一次，再逐行调纯函数 {@link #summarize}。
     *
     * @param actorIdsNewestFirst 本条通知全部触发人，最新在前
     */
    public Optional<ActorSummary> resolve(long recipientUserId, List<Long> actorIdsNewestFirst) {
        if (actorIdsNewestFirst.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, ActorView> views = accounts.findActorViews(actorIdsNewestFirst);
        Set<Long> hidden = hideRelations.hiddenByHolder(recipientUserId, actorIdsNewestFirst);
        return summarize(actorIdsNewestFirst, views, hidden);
    }

    /**
     * 纯函数：取第一个「对外有效（未注销且 ACTIVE，D-8）、有昵称、接收者没有隐藏（T-30）」的人为最新触发人，
     * {@code others} = 其余同样过滤后的人数；全部被滤掉 → 空。推送与列表共用这一份规则。
     *
     * @param views  触发人投影（缺失的 id 按不可显示处理）
     * @param hidden 接收者隐藏了的人
     */
    public static Optional<ActorSummary> summarize(List<Long> actorIdsNewestFirst, Map<Long, ActorView> views,
            Set<Long> hidden) {
        ActorView latest = null;
        int others = 0;
        for (Long id : actorIdsNewestFirst) {
            ActorView v = views.get(id);
            if (v == null || !v.active() || v.nickname() == null || hidden.contains(id)) {
                continue;
            }
            if (latest == null) {
                latest = v;
            } else {
                others++;
            }
        }
        return latest == null ? Optional.empty()
                : Optional.of(new ActorSummary(latest.userId(), latest.nickname(), latest.publicToken(), others));
    }

    /** 评论已删除 / 不可见时的第二行（FR-141.13，T-37）。 */
    public String commentDeleted(Locale locale) {
        return messages.getMessage("notify.tpl.commentDeleted", null, locale);
    }

    /**
     * 第一行文案（推送标题 / 列表第一行）。收礼无宠物名时用 noPet 文案。点赞、场所评论按 {@code others == 0} 选 {@code .one} / {@code .many}；
     * 评论按 {@code variant} 选「评论了你的帖子」/「回复了你的评论」；新粉丝「关注了你」。
     *
     * @throws IllegalArgumentException 非互动类型
     */
    public String title(NotificationType type, String variant, ActorSummary s, Locale locale) {
        return title(type, variant, s, locale, null);
    }

    /**
     * 同 {@link #title(NotificationType, String, ActorSummary, Locale)}，另带宠物名（V1.4.0 gift Story 1.2 · AC5.3）：
     * 只有 {@code PET_GIFT_RECEIVED} 用到 —— {@code others == 0} → {@code notify.tpl.giftReceived.one}（{0} 昵称、{1} 宠物名）；
     * 否则 {@code .many}（{0} 昵称、{1} 其余人数、{2} 宠物名、{3} 总人数）；{@code petName == null}（宠物已删 / 查不到）→
     * {@code .one.noPet} / {@code .many.noPet}。其余四类忽略 {@code petName}，行为不变。
     */
    public String title(NotificationType type, String variant, ActorSummary s, Locale locale, String petName) {
        return title(type, variant, s, locale, petName, null);
    }

    /**
     * 同上，另带礼物（V1.4.0 gift L2 第二轮 · PRD「XX 给你的 Mochi 送了 Tulang ×5」· 架构 P-4 用礼物名不用 emoji）：
     * 只对 {@code PET_GIFT_RECEIVED} 且单个送礼人（{@code others == 0}）生效 —— {@code giftLabel} 非空 →
     * {@code notify.tpl.giftReceived.oneGift}（{0} 昵称、{1} 宠物名、{2} 礼物）/ {@code .oneGift.noPet}（{0} 昵称、{1} 礼物）；
     * 为空（查不到 / 送了不止一种）→ 回落 {@code .one}。多人合并照稿 G1 不列礼物。{@code giftLabel} 用 {@link #giftLabel} 拼。
     */
    public String title(NotificationType type, String variant, ActorSummary s, Locale locale, String petName,
            String giftLabel) {
        String name = s.latestNickname();
        // {1} 以字符串传入：MessageFormat 对数字会按语言插千分位（1.234 / 1,234）。
        String others = String.valueOf(s.others());
        if (type == NotificationType.PET_GIFT_RECEIVED) {
            String total = String.valueOf(s.total());
            String suffix = petName == null ? ".noPet" : "";
            String pet = petName == null ? "" : petName;
            if (s.others() == 0 && giftLabel != null && !giftLabel.isBlank()) {
                return petName == null
                        ? msg("notify.tpl.giftReceived.oneGift.noPet", locale, name, giftLabel)
                        : msg("notify.tpl.giftReceived.oneGift", locale, name, pet, giftLabel);
            }
            return s.others() == 0
                    ? msg("notify.tpl.giftReceived.one" + suffix, locale, name, pet)
                    : msg("notify.tpl.giftReceived.many" + suffix, locale, name, others, pet, total);
        }
        return switch (type) {
            case CONTENT_LIKED -> s.others() == 0
                    ? msg("notify.tpl.liked.one", locale, name)
                    : msg("notify.tpl.liked.many", locale, name, others);
            case PLACE_COMMENTED -> s.others() == 0
                    ? msg("notify.tpl.placeCommented.one", locale, name)
                    : msg("notify.tpl.placeCommented.many", locale, name, others);
            case CONTENT_COMMENTED -> Notification.VARIANT_REPLY.equals(variant)
                    ? msg("notify.tpl.replied", locale, name)
                    : msg("notify.tpl.commented", locale, name);
            case USER_FOLLOWED -> msg("notify.tpl.followed", locale, name);
            default -> throw new IllegalArgumentException("not an interaction type: " + type);
        };
    }

    /**
     * 收礼推送标题（单笔文案，V1.4.0 gift Story 1.2 · AC5.3）：{@code notify.tpl.giftReceived.push}
     * （{0} 送礼人昵称、{1} 礼物、{2} 宠物名）。{@code giftLabel} 由调用方给：数量 1 时为礼物名、≥ 2 时「礼物名 ×N」。
     */
    public String giftPushTitle(String actorName, String giftLabel, String petName, Locale locale) {
        return msg("notify.tpl.giftReceived.push", locale, actorName, giftLabel, petName);
    }

    /**
     * 礼物文案片段（推送与通知列表共用一份口径，UI 稿 G1 / G4）：名称按语言取订单快照（{@code en} → EN 名；其余含 null → ID 名），
     * 数量 ≥ 2 时加「 ×N」，1 时省略。
     */
    public static String giftLabel(String nameEn, String nameId, int quantity, Locale locale) {
        boolean en = locale != null && "en".equalsIgnoreCase(locale.getLanguage());
        String name = en ? nameEn : nameId;
        return quantity >= 2 ? name + " ×" + quantity : name;
    }

    private String msg(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, locale);
    }
}
