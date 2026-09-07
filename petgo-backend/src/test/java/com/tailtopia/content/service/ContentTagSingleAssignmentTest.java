package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentTag;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.repository.ContentTagAssignmentRepository;
import com.tailtopia.content.repository.ContentTagRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：一条内容同时最多一个装饰标签（Story 11.6）。
 *
 * <p>🔴 走真库的理由：判据是**时间窗重叠**，而那是一条 SQL 里的区间比较 ——
 * 边界（接续 vs 重叠、`endsAt` 为 null 表示永久）在 mock 上验不出来。
 */
class ContentTagSingleAssignmentTest extends ApiIntegrationTest {

    @Autowired
    private ContentTagQueryService service;

    @Autowired
    private ContentTagRepository tags;

    @Autowired
    private ContentTagAssignmentRepository assignments;

    @Autowired
    private ContentPostRepository posts;

    private ContentPost publicPost() {
        return posts.save(ContentPost.publish(newUser().getId(), ContentType.DAILY, null,
                "tag-single-" + SEQ.incrementAndGet(), List.of()));
    }

    private ContentTag tag(String suffix) {
        return tags.save(ContentTag.of("SGL_" + suffix + SEQ.incrementAndGet(),
                "标签" + suffix, "https://cdn/i.png", "说明"));
    }


    /**
     * 查重叠 —— 收敛成一处，免得每个用例都写那串布尔标志。
     *
     * <p>⚠️ 那些标志是为了绕开 PostgreSQL 的 42P18（裸 {@code :x is null} 推不出类型），
     * 不是业务参数。详见仓库里 {@code findFeed} 的注释。
     */
    private List<com.tailtopia.content.domain.ContentTagAssignment> overlapping(
            long postId, Instant startsAt, Instant endsAt) {
        return assignments.findOverlapping(postId, startsAt, endsAt != null, endsAt, false, null);
    }

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    // ── AC1：同时段拒绝，且点明是哪一个 ─────────────────────────────

    @Test
    void secondTagInTheSameWindowIsRejectedAndNamesTheExistingOne() {
        ContentPost p = publicPost();
        ContentTag a = tag("A");
        ContentTag b = tag("B");
        service.assign(p.getId(), a.getId(), T0, T0.plus(Duration.ofDays(7)));

        assertThatThrownBy(() -> service.assign(p.getId(), b.getId(),
                T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(3))))
                .isInstanceOf(AppException.class)
                // 🛡 报错要点明是哪一个 —— 只说"已有标签"运营得自己去列表里找
                .hasMessageContaining(a.getName())
                .hasMessageContaining("同时只能挂一个");
    }

    /** 🛡 被拒时**不留半条记录**。 */
    @Test
    void rejectedAssignmentLeavesNothingBehind() {
        ContentPost p = publicPost();
        service.assign(p.getId(), tag("A").getId(), T0, T0.plus(Duration.ofDays(7)));
        long before = assignments.count();

        assertThatThrownBy(() -> service.assign(p.getId(), tag("B").getId(),
                T0, T0.plus(Duration.ofDays(7)))).isInstanceOf(AppException.class);

        assertThat(assignments.count()).isEqualTo(before);
    }

    // ── AC2：判据是「窗口重叠」，不是「有没有别的记录」 ───────────────

    /**
     * 🔴 本类最要紧的一条：窗口**不重叠**必须放过。
     *
     * <p>「本周最佳」下周一到期、「本月最佳」下周二开始 —— 这是**正常排期**。
     * 把校验写成"这条内容有没有别的标签记录"会把它判成冲突，
     * 而运营完全不知道为什么排不进去（他看到的两个时段明明不重叠）。
     */
    @Test
    void nonOverlappingWindowsAreAllowed() {
        ContentPost p = publicPost();
        service.assign(p.getId(), tag("A").getId(), T0, T0.plus(Duration.ofDays(7)));

        // 紧接着那一刻开始 —— 接续不是重叠
        service.assign(p.getId(), tag("B").getId(),
                T0.plus(Duration.ofDays(7)), T0.plus(Duration.ofDays(14)));

        assertThat(overlapping(p.getId(), T0, T0.plus(Duration.ofDays(30))))
                .as("两条都在，只是时段不重叠").hasSize(2);
    }

    /** ⚠️ 边界：旧窗 22:00 结束、新窗 22:00 开始 —— 用 `<=` 会误判成冲突。 */
    @Test
    void backToBackAtTheExactSameInstantIsNotAClash() {
        ContentPost p = publicPost();
        Instant mid = T0.plus(Duration.ofHours(22));
        service.assign(p.getId(), tag("A").getId(), T0, mid);

        service.assign(p.getId(), tag("B").getId(), mid, mid.plus(Duration.ofHours(2)));

        assertThat(overlapping(p.getId(), T0, mid.plus(Duration.ofHours(2))))
                .hasSize(2);
    }

    /** 已过期的记录不阻止新打标。 */
    @Test
    void expiredAssignmentDoesNotBlockANewOne() {
        ContentPost p = publicPost();
        Instant past = Instant.now().minus(Duration.ofDays(30));
        service.assign(p.getId(), tag("A").getId(), past, past.plus(Duration.ofDays(1)));

        service.assign(p.getId(), tag("B").getId(), Instant.now(),
                Instant.now().plus(Duration.ofDays(7)));

        assertThat(overlapping(p.getId(), Instant.now(),
                Instant.now().plus(Duration.ofDays(7)))).hasSize(1);
    }

    /** 🔴 永久分配（`endsAt` 为 null）挡住其后的一切。 */
    @Test
    void permanentAssignmentBlocksEverythingAfterIt() {
        ContentPost p = publicPost();
        service.assign(p.getId(), tag("A").getId(), T0, null);

        assertThatThrownBy(() -> service.assign(p.getId(), tag("B").getId(),
                T0.plus(Duration.ofDays(365)), null)).isInstanceOf(AppException.class);
    }

    /** ⚠️ 反过来：永久分配之**前**的时段仍可用。 */
    @Test
    void windowBeforeAPermanentAssignmentIsStillAllowed() {
        ContentPost p = publicPost();
        service.assign(p.getId(), tag("A").getId(), T0, null);

        service.assign(p.getId(), tag("B").getId(),
                T0.minus(Duration.ofDays(10)), T0);

        assertThat(overlapping(p.getId(),
                T0.minus(Duration.ofDays(10)), null)).hasSize(2);
    }

    // ── AC3：移除后可重打 ───────────────────────────────────────────

    @Test
    void removingTheAssignmentAllowsANewOne() {
        ContentPost p = publicPost();
        ContentTag a = tag("A");
        var first = service.assign(p.getId(), a.getId(), T0, T0.plus(Duration.ofDays(7)));

        assignments.deleteById(first.getId());
        service.assign(p.getId(), tag("B").getId(), T0, T0.plus(Duration.ofDays(7)));

        assertThat(overlapping(p.getId(), T0, T0.plus(Duration.ofDays(7))))
                .hasSize(1);
    }

    /** 🛡 约束是**按内容**算的，不同内容互不影响。 */
    @Test
    void theLimitIsPerContentNotGlobal() {
        ContentTag a = tag("A");
        service.assign(publicPost().getId(), a.getId(), T0, T0.plus(Duration.ofDays(7)));
        service.assign(publicPost().getId(), a.getId(), T0, T0.plus(Duration.ofDays(7)));
    }
}
