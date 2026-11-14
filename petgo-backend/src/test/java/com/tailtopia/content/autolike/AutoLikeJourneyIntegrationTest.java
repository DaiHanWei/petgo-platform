package com.tailtopia.content.autolike;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.dailyreport.DailyReportQuery;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.autocomment.VirtualIdentityPicker;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.LikeService;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * L1 · V1.5.0 likes Story 2.4 AC2：PRD UJ-1 全链路，拨钟复现。需真 PG + Redis（云端不跑，本地验收）。
 *
 * <p>用 2001 年的日期造帖与轮次：共享库里别的帖子不会落进这些轮次的检查窗口。
 * 周一 20:00 WIB 发帖（2 个真人赞）→ 周三 10:30 轮检查入集合、排 1 条并派发 → 16:30 轮再 1 条 →
 * 周四 10:30 轮第 3 条到 5 → 离开集合；之后再跑两轮不再排它。
 */
class AutoLikeJourneyIntegrationTest extends ApiIntegrationTest {

    // 2001-01-01 是周一。WIB = UTC+7。
    private static final Instant POSTED = Instant.parse("2001-01-01T13:00:00Z");       // 周一 20:00 WIB
    private static final Instant WED_AM = Instant.parse("2001-01-03T03:30:00Z");       // 周三 10:30 WIB
    private static final Instant WED_PM = Instant.parse("2001-01-03T09:30:00Z");       // 周三 16:30 WIB
    private static final Instant THU_AM = Instant.parse("2001-01-04T03:30:00Z");       // 周四 10:30 WIB
    private static final Instant THU_PM = Instant.parse("2001-01-04T09:30:00Z");
    private static final Instant FRI_AM = Instant.parse("2001-01-05T03:30:00Z");

    @Autowired
    private ContentPostRepository posts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NamedParameterJdbcTemplate named;
    @Autowired
    private PlatformTransactionManager txm;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private AutoLikeProperties props;
    @Autowired
    private AutoLikeVirtualPool pool;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private ContentLikeRepository likes;
    @Autowired
    private ContentSpeciesResolver speciesResolver;
    @Autowired
    private VirtualIdentityPicker picker;
    @Autowired
    private LikeService likeService;
    @Autowired
    private AutoLikePlanner planner;
    @Autowired
    private AutoLikeHoldout holdout;
    @Autowired
    private DailyReportQuery dailyReport;

    private boolean savedEnabled;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM auto_like_logs");
        jdbc.update("DELETE FROM auto_like_runs");
        jdbc.update("DELETE FROM auto_like_targets");
        savedEnabled = props.isEnabled();
        props.setEnabled(true);
    }

    @AfterEach
    void restore() {
        props.setEnabled(savedEnabled);
    }

    private void dispatchAt(Instant now) {
        new AutoLikeDispatcher(named, txm, redis, props, pool, posts, userRepo, likes, speciesResolver, picker,
                likeService, Clock.fixed(now, ZoneOffset.UTC)).dispatchOnce();
    }

    /** 计划一轮，并在窗口结束前 1 分钟派发（此时本轮全部 planned_at 均已到期）。 */
    private void runSlot(AutoLikeSlot slot, Instant start) {
        planner.plan(slot, start);
        dispatchAt(start.plus(props.getSpreadWindow()).minus(Duration.ofMinutes(1)));
    }

    private User nonHoldoutAuthor() {
        for (int i = 0; i < 200; i++) {
            User u = newUser();
            if (!holdout.isHoldout(u.getId())) {
                return u;
            }
        }
        throw new IllegalStateException("no non-holdout user");
    }

    @Test
    void userJourneyOne() {
        for (int i = 0; i < 3; i++) {
            User v = newUser();
            jdbc.update("UPDATE users SET account_type = 'VIRTUAL', google_sub = 'virtual:' || google_sub WHERE id = ?",
                    v.getId());
        }
        User author = nonHoldoutAuthor();
        ContentPost p = posts.save(ContentPost.publish(author.getId(), ContentType.DAILY, null, "t", List.of(), null));
        long postId = p.getId();
        jdbc.update("UPDATE content_posts SET created_at = ? WHERE id = ?", Timestamp.from(POSTED), postId);
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO content_likes (post_id, user_id) VALUES (?, ?)", postId, newUser().getId());
        }

        runSlot(AutoLikeSlot.MORNING, WED_AM);
        assertThat(likes.countByPostId(postId)).isEqualTo(3L);
        runSlot(AutoLikeSlot.EVENING, WED_PM);
        assertThat(likes.countByPostId(postId)).isEqualTo(4L);
        runSlot(AutoLikeSlot.MORNING, THU_AM);
        assertThat(likes.countByPostId(postId)).isEqualTo(5L);
        runSlot(AutoLikeSlot.EVENING, THU_PM);
        runSlot(AutoLikeSlot.MORNING, FRI_AM);
        assertThat(likes.countByPostId(postId)).isEqualTo(5L);

        assertThat(jdbc.queryForObject("SELECT leave_reason FROM auto_like_targets WHERE post_id = ?", String.class,
                postId)).isEqualTo("REACHED_TARGET");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auto_like_logs WHERE post_id = ?", Long.class, postId))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auto_like_logs WHERE post_id = ? AND status = 'LIKED'",
                Long.class, postId)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_likes WHERE post_id = ? AND is_automated",
                Long.class, postId)).isEqualTo(3L);
        // 作者没有 LIKE_RECEIVED 流水。⚠️ 成长值监听是 @Async：此处只是弱断言（真正的跳过逻辑由 L0
        // GrowthActivityListenerTest#automatedLikeAccruesNothing 钉住）；到这里已跑过 5 轮派发，若会入账大概率已落库。
        assertThat(jdbc.queryForObject("SELECT count(*) FROM growth_ledger WHERE user_id = ? AND rule_code = 'LIKE_RECEIVED'",
                Long.class, author.getId())).isZero();
        // 日报：attempted_at 的 WIB 日期——周三 2 条、周四 1 条
        assertThat(dailyReport.metricsOf(LocalDate.of(2001, 1, 3)).autoLikes()).isEqualTo(2L);
        assertThat(dailyReport.metricsOf(LocalDate.of(2001, 1, 4)).autoLikes()).isEqualTo(1L);
        // 「点赞数」不含自动赞（AC1 后半；真人那 2 个赞是今天点的，不在这两天）
        assertThat(dailyReport.metricsOf(LocalDate.of(2001, 1, 3)).likes()).isZero();
        assertThat(dailyReport.metricsOf(LocalDate.of(2001, 1, 4)).likes()).isZero();
        // 通知中心：同一帖的点赞通知合并（未读时）
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?"
                + " AND type = 'CONTENT_LIKED'", Long.class, author.getId())).isEqualTo(1L);
    }

    /** AC3 L1：dev profile（不设 AUTO_LIKE_ENABLED）→ AutoLikeJob.morning() 不写任何 run / log 行，日志含 auto like disabled。 */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void devProfileIsDisabledAndWritesNothing(CapturedOutput output) {
        Assumptions.assumeTrue(System.getenv("AUTO_LIKE_ENABLED") == null, "本机设了 AUTO_LIKE_ENABLED，跳过");
        props.setEnabled(savedEnabled);
        assertThat(props.isEnabled()).isFalse();
        new AutoLikeJob(planner, props, Clock.fixed(WED_AM, ZoneOffset.UTC)).morning();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auto_like_runs", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auto_like_logs", Long.class)).isZero();
        assertThat(output.getAll()).contains("auto like disabled");
    }
}
