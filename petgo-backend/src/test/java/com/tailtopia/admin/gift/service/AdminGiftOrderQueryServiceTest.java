package com.tailtopia.admin.gift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.gift.dto.GiftOrderAdminRow;
import com.tailtopia.admin.gift.dto.GiftOrderSummary;
import com.tailtopia.admin.gift.service.AdminGiftOrderQueryService.Filter;
import com.tailtopia.admin.gift.service.AdminGiftOrderQueryService.StatusGroup;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * L0：后台送礼记录查询（V1.4.0 ③ gift Story 3.2 · AC2 / AC3 / AC6）。mock {@link NamedParameterJdbcTemplate}，
 * 钉筛选参数映射、查询次数（禁 N+1）与显示口径。
 */
class AdminGiftOrderQueryServiceTest {

    private static final Filter NONE = new Filter(null, null, null, null, null, null);

    static GiftOrderAdminRow row(String status, String channel, String intentToken) {
        return new GiftOrderAdminRow(Instant.parse("2026-10-17T06:20:00Z"), 10233L, "Andi", false, 10241L, "Rina",
                false, "Mochi", false, "Salmon", "Salmon ID", "https://cdn.example/gift.png", 5000L, 2, 10000L, channel, status, null, 0, null,
                intentToken);
    }

    // ——————————————————— 筛选参数映射 ———————————————————

    @Test
    @SuppressWarnings("unchecked")
    void undeliveredMeansTheThreeAnomalyStatuses() {
        assertThat(StatusGroup.parse("UNDELIVERED").statuses()).extracting(Enum::name)
                .containsExactlyInAnyOrder("UNDELIVERED_REFUND_PENDING", "UNDELIVERED_REFUNDED", "REFUND_MANUAL");
        assertThat(StatusGroup.parse("delivered")).isEqualTo(StatusGroup.DELIVERED);
        var w = AdminGiftOrderQueryService.where(new Filter(null, null, null, StatusGroup.UNDELIVERED, null, null));
        assertThat(w.sql()).isEqualTo(" WHERE o.status IN (:statuses)");
        assertThat((List<Object>) w.params().getValue("statuses"))
                .containsExactly("REFUND_MANUAL", "UNDELIVERED_REFUNDED", "UNDELIVERED_REFUND_PENDING");
        assertThat(new Filter(null, null, null, StatusGroup.UNDELIVERED, null, null).anomalyView()).isTrue();
        assertThat(NONE.anomalyView()).isFalse();
    }

    @Test
    void unknownChannelOrStatusMeansUnrestricted() {
        assertThat(StatusGroup.parse("REFUND_MANUAL")).as("只认五个组，不认单个异常状态").isNull();
        assertThat(StatusGroup.parse("bogus")).isNull();
        assertThat(StatusGroup.parse("  ")).isNull();
        assertThat(StatusGroup.parse(null)).isNull();
        assertThat(AdminGiftOrderQueryService.parseChannel("MIXED")).as("送礼只有两个渠道").isNull();
        assertThat(AdminGiftOrderQueryService.parseChannel("x")).isNull();
        assertThat(AdminGiftOrderQueryService.parseChannel("qris")).isEqualTo("QRIS");
        assertThat(AdminGiftOrderQueryService.parseChannel("PAWCOIN")).isEqualTo("PAWCOIN");
        var w = AdminGiftOrderQueryService.where(NONE);
        assertThat(w.sql()).as("无筛选 = 无 WHERE").isEmpty();
        assertThat(w.params().getParameterNames()).isEmpty();
    }

