package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.OpsAudienceQuery;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import com.tailtopia.ops.audience.ActivityBand;
import com.tailtopia.ops.audience.AudienceFilters;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.support.ApiIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（真库）：V1.4.1 运营推送四维人群条件 —— 等级档 / 宠物类型 / 活跃档 / 付费。
 * 运营通知（{@link OpsAudienceQuery#resolve}）与开屏弹窗（{@link OpsAudienceQuery#matches}）走同一段 SQL，两边都验。
 *
 * <p>共享测试库里有别的用例留下的用户，所以只断言「本用例造的人」是否命中，不断言总人数。
 */
class OpsAudienceTargetingIntegrationTest extends ApiIntegrationTest {

    @Autowired private OpsAudienceQuery audienceQuery;
    @Autowired private JdbcTemplate jdbc;

    private void level(long userId, int level) {
        jdbc.update("INSERT INTO growth_accounts (user_id, lifetime_total, level, version, created_at, updated_at)"
                + " VALUES (?, 0, ?, 0, now(), now())", userId, level);
    }

    private void lastActive(long userId, Instant at) {
        jdbc.update("UPDATE users SET last_active_at = ? WHERE id = ?", at == null ? null : Timestamp.from(at), userId);
    }

    private void pet(long ownerId, String type) {
        jdbc.update("INSERT INTO pet_profiles (owner_id, name, pet_type, card_token, created_at, updated_at) "
                + "VALUES (?, 'Mochi', ?, ?, now(), now())", ownerId, type, "aud-it-" + SEQ.incrementAndGet());
    }

    private void intent(long userId, String channel, String status) {
        jdbc.update("INSERT INTO payment_intents (public_token, user_id, purpose, channel, amount, currency, status,"
                + " version, created_at, updated_at) VALUES (?, ?, 'PAWCOIN_TOPUP', ?, 10000, 'IDR', ?, 0, now(), now())",
                "aud" + SEQ.incrementAndGet(), userId, channel, status);
    }

    private Set<Long> resolve(String json) {
        return audienceQuery.resolve(AudienceSpec.parse(json), 0L).stream()
                .map(OpsRecipient::userId).collect(Collectors.toSet());
    }

    private boolean matches(User u, AudienceFilters f) {
        return audienceQuery.matches(u.getId(), f, Instant.now());
    }

    @Test
    void levelTierFollowsGrowthLevelsTableAndMissingAccountIsNewbie() {
        User noAccount = newUser();
        User lv4 = newUser();
        User lv5 = newUser();
        User lv15 = newUser();
        level(lv4.getId(), 4);
        level(lv5.getId(), 5);
        level(lv15.getId(), 15);

        Set<Long> newbie = resolve("{\"mode\":\"FILTER\",\"levelTiers\":[\"NEWBIE\"]}");
        assertThat(newbie).contains(noAccount.getId(), lv4.getId()).doesNotContain(lv5.getId(), lv15.getId());
        Set<Long> buddyOrStar = resolve("{\"mode\":\"FILTER\",\"levelTiers\":[\"BUDDY\",\"STAR\"]}");
        assertThat(buddyOrStar).contains(lv5.getId(), lv15.getId()).doesNotContain(noAccount.getId(), lv4.getId());

        AudienceFilters guardian = new AudienceFilters(Set.of(GrowthTier.GUARDIAN), null, null, null);
        assertThat(matches(lv15, guardian)).isFalse();
        assertThat(matches(lv5, new AudienceFilters(Set.of(GrowthTier.BUDDY), null, null, null))).isTrue();
    }

    @Test
    void activityBands() {
        Instant now = Instant.now();
        User d3 = newUser();
        User d10 = newUser();
        User d20 = newUser();
        User never = newUser();
        lastActive(d3.getId(), now.minus(Duration.ofDays(3)));
        lastActive(d10.getId(), now.minus(Duration.ofDays(10)));
        lastActive(d20.getId(), now.minus(Duration.ofDays(20)));
        lastActive(never.getId(), null);

        assertThat(resolve("{\"mode\":\"FILTER\",\"activity\":[\"WITHIN_7D\"]}"))
                .contains(d3.getId()).doesNotContain(d10.getId(), d20.getId(), never.getId());
        assertThat(resolve("{\"mode\":\"FILTER\",\"activity\":[\"WITHIN_15D\"]}"))
                .contains(d3.getId(), d10.getId()).doesNotContain(d20.getId(), never.getId());
        assertThat(resolve("{\"mode\":\"FILTER\",\"activity\":[\"OTHER\"]}"))
                .contains(d20.getId(), never.getId()).doesNotContain(d3.getId(), d10.getId());
        assertThat(resolve("{\"mode\":\"FILTER\",\"activity\":[\"WITHIN_7D\",\"OTHER\"]}"))
                .contains(d3.getId(), d20.getId(), never.getId()).doesNotContain(d10.getId());

        assertThat(matches(never, new AudienceFilters(null, null, Set.of(ActivityBand.OTHER), null))).isTrue();
        assertThat(matches(d3, new AudienceFilters(null, null, Set.of(ActivityBand.OTHER), null))).isFalse();
    }

    @Test
    void paidMeansAnySuccessfulCashPaymentNotPawcoinOnly() {
        User qrisPaid = newUser();
        User pawcoinOnly = newUser();
        User pendingOnly = newUser();
        User nothing = newUser();
        intent(qrisPaid.getId(), "QRIS", "PAID");
        intent(pawcoinOnly.getId(), "PAWCOIN", "PAID");
        intent(pendingOnly.getId(), "QRIS", "PENDING");

        assertThat(resolve("{\"mode\":\"FILTER\",\"paid\":true}"))
                .contains(qrisPaid.getId()).doesNotContain(pawcoinOnly.getId(), pendingOnly.getId(), nothing.getId());
        assertThat(resolve("{\"mode\":\"FILTER\",\"paid\":false}"))
                .contains(pawcoinOnly.getId(), pendingOnly.getId(), nothing.getId()).doesNotContain(qrisPaid.getId());
        assertThat(matches(qrisPaid, new AudienceFilters(null, null, null, true))).isTrue();
        assertThat(matches(nothing, new AudienceFilters(null, null, null, true))).isFalse();
    }

    @Test
    void dimensionsAreAndedAndBaseFilterApplies() {
        User match = newUser();
        User wrongPet = newUser();
        User virt = users.save(User.newVirtual("virtual:aud-" + SEQ.incrementAndGet(), "马甲", null, 1L));
        for (User u : new User[] {match, wrongPet, virt}) {
            level(u.getId(), 6);
            intent(u.getId(), "QRIS", "PAID");
            lastActive(u.getId(), Instant.now().minus(Duration.ofDays(1)));
        }
        pet(match.getId(), "CAT");
        pet(wrongPet.getId(), "DOG");
        pet(virt.getId(), "CAT");

        String json = "{\"mode\":\"FILTER\",\"levelTiers\":[\"BUDDY\"],\"species\":[\"CAT\"],"
                + "\"activity\":[\"WITHIN_7D\"],\"paid\":true}";
        assertThat(resolve(json)).contains(match.getId()).doesNotContain(wrongPet.getId(), virt.getId());
        AudienceFilters f = AudienceSpec.parse(json).filters();
        assertThat(audienceQuery.count(AudienceSpec.parse(json), null)).isGreaterThanOrEqualTo(1);
        assertThat(matches(match, f)).isTrue();
        assertThat(matches(wrongPet, f)).isFalse();
        assertThat(matches(virt, f)).as("虚拟号不在基础过滤内").isFalse();
        assertThat(matches(wrongPet, new AudienceFilters(null, Set.of(PetType.DOG), null, null))).isTrue();
    }

    @Test
    void emptyFiltersMatchEveryone() {
        User u = newUser();
        assertThat(audienceQuery.matches(u.getId(), AudienceFilters.none(), Instant.now())).isTrue();
    }
}
