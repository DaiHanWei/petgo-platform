package com.tailtopia.notify.preference;

import static com.tailtopia.notify.domain.NotificationType.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.notify.domain.NotificationType;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * L0：V1.4.0 ④ Story 1.1 · AC2（PRD AC6）—— 每个通知类型属于且只属于一个推送分类，与 PRD §3.1 对照表逐值一致。
 *
 * <p>穷尽 switch 保证「新增类型未归类编译失败」；本测试保证「编译过了但归错类」也会红。
 */
class PushCategoryTest {

    /** 照 PRD §3.1 / story AC2 表抄的期望映射（39 条）。 */
    private static final Map<NotificationType, PushCategory> EXPECTED = Map.ofEntries(
            Map.entry(CONTENT_LIKED, PushCategory.LIKE),
            Map.entry(CONTENT_COMMENTED, PushCategory.COMMENT),
            Map.entry(PLACE_COMMENTED, PushCategory.COMMENT),
            Map.entry(CONTENT_MENTIONED, PushCategory.MENTION),
            Map.entry(USER_FOLLOWED, PushCategory.FOLLOWER),
            Map.entry(PET_GIFT_RECEIVED, PushCategory.GIFT),
            Map.entry(PET_BIRTHDAY, PushCategory.PET_REMINDER),
            Map.entry(COMPANION_ANNIVERSARY, PushCategory.PET_REMINDER),
            Map.entry(MILESTONE_NODE, PushCategory.ACHIEVEMENT),
            Map.entry(MILESTONE_SM_NODE, PushCategory.ACHIEVEMENT),
            Map.entry(GROWTH_LEVEL_UP, PushCategory.ACHIEVEMENT),
            Map.entry(OPS_ANNOUNCEMENT, PushCategory.PROMO),
            Map.entry(LIFECYCLE_D1, PushCategory.PROMO),
            Map.entry(LIFECYCLE_D3, PushCategory.PROMO),
            Map.entry(LIFECYCLE_D7, PushCategory.PROMO),
            Map.entry(LIFECYCLE_WINBACK, PushCategory.PROMO),
            Map.entry(REPURCHASE_FOOD_LOW, PushCategory.PROMO),
            Map.entry(CSAT_SURVEY, PushCategory.PROMO),
            Map.entry(SHOP_ORDER_SHIPPED, PushCategory.ORDER_ACCOUNT),
            Map.entry(SHOP_ORDER_EXCEPTION, PushCategory.ORDER_ACCOUNT),
            Map.entry(SHOP_RETURN_UPDATED, PushCategory.ORDER_ACCOUNT),
            Map.entry(REFUND_REJECTED, PushCategory.ORDER_ACCOUNT),
            Map.entry(PET_SLOT_EXPIRING, PushCategory.ORDER_ACCOUNT),
            Map.entry(PET_SLOT_EXPIRED, PushCategory.ORDER_ACCOUNT),
            Map.entry(GIFT_UNDELIVERED_REFUND, PushCategory.ORDER_ACCOUNT),
            Map.entry(TICKET_RESOLVED, PushCategory.ORDER_ACCOUNT),
            Map.entry(CONTENT_REMOVED, PushCategory.ORDER_ACCOUNT),
            Map.entry(CONTENT_REVIEW_REJECTED, PushCategory.ORDER_ACCOUNT),
            Map.entry(CONTENT_REVIEW_TIMED_OUT, PushCategory.ORDER_ACCOUNT),
            Map.entry(REPORT_REVIEWED, PushCategory.ORDER_ACCOUNT),
            Map.entry(NAME_RESET, PushCategory.ORDER_ACCOUNT),
            Map.entry(AVATAR_RESET, PushCategory.ORDER_ACCOUNT),
            Map.entry(ACCOUNT_WARNED, PushCategory.ORDER_ACCOUNT),
            Map.entry(ACCOUNT_SUSPENDED, PushCategory.ORDER_ACCOUNT),
            Map.entry(VET_REPLY, PushCategory.ORDER_ACCOUNT),
            Map.entry(NEW_CONSULT_REQUEST, PushCategory.ORDER_ACCOUNT),
            Map.entry(CONSULT_CLOSED, PushCategory.ORDER_ACCOUNT),
            Map.entry(CONTENT_REVIEW_APPROVED, PushCategory.ORDER_ACCOUNT),
            Map.entry(IDENTITY_REQUIRE_MODIFY, PushCategory.ORDER_ACCOUNT));

    @Test
    void everyTypeMapsToACategory() {
        assertThat(NotificationType.values()).hasSize(39);
        for (NotificationType t : NotificationType.values()) {
            assertThat(PushCategory.of(t)).as(t.name()).isNotNull();
        }
    }

    @Test
    void mappingEqualsPrdTableExactly() {
        Map<NotificationType, PushCategory> actual = new EnumMap<>(NotificationType.class);
        for (NotificationType t : NotificationType.values()) {
            actual.put(t, PushCategory.of(t));
        }
        assertThat(actual).isEqualTo(EXPECTED);
    }

    @Test
    void categoryCountsMatchPrd() {
        Map<PushCategory, Long> counts = Arrays.stream(NotificationType.values())
                .collect(Collectors.groupingBy(PushCategory::of, () -> new EnumMap<>(PushCategory.class),
                        Collectors.counting()));
        assertThat(counts).containsExactly(
                Map.entry(PushCategory.LIKE, 1L),
                Map.entry(PushCategory.COMMENT, 2L),
                Map.entry(PushCategory.MENTION, 1L),
                Map.entry(PushCategory.FOLLOWER, 1L),
                Map.entry(PushCategory.GIFT, 1L),
                Map.entry(PushCategory.PET_REMINDER, 2L),
                Map.entry(PushCategory.ACHIEVEMENT, 3L),
                Map.entry(PushCategory.PROMO, 7L),
                Map.entry(PushCategory.ORDER_ACCOUNT, 21L));
    }

    @Test
    void declarationOrderIsResponseOrderAndOnlyOrderAccountIsLocked() {
        assertThat(PushCategory.values()).containsExactly(
                PushCategory.LIKE, PushCategory.COMMENT, PushCategory.MENTION, PushCategory.FOLLOWER,
                PushCategory.GIFT, PushCategory.PET_REMINDER, PushCategory.ACHIEVEMENT, PushCategory.PROMO,
                PushCategory.ORDER_ACCOUNT);
        for (PushCategory c : PushCategory.values()) {
            assertThat(c.lockable()).as(c.name()).isEqualTo(c != PushCategory.ORDER_ACCOUNT);
        }
    }

    @Test
    void keySinglePoints() {
        // PRD AC2a：CSAT 跟着营销走，工单已解决不可关
        assertThat(PushCategory.of(CSAT_SURVEY)).isEqualTo(PushCategory.PROMO);
        assertThat(PushCategory.of(TICKET_RESOLVED)).isEqualTo(PushCategory.ORDER_ACCOUNT);
        // PRD AC2b：@ 与评论分开
        assertThat(PushCategory.of(CONTENT_MENTIONED)).isEqualTo(PushCategory.MENTION);
        assertThat(PushCategory.of(CONTENT_COMMENTED)).isEqualTo(PushCategory.COMMENT);
        assertThat(PushCategory.of(MILESTONE_SM_NODE)).isEqualTo(PushCategory.ACHIEVEMENT);
        assertThat(PushCategory.of(NEW_CONSULT_REQUEST)).isEqualTo(PushCategory.ORDER_ACCOUNT);
    }
}
