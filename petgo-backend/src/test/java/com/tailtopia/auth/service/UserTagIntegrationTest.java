package com.tailtopia.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.domain.UserTag;
import com.tailtopia.auth.domain.UserTagAssignment;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.repository.UserTagAssignmentRepository;
import com.tailtopia.auth.repository.UserTagRepository;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：用户标签的时间窗判定、上限与批量取数（V1.1.6 Story 5.1）。
 *
 * <p>⚠️ 后台配置界面不在本轮，所以标签与分配都直接造。
 */
class UserTagIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private UserTagRepository tags;

    @Autowired
    private UserTagAssignmentRepository assignments;

    @Autowired
    private UserTagQueryService tagQuery;

    @Autowired
    private AccountQueryService accounts;

    private UserTag newTag(String suffix) {
        return tags.save(UserTag.of("tag_" + suffix, "标签" + suffix, "🏅",
                "这是一句由运营配置的说明文案"));
    }

    private UserTagAssignment assign(long userId, long tagId, Instant startsAt, Instant endsAt) {
        return assignments.save(UserTagAssignment.of(userId, tagId, startsAt, endsAt));
    }

    // ---------------------------------------------------------------- 时间窗

    /**
     * 🛡 **不设结束时间 = 永久分配**。
     *
     * <p>这是本 story 给那份唯一判定扩的口径 —— 顶置排期必有结束时间，标签可以没有。
     * 扩的是同一份实现，不是另写一份。
     */
    @Test
    void assignmentWithoutAnEndIsActiveForever() {
        User u = newUser();
        UserTag tag = newTag(String.valueOf(SEQ.incrementAndGet()));
        assign(u.getId(), tag.getId(), Instant.now().minusSeconds(60), null);

        Instant farFuture = Instant.parse("2099-01-01T00:00:00Z");
        assertThat(tagQuery.findVisibleTags(List.of(u.getId()), farFuture)).containsKey(u.getId());
        // Java 侧那份判定也必须认这个口径
        assertThat(ScheduleWindow.isActiveAt(farFuture, Instant.now().minusSeconds(60), null))
                .isTrue();
    }

    /**
     * 🔴 SQL 侧与 Java 侧在同一组边界时刻上结论必须一致（沿用 Story 4.1 的做法）。
     *
     * <p>取数过滤只能写在 SQL 的 WHERE 里，物理上与 Java 那份共用不了同一行代码；
     * 靠这条把缺口堵上 —— 谁哪天把某一侧的左闭右开写成全闭，这里立刻红。
     */
    @Test
    void sqlAndJavaAgreeOnEveryBoundaryInstant() {
        User u = newUser();
        UserTag tag = newTag(String.valueOf(SEQ.incrementAndGet()));
        Instant start = Instant.parse("2026-10-01T03:00:00Z");
        Instant end = Instant.parse("2026-10-01T05:00:00Z");
        assign(u.getId(), tag.getId(), start, end);

        for (Instant t : List.of(start.minusMillis(1), start, start.plusMillis(1),
                end.minusMillis(1), end, end.plusMillis(1))) {
            boolean sql = tagQuery.findVisibleTags(List.of(u.getId()), t).containsKey(u.getId());
            boolean java = ScheduleWindow.isActiveAt(t, start, end);
            assertThat(sql).as("时刻 %s：SQL 侧与 Java 侧结论必须一致（左闭右开）", t).isEqualTo(java);
        }
    }

    // ---------------------------------------------------------------- 上限

    /** 🛡 同时最多 3 个，按分配时间倒序取最近的；**其余分配记录保留在库、只是不展示**。 */
    @Test
    void atMostThreeAreShownAndTheRestAreKeptInDb() {
        User u = newUser();
        Instant base = Instant.now().minusSeconds(3600);
        for (int i = 0; i < 5; i++) {
            UserTag tag = newTag(SEQ.incrementAndGet() + "_" + i);
            assign(u.getId(), tag.getId(), base.plusSeconds(i * 60L), null);
        }

        var visible = tagQuery.findVisibleTags(List.of(u.getId()), Instant.now()).get(u.getId());
        assertThat(visible).hasSize(3);

        // 记录仍在库里（只是不展示）
        assertThat(assignments.findAll().stream()
                .filter(a -> a.getUserId().equals(u.getId())).count()).isEqualTo(5);
    }

    // ---------------------------------------------------------------- 批量 + 注销

    /**
     * 🛡 **一律批量**（AD-11）。四处展示位全都经作者投影拿标签，
     * 所以这里验的是"多作者一次取回"，而不是每个作者各查一次。
     */
    @Test
    void tagsForManyAuthorsComeBackInOneBatch() {
        User a = newUser();
        User b = newUser();
        UserTag tagA = newTag(String.valueOf(SEQ.incrementAndGet()));
        UserTag tagB = newTag(String.valueOf(SEQ.incrementAndGet()));
        assign(a.getId(), tagA.getId(), Instant.now().minusSeconds(60), null);
        assign(b.getId(), tagB.getId(), Instant.now().minusSeconds(60), null);

        Map<Long, AuthorView> views = accounts.findAuthorViews(List.of(a.getId(), b.getId()));

        assertThat(views.get(a.getId()).tags()).extracting("code").containsExactly(tagA.getCode());
        assertThat(views.get(b.getId()).tags()).extracting("code").containsExactly(tagB.getCode());
    }

    /** 没有标签的作者拿到的是空表，不是 null（调用方不必判空）。 */
    @Test
    void authorsWithoutTagsGetAnEmptyList() {
        User u = newUser();
        assertThat(accounts.findAuthorViews(List.of(u.getId())).get(u.getId()).tags()).isEmpty();
    }

    /**
     * 🛡 注销作者**不带标签**（AC6）——匿名化之后不该再挂着身份标识。
     *
     * <p>作者投影本就把注销的一律匿名化，标签随之为空；这条把它钉住，
     * 防止日后有人在匿名化之后又给它贴回标签。
     */
    @Test
    void deletedAuthorsCarryNoTags() {
        User u = newUser();
        UserTag tag = newTag(String.valueOf(SEQ.incrementAndGet()));
        assign(u.getId(), tag.getId(), Instant.now().minusSeconds(60), null);
        assertThat(accounts.findAuthorViews(List.of(u.getId())).get(u.getId()).tags()).hasSize(1);

        u.anonymizeForDeletion(Instant.now());
        users.save(u);

        AuthorView view = accounts.findAuthorViews(List.of(u.getId())).get(u.getId());
        assertThat(view.deleted()).isTrue();
        assertThat(view.tags()).isEmpty();
    }

    /** 空集合直接短路，不发查询（纯文字页不必白跑一次）。 */
    @Test
    void emptyInputShortCircuits() {
        assertThat(tagQuery.findVisibleTags(List.of(), Instant.now())).isEmpty();
        assertThat(tagQuery.findVisibleTags(null, Instant.now())).isEmpty();
    }
}
