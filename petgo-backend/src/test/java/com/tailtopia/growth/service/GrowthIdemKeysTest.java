package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** V1.4.0 ① Story 1.1 · AC2（L0）：幂等键格式（AD-6）。 */
class GrowthIdemKeysTest {

    @Test
    void formats() {
        assertThat(GrowthIdemKeys.dailyLogin(7L, LocalDate.of(2026, 10, 18))).isEqualTo("DAILY_LOGIN:7:2026-10-18");
        assertThat(GrowthIdemKeys.post(5L)).isEqualTo("POST:5");
        assertThat(GrowthIdemKeys.commentPosted(9L)).isEqualTo("COMMENT_POSTED:9");
        assertThat(GrowthIdemKeys.commentReceived(9L)).isEqualTo("COMMENT_RECEIVED:9");
        assertThat(GrowthIdemKeys.likeGiven(3L, 5L)).isEqualTo("LIKE_GIVEN:3:5");
        assertThat(GrowthIdemKeys.likeReceived(3L, 5L)).isEqualTo("LIKE_RECEIVED:3:5");
        assertThat(GrowthIdemKeys.collectReceived(3L, 5L)).isEqualTo("COLLECT_RECEIVED:3:5");
        assertThat(GrowthIdemKeys.checkin(11L)).isEqualTo("CHECKIN:11");
        assertThat(GrowthIdemKeys.milestone(7L, "C-S1", 10L)).isEqualTo("MILESTONE:7:C-S1:10");
        assertThat(GrowthIdemKeys.milestone(7L, "C-S1", null)).isEqualTo("MILESTONE:7:C-S1:0");
        assertThat(GrowthIdemKeys.giftSent(4L)).isEqualTo("GIFT_SENT:4");
        assertThat(GrowthIdemKeys.giftReceived(4L)).isEqualTo("GIFT_RECEIVED:4");
    }

    @Test
    void spendKeys() {
        assertThat(GrowthIdemKeys.spendIntent(42L)).isEqualTo("SPEND:PI:42");
        assertThat(GrowthIdemKeys.spendCoin("id-hd-card:5")).isEqualTo("SPEND:COIN:id-hd-card:5");
        assertThat(GrowthIdemKeys.spendConsultCoin(9L)).isEqualTo("SPEND:COIN:vet-consult-order:9");
        assertThat(GrowthIdemKeys.consultSpend(9L, 42L)).isEqualTo("SPEND:PI:42");
        assertThat(GrowthIdemKeys.consultSpend(9L, null)).isEqualTo("SPEND:COIN:vet-consult-order:9");
        assertThat(GrowthIdemKeys.spendShop(7L)).isEqualTo("SPEND:SHOP:7");
        assertThat(GrowthIdemKeys.refundSource("rf_1")).isEqualTo("REFUND:rf_1");
    }

    @Test
    void reverseConcatenatesOriginalIdAndSourceKey() {
        assertThat(GrowthIdemKeys.reverse(123L, GrowthIdemKeys.SOURCE_UNLIKE)).isEqualTo("REV:123:UNLIKE");
        assertThat(GrowthIdemKeys.reverse(123L, "REFUND:rf_xxx")).isEqualTo("REV:123:REFUND:rf_xxx");
    }

    /** V1.4.2 Story 2.1（P-7 定死）：账号级里程碑键 = 账号 + flag，与物种 / 宠物无关。 */
    @Test
    void milestoneAccountKeyIsUserAndFlag() {
        assertThat(GrowthIdemKeys.milestoneAccount(7L, "FIRST_FOLLOW_USER")).isEqualTo("MILESTONE_ACCT:7:FIRST_FOLLOW_USER");
        assertThat(GrowthIdemKeys.milestoneAccount(7L, "FIRST_COLLECT_CONTENT"))
                .isEqualTo("MILESTONE_ACCT:7:FIRST_COLLECT_CONTENT");
        // 与按宠物键不同前缀，互不相撞。
        assertThat(GrowthIdemKeys.milestoneAccount(7L, "C-S19")).doesNotStartWith("MILESTONE:");
        assertThat(GrowthIdemKeys.milestoneAccount(Long.MAX_VALUE, "X".repeat(32)).length())
                .isLessThanOrEqualTo(GrowthIdemKeys.MAX_LENGTH);
    }

    /** V1.4.2 Story 2.2（P-7 定死）：存量补算键，迁移 V20261104_2040 以 SQL 写同一格式。 */
    @Test
    void backfillAccountMilestoneKeyIsUserAndFlag() {
        assertThat(GrowthIdemKeys.backfillAccountMilestone(7L, "FIRST_FOLLOW_USER"))
                .isEqualTo("BACKFILL142:7:FIRST_FOLLOW_USER");
        assertThat(GrowthIdemKeys.backfillAccountMilestone(7L, "FIRST_COLLECT_CONTENT"))
                .isEqualTo("BACKFILL142:7:FIRST_COLLECT_CONTENT");
        // 与 1.4.0 补算键 BACKFILL:{u}、运行时键 MILESTONE_ACCT:{u}:{flag} 互不相撞。
        assertThat(GrowthIdemKeys.backfillAccountMilestone(7L, "FIRST_FOLLOW_USER"))
                .isNotEqualTo(GrowthIdemKeys.backfill(7L))
                .doesNotStartWith(GrowthIdemKeys.backfill(7L) + ":")
                .isNotEqualTo(GrowthIdemKeys.milestoneAccount(7L, "FIRST_FOLLOW_USER"));
        assertThat(GrowthIdemKeys.backfillAccountMilestone(Long.MAX_VALUE, "X".repeat(32)).length())
                .isLessThanOrEqualTo(GrowthIdemKeys.MAX_LENGTH);
    }

    @Test
    void longestKeysFitTheColumn() {
        String longest = GrowthIdemKeys.milestone(Long.MAX_VALUE, "X".repeat(32), Long.MAX_VALUE);
        assertThat(longest.length()).isLessThanOrEqualTo(GrowthIdemKeys.MAX_LENGTH);
        assertThat(GrowthIdemKeys.reverse(Long.MAX_VALUE, "REFUND:" + "r".repeat(64)).length())
                .isLessThanOrEqualTo(GrowthIdemKeys.MAX_LENGTH);
    }

    /** V1.5.0 onboarding-recall Story 2.4 · AC5：新手毕业补算键，与迁移 SQL 拼法一致、与其他补算前缀不撞。 */
    @org.junit.jupiter.api.Test
    void backfillNewbieKey() {
        org.assertj.core.api.Assertions.assertThat(GrowthIdemKeys.backfillNewbie(7L)).isEqualTo("BACKFILL150:7:LULUS_PEMULA");
        org.assertj.core.api.Assertions.assertThat(GrowthIdemKeys.backfillNewbie(7L))
                .isNotEqualTo(GrowthIdemKeys.backfillAccountMilestone(7L, "LULUS_PEMULA"))
                .isNotEqualTo(GrowthIdemKeys.milestoneAccount(7L, "LULUS_PEMULA"));
    }
}
