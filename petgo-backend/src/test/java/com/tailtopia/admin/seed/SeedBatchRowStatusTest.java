package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.content.domain.ContentType;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * L0：行状态的合法流转表（V1.1.6 Story 13.1 · AC1）。
 *
 * <p><b>值得单测的理由</b>：非法流转的后果都是"后台显示与线上事实不一致"这类安静的错 ——
 * 比如一条已发布的内容在后台显示成"待发布"，运营再点一次发布就成了重复发帖。
 */
class SeedBatchRowStatusTest {

    @Test
    void theHappyPathFollowsTheSpecifiedChain() {
        assertThat(SeedBatchRowStatus.DRAFT.canGoTo(SeedBatchRowStatus.VALIDATED)).isTrue();
        assertThat(SeedBatchRowStatus.VALIDATED.canGoTo(SeedBatchRowStatus.SCHEDULED)).isTrue();
        assertThat(SeedBatchRowStatus.SCHEDULED.canGoTo(SeedBatchRowStatus.PUBLISHED)).isTrue();
        assertThat(SeedBatchRowStatus.SCHEDULED.canGoTo(SeedBatchRowStatus.FAILED)).isTrue();
    }

    /** AC1 明写的那条：取消排期回退草稿。 */
    @Test
    void cancellingAScheduleGoesBackToDraft() {
        assertThat(SeedBatchRowStatus.SCHEDULED.canGoTo(SeedBatchRowStatus.DRAFT)).isTrue();
    }

    /**
     * ⚠️ {@code VALIDATED → PUBLISHED} 是**刻意允许**的。
     *
     * <p>AC1 把链写成「校验通过 → 已排期 → 已发布」，但"确认发布"（13-4）是**立即**发布：
     * 硬走 SCHEDULED 就得编一个假的计划时间，于是排期列表里会出现一堆从未被排期的行。
     */
    @Test
    void immediatePublishSkipsTheScheduledState() {
        assertThat(SeedBatchRowStatus.VALIDATED.canGoTo(SeedBatchRowStatus.PUBLISHED)).isTrue();
    }

    /** 校验通过后又改了内容 ⇒ 必须能退回草稿重新校验，否则改一个字就得整批重来。 */
    @Test
    void editingAfterValidationCanGoBackToDraft() {
        assertThat(SeedBatchRowStatus.VALIDATED.canGoTo(SeedBatchRowStatus.DRAFT)).isTrue();
    }

    /** 修错重提。 */
    @Test
    void failedRowsCanBeReopened() {
        assertThat(SeedBatchRowStatus.FAILED.canGoTo(SeedBatchRowStatus.DRAFT)).isTrue();
    }

    /**
     * 🛡 <b>PUBLISHED 是终态</b>。
     *
     * <p>内容已经在 content_posts 里、已经对外可见了 —— 把行改回草稿不会让那条内容消失，
     * 只会让后台显示与线上事实不一致。「整批撤回」是另一件事（本版本不做），
     * 它删的是**内容**而不是改行状态。
     */
    @Test
    void publishedIsTerminal() {
        for (SeedBatchRowStatus target : SeedBatchRowStatus.values()) {
            assertThat(SeedBatchRowStatus.PUBLISHED.canGoTo(target))
                    .as("PUBLISHED → %s 必须被拒", target).isFalse();
        }
    }

    /** 🛡 未校验的草稿不能直接排期或发布 —— 那等于绕过校验。 */
    @Test
    void draftCannotSkipValidation() {
        assertThat(SeedBatchRowStatus.DRAFT.canGoTo(SeedBatchRowStatus.SCHEDULED)).isFalse();
        assertThat(SeedBatchRowStatus.DRAFT.canGoTo(SeedBatchRowStatus.PUBLISHED)).isFalse();
    }

    /** 已排期的行不能"倒回"待确认 —— 取消排期的语义是回草稿（AC1），不是回上一步。 */
    @Test
    void scheduledCannotGoBackToValidated() {
        assertThat(SeedBatchRowStatus.SCHEDULED.canGoTo(SeedBatchRowStatus.VALIDATED)).isFalse();
    }

    /**
     * 「还没发出去」的定义 —— 13-5 的到点扫描与 12-1 的排期计数都依赖它。
     *
     * <p>⚠️ 这条写成穷举而不是 {@code assertThat(DRAFT.isPending()).isTrue()} 之类：
     * 将来新增一个状态时，它会强制作者回答"这个新状态算不算没发出去"。
     */
    @Test
    void pendingCoversExactlyTheNotYetPublishedStates() {
        Set<SeedBatchRowStatus> pending = EnumSet.noneOf(SeedBatchRowStatus.class);
        for (SeedBatchRowStatus s : SeedBatchRowStatus.values()) {
            if (s.isPending()) {
                pending.add(s);
            }
        }
        assertThat(pending).containsExactlyInAnyOrder(SeedBatchRowStatus.DRAFT,
                SeedBatchRowStatus.VALIDATED, SeedBatchRowStatus.SCHEDULED);
    }

    // ——————————————————— 实体上的三条不变式 ———————————————————

    private static SeedBatchRow newDraft() {
        return SeedBatchRow.draft(1L, 1, 7L, ContentType.DAILY, null, "x", null, null);
    }

    /**
     * 🛡 <b>排期前必须已有计划时间</b>，否则"到点"永远不会到 —— 那条内容会安静地
     * 停在"已排期"上再也不动，而运营以为它排好了。
     */
    @Test
    void schedulingWithoutAPlannedTimeIsRejectedAtTheEntityLevel() {
        SeedBatchRow r = newDraft();
        r.transitionTo(SeedBatchRowStatus.VALIDATED);

        assertThatThrownBy(() -> r.transitionTo(SeedBatchRowStatus.SCHEDULED))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 🛡 <b>转已发布前必须已回填内容 id</b>（AC3）。
     *
     * <p>那个 id 是「整批撤回」（本版本不做，OQ-22 后移）**唯一的抓手** ——
     * 漏了它，那条已经发出去的内容就再也和这一行对不上了，而这件事事后无法补救。
     */
    @Test
    void markingPublishedWithoutBackfillingTheContentIdIsRejected() {
        SeedBatchRow r = newDraft();
        r.transitionTo(SeedBatchRowStatus.VALIDATED);

        assertThatThrownBy(() -> r.transitionTo(SeedBatchRowStatus.PUBLISHED))
                .isInstanceOf(IllegalStateException.class);

        r.setContentPostId(4242L);
        r.transitionTo(SeedBatchRowStatus.PUBLISHED); // 回填之后才允许
        assertThat(r.getStatus()).isEqualTo(SeedBatchRowStatus.PUBLISHED);
    }

    /** 回退草稿时清掉计划时间与错误 —— 留着会显示"未排期，计划 X 日发布"这种自相矛盾的东西。 */
    @Test
    void goingBackToDraftClearsScheduleAndError() {
        SeedBatchRow r = newDraft();
        r.transitionTo(SeedBatchRowStatus.VALIDATED);
        r.setScheduledAt(java.time.Instant.now().plusSeconds(3600));
        r.transitionTo(SeedBatchRowStatus.SCHEDULED);
        r.setErrorMessage("旧的失败原因");

        r.transitionTo(SeedBatchRowStatus.DRAFT);

        assertThat(r.getScheduledAt()).isNull();
        assertThat(r.getErrorMessage()).isNull();
    }
}
