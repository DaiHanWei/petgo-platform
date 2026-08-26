package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.repository.ContentPinRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：顶置排期的时间窗判定、重叠拦截与下架联动（V1.1.6 Story 4.1）。
 *
 * <p>⚠️ 本 story **没有对外接口**（后台配置界面不在本轮），所以这里直接打 service / repository，
 * 而不是走 HTTP。
 */
class ContentPinIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentPinRepository pins;

    @Autowired
    private ContentPinService pinService;

    @Autowired
    private ContentService contentService;

    @Autowired
    private ContentPostRepository posts;

    private static final Instant START = Instant.parse("2026-09-01T03:00:00Z");
    private static final Instant END = Instant.parse("2026-09-01T05:00:00Z");

    /** 每个用例用独立坑位名，避免同库历史数据串扰（L1 不回滚）。 */
    private String uniqueSlot() {
        return "TEST_SLOT_" + SEQ.incrementAndGet();
    }

    private ContentPost savePost(long authorId) {
        return posts.save(ContentPost.publish(authorId, ContentType.DAILY, null, "pin target",
                List.of(), null));
    }

    // ---------------------------------------------------------------- 时间窗

    /**
     * 🔴 本 story 最重要的一条：**SQL 侧与 Java 侧在同一组边界时刻上结论必须一致**。
     *
     * <p>取数过滤只能写在 SQL 的 WHERE 里（捞出来再内存筛会破坏分页），
     * 所以物理上没法与 {@link ScheduleWindow} 共用同一行代码。AC 明写：
     * 「若各写一遍，左闭右开的边界会在某处被写成全闭，出现『App 上已失效、后台还显示生效中』」。
     *
     * <p>这条就是堵那个缺口的 —— 谁哪天把某一侧改成全闭，这里立刻红。
     */
    @Test
    void sqlAndJavaAgreeOnEveryBoundaryInstant() {
        String slot = uniqueSlot();
        pinService.schedule(ContentPin.ofContent(slot, savePost(newUser().getId()).getId(), START, END));

        List<Instant> probes = List.of(
                START.minusMillis(1),   // 开始前一毫秒
                START,                  // 开始整点（闭）
                START.plusMillis(1),
                END.minusMillis(1),     // 结束前一毫秒
                END,                    // 结束整点（开）
                END.plusMillis(1));

        for (Instant t : probes) {
            boolean sqlSaysActive = pins.findActiveOne(slot, t).isPresent();
            boolean javaSaysActive = ScheduleWindow.isActiveAt(t, START, END);
            assertThat(sqlSaysActive)
                    .as("时刻 %s：SQL 侧与 Java 侧结论必须一致（左闭右开）", t)
                    .isEqualTo(javaSaysActive);
        }
    }

    /** 提前结束之后，SQL 侧也必须立刻认为不生效（不是等到排期结束）。 */
    @Test
    void earlyTerminationTakesEffectInSqlImmediately() {
        String slot = uniqueSlot();
        ContentPost post = savePost(newUser().getId());
        pinService.schedule(ContentPin.ofContent(slot, post.getId(), START, END));
        Instant mid = Instant.parse("2026-09-01T04:00:00Z");
        assertThat(pins.findActiveOne(slot, mid)).isPresent();

        pinService.terminateForContent(post.getId(), mid);

        assertThat(pins.findActiveOne(slot, mid)).isEmpty();
        assertThat(pins.findActiveOne(slot, mid.minusMillis(1))).isPresent();
    }

    // ---------------------------------------------------------------- 重叠

    @Test
    void overlappingWindowInSameSlotIsRejected() {
        String slot = uniqueSlot();
        long author = newUser().getId();
        pinService.schedule(ContentPin.ofContent(slot, savePost(author).getId(), START, END));

        assertThatThrownBy(() -> pinService.schedule(ContentPin.ofContent(
                slot, savePost(author).getId(),
                Instant.parse("2026-09-01T04:00:00Z"), Instant.parse("2026-09-01T06:00:00Z"))))
                .isInstanceOf(AppException.class);
    }

    /** 🛡 首尾相接**不算重叠** —— 排满连续档期是运营的正常用法。 */
    @Test
    void backToBackWindowsAreAllowed() {
        String slot = uniqueSlot();
        long author = newUser().getId();
        pinService.schedule(ContentPin.ofContent(slot, savePost(author).getId(), START, END));

        pinService.schedule(ContentPin.ofContent(slot, savePost(author).getId(),
                END, END.plusSeconds(3600)));

        assertThat(pins.findActiveOne(slot, END)).isPresent();
    }

    /**
     * 🛡 **坑位是一个字段** —— 换一个坑位取值即可"新增坑位"，无需改表结构或代码结构。
     *
     * <p>这条守的是 AD-8 Rule 5：下游 V1.2.0 的话题页坑位直接复用本机制，
     * 写死为首页会导致下游重构。
     */
    @Test
    void differentSlotsAreIndependentSoAddingASlotNeedsNoSchemaChange() {
        String slotA = uniqueSlot();
        String slotB = uniqueSlot(); // 一个从未出现过的坑位名，直接就能用
        long author = newUser().getId();

        pinService.schedule(ContentPin.ofContent(slotA, savePost(author).getId(), START, END));
        // 同一时间窗在另一个坑位上不算冲突
        pinService.schedule(ContentPin.ofContent(slotB, savePost(author).getId(), START, END));

        Instant mid = Instant.parse("2026-09-01T04:00:00Z");
        assertThat(pins.findActiveOne(slotA, mid)).isPresent();
        assertThat(pins.findActiveOne(slotB, mid)).isPresent();
        assertThat(pins.findActiveOne(slotA, mid).get().getId())
                .isNotEqualTo(pins.findActiveOne(slotB, mid).get().getId());
    }

    // ---------------------------------------------------------------- 下架联动

    /**
     * 🔴 三种触发**各验一次**。
     *
     * <p>现有的下架事件只在"运营下架"时发（作者自删刻意不发，因为它被用来推「你的内容因违规被移除」），
     * 所以本 story 新增了一条语义更宽的「内容不再可展示」事件。这三条就是验它真的覆盖了三种触发。
     */
    @Test
    void authorDeleteEndsThePin() {
        String slot = uniqueSlot();
        User author = newUser();
        ContentPost post = savePost(author.getId());
        pinService.schedule(ContentPin.ofContent(slot, post.getId(), START, END));

        contentService.deleteByAuthor(post.getId(), author.getId());

        assertThat(reload(slot).getTerminatedAt()).isNotNull();
    }

    @Test
    void adminTakedownEndsThePin() {
        String slot = uniqueSlot();
        ContentPost post = savePost(newUser().getId());
        pinService.schedule(ContentPin.ofContent(slot, post.getId(), START, END));

        contentService.softDelete(post.getId(), DeleteReason.ADMIN_TAKEDOWN);

        assertThat(reload(slot).getTerminatedAt()).isNotNull();
    }

    @Test
    void banningTheAuthorEndsThePin() {
        String slot = uniqueSlot();
        User author = newUser();
        ContentPost post = savePost(author.getId());
        pinService.schedule(ContentPin.ofContent(slot, post.getId(), START, END));

        contentService.takedownAllByAuthor(author.getId());

        assertThat(reload(slot).getTerminatedAt()).isNotNull();
    }

    /**
     * ⚠️ 注销**超出 AC 列的三种触发**，但性质相同：内容对他人不可见，
     * 顶置位却还在展示它。注销走批量隐藏、拿不到逐条 id，故在模块内直接收口。
     */
    @Test
    void deactivatingTheAuthorAlsoEndsThePin() {
        String slot = uniqueSlot();
        User author = newUser();
        ContentPost post = savePost(author.getId());
        pinService.schedule(ContentPin.ofContent(slot, post.getId(), START, END));

        contentService.deactivateAuthorContent(author.getId());

        assertThat(reload(slot).getTerminatedAt()).isNotNull();
    }

    /** 幂等：重复触发不该把提前结束时刻往后挪。 */
    @Test
    void terminatingTwiceKeepsTheFirstInstant() {
        String slot = uniqueSlot();
        ContentPost post = savePost(newUser().getId());
        pinService.schedule(ContentPin.ofContent(slot, post.getId(), START, END));

        Instant first = Instant.parse("2026-09-01T03:30:00Z");
        assertThat(pinService.terminateForContent(post.getId(), first)).isEqualTo(1);
        assertThat(pinService.terminateForContent(post.getId(), Instant.parse("2026-09-01T04:30:00Z")))
                .isEqualTo(0);

        assertThat(reload(slot).getTerminatedAt()).isEqualTo(first);
    }

    /** 已自然结束的排期不该被"回填"一个更晚的结束时刻。 */
    @Test
    void alreadyEndedScheduleIsNotTouched() {
        String slot = uniqueSlot();
        ContentPost post = savePost(newUser().getId());
        pinService.schedule(ContentPin.ofContent(slot, post.getId(), START, END));

        assertThat(pinService.terminateForContent(post.getId(), END.plusSeconds(60))).isEqualTo(0);
        assertThat(reload(slot).getTerminatedAt()).isNull();
    }

    private ContentPin reload(String slot) {
        return pins.findAll().stream()
                .filter(p -> slot.equals(p.getSlot()))
                .findFirst()
                .orElseThrow();
    }
}