    @Test
    void dateRangeIsWibAndEndDateIsInclusive() {
        var w = AdminGiftOrderQueryService.where(new Filter(7L, 8L, "QRIS", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 17)));
        assertThat(w.sql()).isEqualTo(" WHERE o.sender_id = :senderId AND o.owner_id = :ownerId"
                + " AND o.channel = :channel AND o.created_at >= :fromTs AND o.created_at < :toTs");
        assertThat(w.params().getValue("senderId")).isEqualTo(7L);
        assertThat(w.params().getValue("ownerId")).isEqualTo(8L);
        assertThat(w.params().getValue("channel")).isEqualTo("QRIS");
        // 2026-10-01 00:00 WIB = 2026-09-30 17:00Z；止日含当天 → 2026-10-18 00:00 WIB = 2026-10-17 17:00Z
        assertThat(((Timestamp) w.params().getValue("fromTs")).toInstant())
                .isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
        assertThat(((Timestamp) w.params().getValue("toTs")).toInstant())
                .isEqualTo(Instant.parse("2026-10-17T17:00:00Z"));
    }

    @Test
    void whereNeverConcatenatesValues() {
        var w = AdminGiftOrderQueryService.where(new Filter(123456789L, 987654321L, "PAWCOIN",
                StatusGroup.DELIVERED, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)));
        assertThat(w.sql()).doesNotContain("123456789").doesNotContain("987654321").doesNotContain("PAWCOIN")
                .doesNotContain("DELIVERED").doesNotContain("2026");
    }

    // ——————————————————— 查询次数（禁 N+1） ———————————————————

    @Test
    @SuppressWarnings("unchecked")
    void onePageOfTwentyIsTwoStatementsAndSummaryIsOne() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        List<GiftOrderAdminRow> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(row("DELIVERED", "PAWCOIN", null));
        }
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(twenty);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(45L);
        var svc = new AdminGiftOrderQueryService(jdbc);

        Page<GiftOrderAdminRow> page = svc.search(new Filter(1L, null, null, null, null, null), 1, 20);

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getTotalElements()).isEqualTo(45L);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getNumber()).isEqualTo(1);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(1)).query(sql.capture(), params.capture(), any(RowMapper.class));
        verify(jdbc, times(1)).queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class));
        assertThat(sql.getValue()).contains("LEFT JOIN users s").contains("LEFT JOIN users w")
                .contains("LEFT JOIN payment_intents pi").contains("ORDER BY o.created_at DESC, o.id DESC")
                .as("宠物名取订单快照，不 JOIN 宠物表").doesNotContain("pet_profiles");
        assertThat(params.getValue().getValue("limit")).isEqualTo(20);
        assertThat(params.getValue().getValue("offset")).isEqualTo(20L);

        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(new GiftOrderSummary(3, 2, 10000, 5000, 1, 20000, 0, 1));
        GiftOrderSummary s = svc.summarize(NONE);
        assertThat(s.orders()).isEqualTo(3);
        verify(jdbc, times(1)).queryForObject(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchAllPassesTheLimitAndIsOneStatement() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        new AdminGiftOrderQueryService(jdbc).searchAll(NONE, 5001);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(1)).query(anyString(), params.capture(), any(RowMapper.class));
        assertThat(params.getValue().getValue("limit")).isEqualTo(5001);
    }

    // ——————————————————— 显示口径 ———————————————————

    @Test
    void deletedUsersHaveNoNameAndNullPetMeansDeletedPet() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-16T13:10:00Z")));
        when(rs.getLong("sender_id")).thenReturn(10233L);
        when(rs.getBoolean("s_deleted")).thenReturn(true);
        when(rs.getString("s_name")).thenReturn("注销前快照不该出现");
        when(rs.getLong("owner_id")).thenReturn(10301L);
        when(rs.getBoolean("w_deleted")).thenReturn(false);
        when(rs.getString("w_name")).thenReturn("Dewi");
        when(rs.getObject("pet_id")).thenReturn(null);
        when(rs.getString("pet_name")).thenReturn("Boba");
        when(rs.getString("gift_name_en")).thenReturn("Tennis ball");
        when(rs.getString("gift_name_id")).thenReturn("Bola tenis");
        when(rs.getLong("unit_price")).thenReturn(10000L);
        when(rs.getInt("quantity")).thenReturn(2);
        when(rs.getLong("total")).thenReturn(20000L);
        when(rs.getString("channel")).thenReturn("QRIS");
        when(rs.getString("status")).thenReturn("UNDELIVERED_REFUND_PENDING");
        when(rs.getString("undelivered_reason")).thenReturn("SENDER_BLOCKED");
        when(rs.getInt("refund_attempts")).thenReturn(2);
        when(rs.getString("intent_token")).thenReturn("pi8d0aZ9kLmN3a3b");

        GiftOrderAdminRow r = AdminGiftOrderQueryService.mapRow(rs, 0);

        assertThat(r.senderDeleted()).isTrue();
        assertThat(r.senderName()).as("注销送礼人不带注销前昵称（AC6.1）").isNull();
        assertThat(r.ownerName()).isEqualTo("Dewi");
        assertThat(r.petDeleted()).isTrue();
        assertThat(r.petName()).isEqualTo("Boba");
        assertThat(r.statusGroup()).isEqualTo("UNDELIVERED");
        assertThat(r.statusBadge()).isEqualTo("badge-warn");
        assertThat(r.refundedAt()).isNull();
        assertThat(r.giftName(Locale.forLanguageTag("id"))).isEqualTo("Bola tenis");
        assertThat(r.giftName(Locale.SIMPLIFIED_CHINESE)).isEqualTo("Tennis ball");
        assertThat(r.giftName(Locale.ENGLISH)).isEqualTo("Tennis ball");
        assertThat(r.totalText()).isEqualTo("Rp20,000");
    }

    @Test
    void intentIsAbbreviatedAndPawCoinHasNone() {
        GiftOrderAdminRow qris = row("EXPIRED", "QRIS", "pi8d0aZ9kLmN3a3b");
        assertThat(qris.intentShort()).isEqualTo("pi8d0a…a3b");
        assertThat(qris.statusBadge()).isEqualTo("badge-muted");
        assertThat(row("DELIVERED", "PAWCOIN", null).intentShort()).as("PawCoin 单显示「—」").isNull();
        assertThat(row("DELIVERED", "PAWCOIN", null).statusBadge()).isEqualTo("badge-ok");
        assertThat(row("PENDING", "QRIS", "short").intentShort()).isEqualTo("short");
        assertThat(row("PENDING", "QRIS", "x").statusBadge()).isEqualTo("badge-neutral");
        assertThat(row("FAILED", "QRIS", "x").statusGroup()).isEqualTo("FAILED");
        assertThat(row("REFUND_MANUAL", "QRIS", "x").statusGroup()).isEqualTo("UNDELIVERED");
        assertThat(row("UNDELIVERED_REFUNDED", "QRIS", "x").statusGroup()).isEqualTo("UNDELIVERED");
    }

    @Test
    void summarizeNullResultIsEmpty() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        assertThat(new AdminGiftOrderQueryService(jdbc).summarize(NONE)).isEqualTo(GiftOrderSummary.EMPTY);
    }
}
