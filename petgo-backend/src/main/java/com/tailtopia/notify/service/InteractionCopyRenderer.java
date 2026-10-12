package com.tailtopia.notify.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.HashSet;
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
 *   <li>已注销（{@code AuthorView.deleted}）与封号（未注销但非 ACTIVE，D-8）一律剔除；</li>
 *   <li>接收者隐藏了的人（拉黑与举报隐藏都算，T-30，{@link UserHideRelationReader#hiddenByHolder}）剔除。</li>
 * </ul>
 * 最新触发人被剔除时顺延到下一个；全部被剔除 → 空。
 *
 * <p>🔴 日志：昵称与渲染出的文案<b>一律不进日志</b>。
 */
@Component
public class InteractionCopyRenderer {

    /** 最新触发人与其余人数。 */
    public record ActorSummary(long latestActorId, String latestNickname, int others) {
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
     * 批量解析一条通知的触发人（固定 3 次查询：展示字段、有效账号、接收者隐藏集合），再交给 {@link #summarize}。
     *
     * @param actorIdsNewestFirst 本条通知全部触发人，最新在前
     */
    public Optional<ActorSummary> resolve(long recipientUserId, List<Long> actorIdsNewestFirst) {
        if (actorIdsNewestFirst.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, AuthorView> views = accounts.findAuthorViewsWithoutTags(actorIdsNewestFirst);
        Set<Long> excluded = new HashSet<>(hideRelations.hiddenByHolder(recipientUserId, actorIdsNewestFirst));
        // D-8：封号（未注销）与已注销同等处理。activeIdsAmong = 未注销 + ACTIVE。
        Set<Long> active = accounts.activeIdsAmong(actorIdsNewestFirst);
        for (Long id : actorIdsNewestFirst) {
            if (!active.contains(id)) {
                excluded.add(id);
            }
        }
        return summarize(actorIdsNewestFirst, views, excluded);
    }

    /**
     * 纯函数：取第一个「未注销、有昵称、不在 {@code excluded}」的人为最新触发人，{@code others} = 其余同样过滤后的人数。
     *
     * @param excluded 要剔除的人（接收者隐藏的 + 封号的）
     */
    public static Optional<ActorSummary> summarize(List<Long> actorIdsNewestFirst, Map<Long, AuthorView> views,
            Set<Long> excluded) {
        Long latest = null;
        String nickname = null;
        int others = 0;
        for (Long id : actorIdsNewestFirst) {
            AuthorView v = views.get(id);
            if (v == null || v.deleted() || v.nickname() == null || excluded.contains(id)) {
                continue;
            }
            if (latest == null) {
                latest = id;
                nickname = v.nickname();
            } else {
                others++;
            }
        }
        return latest == null ? Optional.empty() : Optional.of(new ActorSummary(latest, nickname, others));
    }

    /**
     * 第一行文案（推送标题 / 列表第一行）。点赞、场所评论按 {@code others == 0} 选 {@code .one} / {@code .many}；
     * 评论按 {@code variant} 选「评论了你的帖子」/「回复了你的评论」；新粉丝「关注了你」。
     *
     * @throws IllegalArgumentException 非互动类型
     */
    public String title(NotificationType type, String variant, ActorSummary s, Locale locale) {
        String name = s.latestNickname();
        // {1} 以字符串传入：MessageFormat 对数字会按语言插千分位（1.234 / 1,234）。
        String others = String.valueOf(s.others());
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

    private String msg(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, locale);
    }
}
