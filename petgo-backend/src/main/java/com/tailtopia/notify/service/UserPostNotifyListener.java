package com.tailtopia.notify.service;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.notify.port.NotificationPetNames;
import com.tailtopia.social.read.FollowReader;
import com.tailtopia.social.read.PetFollowReader;
import com.tailtopia.social.read.UserHideRelationReader;
import com.tailtopia.social.service.AutoFollowTargets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 关注的人发新公开帖提醒（V1.5.0 onboarding-recall Story 5.1 · FR-180 / FR-181 · OR-13 / OR-15）：消费
 * {@link ContentPublishedEvent}，给作者的关注者发 {@code USER_POST_PUBLISHED}。发帖与人工审核通过补发都会触发。
 *
 * <p>只处理 {@code PUBLIC}（Diary / Moment / Tips 都算）。<b>不触发的作者</b>：官方号（{@code isOfficialAccount}）、
 * 虚拟号（{@code isVirtual}）、自动关注目标（{@link AutoFollowTargets}，env 配置）、非 {@code isActive}。
 * 收件人 = 作者全部关注者（不分 source），逐个排除：作者本人、非 {@code isActive}、隐藏了作者（{@code holdersHiding}）、
 * 被作者拉黑（{@code blockedByHolder}）—— 与 {@link PetDiaryNotifyListener} 同口径。
 * <b>同帖去重（FR-181）</b>：追更了帖子里任一宠物的人（{@code petFollows.followersOfAny(petIds)} 的 key）整体跳过，只走
 * {@code PET_DIARY_POSTED}——即使那边因「今天已提醒过」没发也不改发本类。
 *
 * <p>回滚开关 {@code petgo.follow-update-push.enabled}（默认 true，env {@code FOLLOW_UPDATE_PUSH_ENABLED}）为 false →
 * 整条 no-op（不写通知、不推）；{@code PET_DIARY_POSTED} 不受影响。
 *
 * <p>分批 {@value #BATCH_SIZE}（每批一次有效性、一次隐藏、一次拉黑查询）；作者昵称与第一只宠物名各取一次。
 * 🔴 {@code @Async} 线程里没有事务（刻意不开外层事务）：每位收件人调<b>另一个 bean</b> 上的 {@code REQUIRES_NEW}
 * {@link UserPostNotifyWriter#write}，单人异常只 warn 并继续。日志只记 id 与异常类名。
 */
@Component
public class UserPostNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(UserPostNotifyListener.class);

    static final int BATCH_SIZE = 500;

    private final FollowReader follows;
    private final PetFollowReader petFollows;
    private final UserHideRelationReader hideRelations;
    private final AccountQueryService accounts;
    private final AutoFollowTargets autoFollowTargets;
    private final UserPostNotifyWriter writer;
    private final ObjectProvider<NotificationPetNames> petNames;
    private final boolean enabled;

    public UserPostNotifyListener(FollowReader follows, PetFollowReader petFollows,
            UserHideRelationReader hideRelations, AccountQueryService accounts, AutoFollowTargets autoFollowTargets,
            UserPostNotifyWriter writer, ObjectProvider<NotificationPetNames> petNames,
            @Value("${petgo.follow-update-push.enabled:true}") boolean enabled) {
        this.follows = follows;
        this.petFollows = petFollows;
        this.hideRelations = hideRelations;
        this.accounts = accounts;
        this.autoFollowTargets = autoFollowTargets;
        this.writer = writer;
        this.petNames = petNames;
        this.enabled = enabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentPublished(ContentPublishedEvent e) {
        if (!enabled || e.visibility() != ContentVisibility.PUBLIC) {
            return;
        }
        long author = e.authorId();
        if (autoFollowTargets.contains(author) || !accounts.isActive(author) || accounts.isVirtual(author)
                || accounts.isOfficialAccount(author)) {
            return;
        }
        Set<Long> candidates = new LinkedHashSet<>(follows.followerIdsOf(author));
        candidates.remove(author);
        if (!candidates.isEmpty() && !e.petIds().isEmpty()) {
            // FR-181：追更了本帖任一宠物的人只走 PET_DIARY_POSTED
            candidates.removeAll(petFollows.followersOfAny(e.petIds()).keySet());
        }
        if (candidates.isEmpty()) {
            return;
        }
        String nickname = nicknameOf(author);
        boolean isDiary = e.type() == ContentType.GROWTH_MOMENT;
        String petName = isDiary && !e.petIds().isEmpty() ? petNameOf(e.petIds().get(0)) : null;
        List<Long> recipients = new ArrayList<>(candidates);
        for (int from = 0; from < recipients.size(); from += BATCH_SIZE) {
            List<Long> batch = recipients.subList(from, Math.min(from + BATCH_SIZE, recipients.size()));
            Set<Long> active = accounts.activeIdsAmong(batch);
            Set<Long> hidesAuthor = hideRelations.holdersHiding(author, batch);
            Set<Long> blockedByAuthor = hideRelations.blockedByHolder(author, batch);
            for (Long f : batch) {
                if (!active.contains(f) || hidesAuthor.contains(f) || blockedByAuthor.contains(f)) {
                    continue;
                }
                try {
                    writer.write(f, e.postId(), author, nickname, petName, isDiary);
                } catch (RuntimeException ex) {
                    log.warn("关注更新提醒失败 follower={} post={} cause={}", f, e.postId(),
                            ex.getClass().getSimpleName());
                }
            }
        }
    }

    /** 作者昵称（通知触发人投影同口径：nickname → displayName）；取不到 → 空串。 */
    private String nicknameOf(long author) {
        try {
            ActorView v = accounts.findActorViews(List.of(author)).get(author);
            return v == null || v.nickname() == null ? "" : v.nickname();
        } catch (RuntimeException ex) {
            log.warn("关注更新取作者昵称失败 author={} cause={}", author, ex.getClass().getSimpleName());
            return "";
        }
    }

    /** 第一只关联宠物名；端口缺失或异常 → null（按 post 文案）。 */
    private String petNameOf(long petId) {
        NotificationPetNames port = petNames.getIfAvailable();
        if (port == null) {
            return null;
        }
        try {
            Map<Long, String> m = port.namesOf(List.of(petId));
            return m == null ? null : m.get(petId);
        } catch (RuntimeException ex) {
            log.warn("关注更新取宠物名失败（按无宠物名渲染）cause={}", ex.getClass().getSimpleName());
            return null;
        }
    }
}
