package com.tailtopia.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.notify.ops.domain.AudienceSpec;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：运营通知人群 SQL 生成（V1.3.4 Story 6.2 · AC1 · D-16）。纯函数，不连库。
 */
class OpsAudienceSqlTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final String BASE = "u.role = 'USER' AND u.account_type = 'REAL' AND u.deleted_at IS NULL "
            + "AND u.enabled = TRUE AND u.status = 'ACTIVE'";

    private static OpsAudienceSql.Query build(String json) {
        return OpsAudienceSql.build(AudienceSpec.parse(json), 42L, WIB);
    }

    @Test
    @DisplayName("ALL：只有基础五条件，无参数，按 id 升序")
    void allHasOnlyBaseFilter() {
        OpsAudienceSql.Query q = build("{\"mode\":\"ALL\"}");
        assertThat(q.sql()).isEqualTo("SELECT u.id, u.locale FROM users u WHERE " + BASE + " ORDER BY u.id ASC");
        assertThat(q.params()).isEmpty();
    }

    @Test
    @DisplayName("基础过滤与 findLifecyclePushCandidates 同口径（role / account_type / deleted_at / enabled / status）")
    void baseFilterMatchesLifecycleCandidates() {
        assertThat(OpsAudienceSql.BASE_FILTER).isEqualTo(BASE);
    }

    @Test
    @DisplayName("FILTER 空规格 = ALL")
    void emptyFilterEqualsAll() {
        assertThat(build("{\"mode\":\"FILTER\"}")).isEqualTo(build("{\"mode\":\"ALL\"}"));
    }

    @Test
    @DisplayName("IDS：含子表子查询 + 基础过滤，不含任何筛选条件（JSON 里的筛选键由 AudienceSpec 在 IDS 模式下丢弃）")
    void idsUsesTargetsOnly() {
        OpsAudienceSql.Query q = build("{\"mode\":\"IDS\",\"hasPet\":true,\"hasPhone\":false}");
        assertThat(q.sql()).contains(BASE)
                .contains("u.id IN (SELECT t.user_id FROM ops_notification_campaign_targets t "
                        + "WHERE t.campaign_id = :campaignId)")
                .doesNotContain("pet_profiles")
                .doesNotContain("phone")
                .doesNotContain("shop_orders")
                .doesNotContain("created_at")
                .doesNotContain("last_active_at");
        assertThat(q.params()).containsExactlyEntriesOf(java.util.Map.of("campaignId", 42L));
    }

    @Test
    @DisplayName("注册日期按 WIB 自然日含两端：from → >= 当天 00:00 WIB；to → < 次日 00:00 WIB")
    void registeredDatesInWib() {
        OpsAudienceSql.Query q = build(
                "{\"mode\":\"FILTER\",\"registeredFrom\":\"2026-10-01\",\"registeredTo\":\"2026-10-12\"}");
        assertThat(q.params().get("regFrom")).isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
        assertThat(q.params().get("regToExcl")).isEqualTo(Instant.parse("2026-10-12T17:00:00Z"));
        assertThat(q.sql()).contains("u.created_at >= :regFrom").contains("u.created_at < :regToExcl")
                .doesNotContain("<= :regToExcl");
    }

    @Test
    @DisplayName("活跃日期取 last_active_at，同 WIB 口径；不含 last_active_at IS NULL 放行分支")
    void activeDatesExcludeNull() {
        OpsAudienceSql.Query q = build(
                "{\"mode\":\"FILTER\",\"activeFrom\":\"2026-10-01\",\"activeTo\":\"2026-10-12\"}");
        assertThat(q.params().get("actFrom")).isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
        assertThat(q.params().get("actToExcl")).isEqualTo(Instant.parse("2026-10-12T17:00:00Z"));
        assertThat(q.sql()).contains("u.last_active_at >= :actFrom").contains("u.last_active_at < :actToExcl")
                .doesNotContain("last_active_at IS NULL");
    }

    @Test
    @DisplayName("hasPet true / false → EXISTS / NOT EXISTS pet_profiles")
    void hasPet() {
        String pet = "EXISTS (SELECT 1 FROM pet_profiles p WHERE p.owner_id = u.id)";
        assertThat(build("{\"mode\":\"FILTER\",\"hasPet\":true}").sql()).contains(" AND " + pet)
                .doesNotContain("NOT EXISTS");
        assertThat(build("{\"mode\":\"FILTER\",\"hasPet\":false}").sql()).contains(" AND NOT " + pet);
    }

    @Test
    @DisplayName("species=[CAT] 绑参为 [\"CAT\"]")
    void speciesBinding() {
        OpsAudienceSql.Query q = build("{\"mode\":\"FILTER\",\"species\":[\"CAT\"]}");
        assertThat(q.sql()).contains("p.pet_type IN (:species)");
        assertThat(q.params().get("species")).isEqualTo(List.of("CAT"));
    }

    @Test
    @DisplayName("hasShopOrder=true：已支付状态恰 6 个，不含 PENDING_PAYMENT / CANCELLED；false → NOT EXISTS")
    void hasShopOrderPaidOnly() {
        assertThat(OpsAudienceSql.PAID_ORDER_STATUSES).containsExactlyInAnyOrder(
                "PENDING_SHIPMENT", "SHIPPED", "DELIVERED", "COMPLETED", "REFUNDING", "REFUNDED");
        String sql = build("{\"mode\":\"FILTER\",\"hasShopOrder\":true}").sql();
        assertThat(sql).contains("EXISTS (SELECT 1 FROM shop_orders o WHERE o.user_id = u.id AND o.status IN ("
                + "'PENDING_SHIPMENT','SHIPPED','DELIVERED','COMPLETED','REFUNDING','REFUNDED'))")
                .doesNotContain("PENDING_PAYMENT").doesNotContain("CANCELLED")
                .doesNotContain("NOT EXISTS");
        assertThat(build("{\"mode\":\"FILTER\",\"hasShopOrder\":false}").sql())
                .contains(" AND NOT EXISTS (SELECT 1 FROM shop_orders o");
    }

    @Test
    @DisplayName("hasPhone true / false：非空且去空白非空 / IS NULL OR btrim 为空")
    void hasPhone() {
        assertThat(build("{\"mode\":\"FILTER\",\"hasPhone\":true}").sql())
                .contains("(u.phone IS NOT NULL AND btrim(u.phone) <> '')");
        assertThat(build("{\"mode\":\"FILTER\",\"hasPhone\":false}").sql())
                .contains("(u.phone IS NULL OR btrim(u.phone) = '')");
    }

    @Test
    @DisplayName("多条件之间为「且」")
    void conditionsAreAnded() {
        OpsAudienceSql.Query q = build("{\"mode\":\"FILTER\",\"hasPet\":true,\"hasPhone\":true,"
                + "\"registeredFrom\":\"2026-10-01\"}");
        assertThat(q.sql()).doesNotContain(" OR u.").doesNotContain(" OR EXISTS");
        assertThat(q.sql().split(" AND ").length).isGreaterThanOrEqualTo(8);
    }
}
