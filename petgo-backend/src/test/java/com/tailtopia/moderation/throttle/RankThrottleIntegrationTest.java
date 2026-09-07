package com.tailtopia.moderation.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.moderation.throttle.domain.RankThrottle;
import com.tailtopia.moderation.throttle.domain.ThrottleDuration;
import com.tailtopia.moderation.throttle.domain.ThrottleScope;
import com.tailtopia.moderation.throttle.repository.RankThrottleRepository;
import com.tailtopia.moderation.throttle.service.RankThrottleService;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：限流状态模型（Story 17.1）—— 真 PostgreSQL 跑真 JPQL。
 *
 * <h2>为什么断言落在 {@link RankThrottleService#factorsFor} 而不是首页接口</h2>
 * 沿用 {@code FeedRankQueryIntegrationTest} 已经记下的教训：共享测试库里候选池有几千条，
 * 一条刚造的 0 赞内容<b>落到第 20 名之后完全正常</b>。拿首页返回值断言「被降权了」
 * 会得到一条时不时红、且红的时候与限流毫无关系的测试。
 * 系数是这一层的属性，在这一层断言既确定又直指要害。
 */
class RankThrottleIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private RankThrottleService service;

    @Autowired
    private RankThrottleRepository throttles;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private NotificationRepository notifications;

    private static final long OPERATOR = 9001L;

    private ContentPost publish(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null,
                "throttle-" + SEQ.incrementAndGet(), List.of()));
    }

    private double factor(ContentPost p, Instant now) {
        Map<Long, Double> f = service.factorsFor(
                List.of(new RankThrottleService.Target(p.getId(), p.getAuthorId())), now);
        // 🛡 AC6：没有记录时 Map 里就没有这一条 —— 引擎按缺省 1.0 处理。
        return f.getOrDefault(p.getId(), 1.0);
    }

    // ── AC6：无限流记录恒 1.0 ──────────────────────────────────────

    @Test
    void noThrottleRecordMeansFactorOne() {
        ContentPost p = publish(newUser().getId());
        Instant now = Instant.now();

        assertThat(service.factorsFor(
                List.of(new RankThrottleService.Target(p.getId(), p.getAuthorId())), now))
                .as("🛡 无记录时不该回填 1.0，而是根本不出现在 Map 里")
                .isEmpty();
        assertThat(factor(p, now)).isEqualTo(1.0);
    }

    @Test
    void emptyTargetListIsNotAQuery() {
        assertThat(service.factorsFor(List.of(), Instant.now())).isEmpty();
    }

    // ── AC1：两级粒度 ─────────────────────────────────────────────

    @Test
    void postScopeThrottleAppliesOnlyToThatPost() {
        User author = newUser();
        ContentPost hit = publish(author.getId());
        ContentPost sibling = publish(author.getId());
        Instant now = Instant.now();

        service.throttlePost(hit.getId(), ThrottleDuration.DAYS_7, now, OPERATOR, null, "试");

        assertThat(factor(hit, now)).isLessThan(1.0);
        assertThat(factor(sibling, now))
                .as("单条限流不该波及同作者的其它内容")
                .isEqualTo(1.0);
    }

    /**
     * 🔴 AC1 的要害：账号级限流期内**新发布**的内容也受限。
     *
     * <p>否则处置形同虚设 —— 被限流的人只要重新发一遍就绕过了。
     * 这里靠「先限流、后发帖」的时序把那个绕法钉死。
     */
    @Test
    void accountScopeThrottleCoversPostsCreatedAfterIt() {
        User author = newUser();
        ContentPost before = publish(author.getId());
        Instant now = Instant.now();

        service.throttleAccount(author.getId(), ThrottleDuration.DAYS_30, now, OPERATOR, null, "试");

        ContentPost after = publish(author.getId()); // 限流之后才发的
        assertThat(factor(before, now)).as("存量内容").isLessThan(1.0);
        assertThat(factor(after, now)).as("🔴 限流期内新发的").isLessThan(1.0);
    }

    @Test
    void accountScopeThrottleDoesNotLeakToOtherAuthors() {
        User throttled = newUser();
        User innocent = newUser();
        ContentPost mine = publish(throttled.getId());
        ContentPost theirs = publish(innocent.getId());
        Instant now = Instant.now();

        service.throttleAccount(throttled.getId(), ThrottleDuration.DAYS_7, now, OPERATOR, null, "试");

        assertThat(factor(mine, now)).isLessThan(1.0);
        assertThat(factor(theirs, now)).isEqualTo(1.0);
    }

    /** 内容级与账号级同时命中时取一份系数，不相乘 —— 0.2 相乘会变成 0.04，那是调不出来的强度。 */
    @Test
    void postAndAccountThrottlesDoNotCompound() {
        User author = newUser();
        ContentPost p = publish(author.getId());
        Instant now = Instant.now();

        service.throttleAccount(author.getId(), ThrottleDuration.DAYS_7, now, OPERATOR, null, "试");
        double accountOnly = factor(p, now);
        service.throttlePost(p.getId(), ThrottleDuration.DAYS_7, now, OPERATOR, null, "试");

        assertThat(factor(p, now)).isEqualTo(accountOnly);
    }

    // ── 🛡 AC2：降权不是下架 ───────────────────────────────────────

    /**
     * 🛡 限流后内容仍是 {@code PUBLISHED}，且没被写任何"下架"痕迹。
     *
     * <p>🔴 审核相关的既有代码路径都是「改状态」，顺手复用就会把降权做成下架。
     */
    @Test
    void throttledPostStaysPublishedAndUndeleted() {
        User author = newUser();
        ContentPost p = publish(author.getId());
        Instant now = Instant.now();

        service.throttlePost(p.getId(), ThrottleDuration.PERMANENT, now, OPERATOR, null, "试");
        service.throttleAccount(author.getId(), ThrottleDuration.PERMANENT, now, OPERATOR, null, "试");

        ContentPost reloaded = posts.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(reloaded.getDeletedAt()).as("🛡 不该写 deleted_at").isNull();
        assertThat(reloaded.getVisibility()).isEqualTo(p.getVisibility());
    }

    /** 🛡 被限流的内容仍在候选池里 —— 限流是降权，不是从池子里剔除。 */
    @Test
    void throttledPostIsStillInTheCandidatePool() {
        User author = newUser();
        User viewer = newUser();
        ContentPost p = publish(author.getId());
        Instant now = Instant.now();

        service.throttleAccount(author.getId(), ThrottleDuration.PERMANENT, now, OPERATOR, null, "试");

        assertThat(posts.findRankableByIds(List.of(p.getId()), true, viewer.getId()))
                .as("🛡 限流被做成了过滤 —— 那是下架（AC2/AC7）")
                .isNotEmpty();
    }

    // ── 🛡 AC3：不通知 ───────────────────────────────────────────

    /** 🛡 限流不产生任何通知。⚠️「警告」处置是要告知的，别复用它那条路径。 */
    @Test
    void throttlingProducesNoNotification() {
        User author = newUser();
        ContentPost p = publish(author.getId());
        long before = notifications.countByRecipientUserIdAndReadIsFalse(author.getId());
        Instant now = Instant.now();

        service.throttlePost(p.getId(), ThrottleDuration.DAYS_7, now, OPERATOR, null, "试");
        service.throttleAccount(author.getId(), ThrottleDuration.DAYS_30, now, OPERATOR, null, "试");

        assertThat(notifications.countByRecipientUserIdAndReadIsFalse(author.getId()))
                .as("🛡 告知会引导删帖重发这类对抗行为")
                .isEqualTo(before);
    }

    // ── AC4：期限与解除 ──────────────────────────────────────────

    @Test
    void sevenAndThirtyDayDurationsSetTheMatchingExpiry() {
        Instant now = Instant.now();
        RankThrottle week = service.throttlePost(publish(newUser().getId()).getId(),
                ThrottleDuration.DAYS_7, now, OPERATOR, null, "试");
        RankThrottle month = service.throttlePost(publish(newUser().getId()).getId(),
                ThrottleDuration.DAYS_30, now, OPERATOR, null, "试");

        assertThat(week.getExpiresAt()).isEqualTo(now.plus(Duration.ofDays(7)));
        assertThat(month.getExpiresAt()).isEqualTo(now.plus(Duration.ofDays(30)));
    }

    /** 永久限流没有到期时刻（与建表 CHECK「永久 ⇔ expires_at 为空」同一口径）。 */
    @Test
    void permanentThrottleHasNoExpiry() {
        RankThrottle t = service.throttlePost(publish(newUser().getId()).getId(),
                ThrottleDuration.PERMANENT, Instant.now(), OPERATOR, null, "试");
        assertThat(t.getExpiresAt()).isNull();
    }

    /**
     * 🛡 到期后系数**立即**回 1.0，不残留。
     *
     * <p>这里用「把时钟拨到到期之后」来验，而不是等定时任务 ——
     * 因为到期解除是由 {@code isActiveAt} 的判定构成的，不存在扫描延迟这回事。
     * 换成扫描器实现的话，这条断言会在「已到期但还没被扫到」的那段窗口里红。
     */
    @Test
    void factorReturnsToOneAfterExpiry() {
        User author = newUser();
        ContentPost p = publish(author.getId());
        Instant now = Instant.now();

        service.throttleAccount(author.getId(), ThrottleDuration.DAYS_7, now, OPERATOR, null, "试");
        assertThat(factor(p, now)).isLessThan(1.0);

        Instant afterExpiry = now.plus(Duration.ofDays(7)).plusSeconds(1);
        assertThat(factor(p, afterExpiry)).isEqualTo(1.0);
    }

    /** 🛡 手动提前解除后系数立即回 1.0。 */
    @Test
    void factorReturnsToOneAfterManualLift() {
        User author = newUser();
        ContentPost p = publish(author.getId());
        Instant now = Instant.now();

        RankThrottle t = service.throttlePost(p.getId(), ThrottleDuration.PERMANENT, now,
                OPERATOR, null, "试");
        assertThat(factor(p, now)).isLessThan(1.0);

        assertThat(service.lift(t.getId(), now, OPERATOR)).isTrue();
        assertThat(factor(p, now)).isEqualTo(1.0);
    }

    /** 已解除的再解除一次返回 false，且不覆盖首次解除的时刻与操作人。 */
    @Test
    void liftingTwiceIsANoOp() {
        Instant now = Instant.now();
        RankThrottle t = service.throttlePost(publish(newUser().getId()).getId(),
                ThrottleDuration.DAYS_30, now, OPERATOR, null, "试");
        assertThat(service.lift(t.getId(), now, OPERATOR)).isTrue();

        RankThrottle reloaded = throttles.findById(t.getId()).orElseThrow();
        Instant firstLift = reloaded.getLiftedAt();

        assertThat(service.lift(t.getId(), now.plusSeconds(60), 9002L)).isFalse();
        assertThat(throttles.findById(t.getId()).orElseThrow().getLiftedAt()).isEqualTo(firstLift);
        assertThat(throttles.findById(t.getId()).orElseThrow().getLiftedBy()).isEqualTo(OPERATOR);
    }

    /** 同一目标再次限流时，先解除旧的生效记录 —— 不留两行同时生效。 */
    @Test
    void rethrottlingLiftsThePreviousActiveRecord() {
        User author = newUser();
        Instant now = Instant.now();
        RankThrottle first = service.throttleAccount(author.getId(), ThrottleDuration.DAYS_7, now,
                OPERATOR, null, "试");
        RankThrottle second = service.throttleAccount(author.getId(), ThrottleDuration.PERMANENT,
                now, OPERATOR, null, "试");

        assertThat(throttles.findById(first.getId()).orElseThrow().isActiveAt(now))
                .as("两行同时生效时「解除」只解一行，运营会以为按钮坏了")
                .isFalse();
        assertThat(throttles.findById(second.getId()).orElseThrow().isActiveAt(now)).isTrue();
        assertThat(service.history(ThrottleScope.ACCOUNT, author.getId())).hasSize(2);
    }
}
