package com.tailtopia.notify.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.event.ContentPublishedEvent;
import com.tailtopia.notify.port.NotificationPetNames;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.social.read.PetFollowReader;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 追更宠物有新 Diary 提醒（V1.4.2 social-topic · Story 1.2 · AC6 · D-5 / D-10 / P-3）：消费 {@link ContentPublishedEvent}，
 * 给追更了帖子关联宠物的人发 {@code PET_DIARY_POSTED}。发帖与人工审核通过补发都会触发；运营种子帖 {@code petIds} 恒空，不触发。
 *
 * <p>只处理 {@code GROWTH_MOMENT + PUBLIC + petIds 非空}；作者非 {@code isActive} 整帖不发。收件人逐个排除：
 * <ul>
 *   <li>作者本人（双保险）；</li>
 *   <li>追更者非 {@code isActive}（注销 / {@code DEACTIVATED}，{@code activeIdsAmong} 批量）；</li>
 *   <li>追更者隐藏了作者（拉黑与举报隐藏都算，与关注流「作者不在我的隐藏名单」同口径，NFR1）；</li>
 *   <li>作者 <b>BLOCK</b> 了追更者（只认 BLOCK，PRD FR-153 AC9 / R-1，与 1.3 关注流追更一路同口径）。</li>
 * </ul>
 * <b>已关注作者本人的追更者照常收</b>（这是与关注人的主要区别）。
 *
 * <p>每个追更者<b>只收 1 条</b>：按 {@code petIds} 顺序（宠物创建顺序）遍历他命中的宠物，第一只
 * {@link PetDiaryNotifyWriter#notifyIfFirstToday} 成功（今天 WIB 还没提醒过、且通知写入成功）的即用于本条文案；
 * 全部冲突 → 不发。某只写通知失败 → 该事务回滚（日志不残留），warn 后继续尝试下一只。
 *
 * <p>分批：收件人按 {@value #BATCH_SIZE} 一批（每批一次有效性、一次隐藏、一次拉黑查询）；每条通知各自
 * {@code REQUIRES_NEW}，单人异常只 warn 并继续下一位。
 *
 * <p>🔴 {@code @Async} 线程里没有事务（本方法<b>刻意不开</b>外层事务 —— 扇出可能很长，不占着一个连接空转）：
 * 所有写库都调<b>另一个 bean</b> 上的 {@code REQUIRES_NEW} 方法 {@link PetDiaryNotifyWriter#notifyIfFirstToday}
 * （插日志 + 写通知同一事务），禁止 {@code this.xxx()} 自调用事务方法。
 * 推送全局限速由 IM 客户端内的 {@code PushRateLimiter} 统一生效，不另加。
 * 日志只记内部 id 与异常类名，不记宠物名 / 昵称 / 文案。
 */
@Component
public class PetDiaryNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(PetDiaryNotifyListener.class);

    /** 扇出分批大小（AC6.8）。 */
    static final int BATCH_SIZE = 500;

    private final PetFollowReader petFollows;
    private final UserHideRelationReader hideRelations;
    private final AccountQueryService accounts;
    private final PetDiaryNotifyWriter writer;
    private final ObjectProvider<NotificationPetNames> petNames;
    private final Clock clock;

    @Autowired
    public PetDiaryNotifyListener(PetFollowReader petFollows, UserHideRelationReader hideRelations,
            AccountQueryService accounts, PetDiaryNotifyWriter writer, ObjectProvider<NotificationPetNames> petNames) {
        this(petFollows, hideRelations, accounts, writer, petNames, Clock.systemUTC());
    }

    PetDiaryNotifyListener(PetFollowReader petFollows, UserHideRelationReader hideRelations,
            AccountQueryService accounts, PetDiaryNotifyWriter writer, ObjectProvider<NotificationPetNames> petNames,
            Clock clock) {
        this.petFollows = petFollows;
        this.hideRelations = hideRelations;
        this.accounts = accounts;
        this.writer = writer;
        this.petNames = petNames;
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentPublished(ContentPublishedEvent e) {
        if (e.type() != ContentType.GROWTH_MOMENT || e.visibility() != ContentVisibility.PUBLIC
                || e.petIds().isEmpty()) {
            return;
        }
        long author = e.authorId();
        if (!accounts.isActive(author)) {
            return;
        }
        Map<Long, List<Long>> petsByFollower = new java.util.LinkedHashMap<>(petFollows.followersOfAny(e.petIds()));
        petsByFollower.remove(author);
        if (petsByFollower.isEmpty()) {
            return;
        }
        Map<Long, String> names = namesOf(e.petIds());
        LocalDate today = LocalDate.now(clock.withZone(ScheduleWindow.WIB));
        List<Long> followers = new ArrayList<>(petsByFollower.keySet());
        for (int from = 0; from < followers.size(); from += BATCH_SIZE) {
            List<Long> batch = followers.subList(from, Math.min(from + BATCH_SIZE, followers.size()));
            Set<Long> active = accounts.activeIdsAmong(batch);
            // 两个单向批量各一次：不能用 hiddenEitherWay —— 它会把「作者举报隐藏了追更者」也排除（R-1 只认作者 BLOCK）
            Set<Long> hidesAuthor = hideRelations.holdersHiding(author, batch);
            Set<Long> blockedByAuthor = hideRelations.blockedByHolder(author, batch);
            for (Long f : batch) {
                if (!active.contains(f) || hidesAuthor.contains(f) || blockedByAuthor.contains(f)) {
                    continue;
                }
                for (Long pet : petsByFollower.get(f)) {
                    try {
                        if (writer.notifyIfFirstToday(f, e.postId(), pet, names.get(pet), today)) {
                            break; // 一人一条
                        }
                    } catch (RuntimeException ex) {
                        // 日志与通知同事务已回滚 → 名额未占，继续尝试下一只宠物（AC6.8）
                        log.warn("追更日提醒失败 follower={} post={} pet={} cause={}", f, e.postId(), pet,
                                ex.getClass().getSimpleName());
                    }
                }
            }
        }
    }

    /** 宠物名一次批量；端口缺失或异常 → 空（按 noPet 文案）。 */
    private Map<Long, String> namesOf(List<Long> petIds) {
        NotificationPetNames port = petNames.getIfAvailable();
        if (port == null) {
            return Map.of();
        }
        try {
            Map<Long, String> m = port.namesOf(petIds);
            return m == null ? Map.of() : m;
        } catch (RuntimeException ex) {
            log.warn("追更日提醒取宠物名失败（按无宠物名渲染）cause={}", ex.getClass().getSimpleName());
            return Map.of();
        }
    }
}
