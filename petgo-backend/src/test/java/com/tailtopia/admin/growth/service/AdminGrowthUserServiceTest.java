package com.tailtopia.admin.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.growth.service.AdminGrowthUserService.Ledger;
import com.tailtopia.admin.growth.service.AdminGrowthUserService.LedgerRow;
import com.tailtopia.admin.growth.service.AdminGrowthUserService.State;
import com.tailtopia.admin.growth.service.AdminGrowthUserService.Tab;
import com.tailtopia.growth.domain.GrowthLevelDef;
import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.repository.GrowthLevelDefRepository;
import com.tailtopia.growth.service.AdminAdjustCommand;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.shared.error.AppException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** V1.4.0 ① Story 4.2 · AC2 / AC3 / AC7 / AC8 / AC9（L0）：用户抽屉成长值页签读模型 + 调整预览 / 审计。 */
class AdminGrowthUserServiceTest {

    private static final long U = 7L;
    private static final String TOKEN = "2f1c7d1e-0000-4000-8000-000000000001";

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GrowthLedgerService ledgerService = mock(GrowthLedgerService.class);
    private final GrowthLevelDefRepository levels = mock(GrowthLevelDefRepository.class);
    private final AdminAccountRepository adminAccounts = mock(AdminAccountRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final com.tailtopia.growth.service.GrowthDisplayLevelService displayLevels =
            mock(com.tailtopia.growth.service.GrowthDisplayLevelService.class);
    private final AdminGrowthUserService service = new AdminGrowthUserService(jdbc, ledgerService, levels,
            adminAccounts, audit, displayLevels);

    @BeforeEach
    void setUp() {
        when(levels.findAllByOrderByLevelAsc()).thenReturn(List.of(
                GrowthLevelDef.of(1, 0, GrowthTier.NEWBIE),
                GrowthLevelDef.of(2, 100, GrowthTier.NEWBIE),
                GrowthLevelDef.of(3, 300, GrowthTier.BUDDY),
                GrowthLevelDef.of(4, 1000, GrowthTier.GUARDIAN)));
        when(ledgerService.participates(U)).thenReturn(true);
        count(0);
    }

    // ------------------------------------------------------------------ 桩

    private static ResultSet rs(Map<String, Object> v) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenAnswer(i -> (String) v.get(i.<String>getArgument(0)));
        when(rs.getBoolean(anyString())).thenAnswer(i -> Boolean.TRUE.equals(v.get(i.<String>getArgument(0))));
        when(rs.getLong(anyString())).thenAnswer(i -> ((Number) v.get(i.<String>getArgument(0))).longValue());
        when(rs.getInt(anyString())).thenAnswer(i -> ((Number) v.get(i.<String>getArgument(0))).intValue());
        when(rs.getObject(anyString(), eq(Long.class))).thenAnswer(i -> {
            Object o = v.get(i.<String>getArgument(0));
            return o == null ? null : ((Number) o).longValue();
        });
        when(rs.getObject(anyString(), eq(Integer.class))).thenAnswer(i -> {
            Object o = v.get(i.<String>getArgument(0));
            return o == null ? null : ((Number) o).intValue();
        });
        when(rs.getTimestamp(anyString())).thenAnswer(i -> (Timestamp) v.get(i.<String>getArgument(0)));
        when(rs.getArray(anyString())).thenAnswer(i -> {
            Object o = v.get(i.<String>getArgument(0));
            if (o == null) {
                return null;
            }
            Array a = mock(Array.class);
            when(a.getArray()).thenReturn(o);
            return a;
        });
        return rs;
    }

    @SuppressWarnings("unchecked")
    private void user(String accountType, String status, boolean deleted, Long total, Integer level,
            Integer displayLevel) {
        user(accountType, "USER", status, deleted, total, level, displayLevel);
    }

    @SuppressWarnings("unchecked")
    private void user(String accountType, String role, String status, boolean deleted, Long total, Integer level,
            Integer displayLevel) {
        Map<String, Object> v = new HashMap<>();
        v.put("account_type", accountType);
        v.put("role", role);
        v.put("status", status);
        v.put("deleted", deleted);
        v.put("name", "Ana");
        v.put("lifetime_total", total);
        v.put("level", level);
        v.put("display_level", displayLevel);
        // doAnswer 形式：同一用例里再桩一次时不会先把上一个 Answer 用 null 实参跑一遍
        doAnswer(i -> List.of(((RowMapper<Object>) i.getArgument(2)).mapRow(rs(v), 0))).when(jdbc)
                .query(eq(AdminGrowthUserService.USER_SQL), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    private void count(long n) {
        when(jdbc.queryForObject(startsWith(AdminGrowthUserService.COUNT_SELECT), any(MapSqlParameterSource.class),
                eq(Long.class))).thenReturn(n);
    }

    @SuppressWarnings("unchecked")
    private void page(List<Map<String, Object>> rows) {
        doAnswer(i -> {
            RowMapper<Object> m = i.getArgument(2);
            List<Object> out = new ArrayList<>();
            for (int k = 0; k < rows.size(); k++) {
                out.add(m.mapRow(rs(rows.get(k)), k));
            }
            return out;
        }).when(jdbc).query(startsWith(AdminGrowthUserService.PAGE_SELECT), any(MapSqlParameterSource.class),
                any(RowMapper.class));
    }

    private static Map<String, Object> row(long id, String rule, int delta) {
        Map<String, Object> v = new HashMap<>();
        v.put("id", id);
        v.put("rule_code", rule);
        v.put("delta", delta);
        v.put("src_idem", rule + ":" + id);
        v.put("created_at", Timestamp.from(Instant.parse("2026-10-18T05:00:00Z").minusSeconds(id)));
        return v;
    }

    private static AdminAccount admin(long id, String name) {
        AdminAccount a = AdminAccount.create(name + "@x", name, AdminRole.OPS_MANAGER, 1L);
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    // ------------------------------------------------------------------ 页签四态

    @Test
    void realUserOverviewWithoutAccountRowIsLv1Zero() {
        user("REAL", "ACTIVE", false, null, null, null);

        Tab t = service.tab(U);

        assertThat(t.state()).isEqualTo(State.REAL);
        assertThat(t.level()).isEqualTo(1);
        assertThat(t.lifetimeTotal()).isZero();
        assertThat(t.tier()).isEqualTo(GrowthTier.NEWBIE);
        assertThat(t.nextLevel()).isEqualTo(2);
        assertThat(t.remaining()).isEqualTo(100);
        assertThat(t.maxed()).isFalse();
        assertThat(t.adjustBlockedKey()).isNull();
        assertThat(t.ledger().rows()).isEmpty();
        assertThat(t.ledger().everAny()).isFalse();
    }

    @Test
    void realUserOverviewMidLevelAndMaxed() {
        user("REAL", "ACTIVE", false, 420L, 3, null);
        Tab mid = service.tab(U);
        assertThat(mid.tier()).isEqualTo(GrowthTier.BUDDY);
        assertThat(mid.nextLevel()).isEqualTo(4);
        assertThat(mid.remaining()).isEqualTo(580);

        user("REAL", "ACTIVE", false, 5000L, 4, null);
        Tab top = service.tab(U);
        assertThat(top.maxed()).isTrue();
        assertThat(top.remaining()).isNull();
        assertThat(top.tier()).isEqualTo(GrowthTier.GUARDIAN);
    }

    @Test
    void deactivatedUserStillShowsOverviewButAdjustIsBlocked() {
        user("REAL", "DEACTIVATED", false, 120L, 2, null);
        when(ledgerService.participates(U)).thenReturn(false);

        Tab t = service.tab(U);

        assertThat(t.state()).isEqualTo(State.REAL);
        assertThat(t.deactivated()).isTrue();
        assertThat(t.adjustBlockedKey()).isEqualTo("admin.v140.growth.adjust.deactivated");
        assertThat(t.ledger()).isNotNull();
    }

    @Test
    void virtualShowsDisplayLevelOnlyAndNoLedger() {
        user("VIRTUAL", "ACTIVE", false, null, null, 3);

        Tab t = service.tab(U);

        assertThat(t.virtual()).isTrue();
        assertThat(t.displayLevel()).isEqualTo(3);
        assertThat(t.displayTier()).isEqualTo(GrowthTier.BUDDY);
        assertThat(t.ledger()).isNull();
        verify(jdbc, never()).queryForObject(startsWith(AdminGrowthUserService.COUNT_SELECT),
                any(MapSqlParameterSource.class), eq(Long.class));
    }

    @Test
    void virtualWithoutDisplayLevelShowsDash() {
        user("VIRTUAL", "ACTIVE", false, null, null, null);
        Tab t = service.tab(U);
        assertThat(t.displayLevel()).isNull();
        assertThat(t.displayTier()).isNull();
    }

    @Test
    void deletedUserHasNoDataAndLedgerEndpointIsEmpty() {
        user("REAL", "ACTIVE", true, null, null, null);

        assertThat(service.tab(U).deleted()).isTrue();
        Ledger l = service.ledger(U, null, null, null, null);
        assertThat(l.rows()).isEmpty();
        assertThat(l.everAny()).isFalse();
    }

    @Test
    void virtualLedgerEndpointIsEmpty() {
        user("VIRTUAL", "ACTIVE", false, null, null, 5);
        assertThat(service.ledger(U, null, null, null, null).rows()).isEmpty();
    }

    // ------------------------------------------------------------------ 明细

    @Test
    void ruleLabelsAcrossKinds() {
        user("REAL", "ACTIVE", false, 100L, 2, null);
        count(5);
        Map<String, Object> normal = row(1, "DAILY_LOGIN", 2);
        Map<String, Object> reversal = row(2, "POST_PUBLISHED", -5);
        reversal.put("reverses_id", 9L);
        reversal.put("reason", "TAKEDOWN");
        Map<String, Object> adjust = row(3, "ADMIN_ADJUST", -10);
        adjust.put("reason", "ADMIN_ADJUST");
        adjust.put("note", "客诉补偿撤回");
        adjust.put("operator_admin_id", 50L);
        Map<String, Object> campaign = row(4, "OPS_CAMPAIGN", 50);
        campaign.put("note", "周年庆");
        campaign.put("operator_admin_id", 51L);
        Map<String, Object> backfill = row(5, "LAUNCH_BACKFILL", 300);
        page(List.of(normal, reversal, adjust, campaign, backfill));
        when(adminAccounts.findAllById(any())).thenReturn(List.of(admin(50, "Ana")));

        List<LedgerRow> rows = service.ledger(U, null, null, null, null).rows();

        assertThat(rows.get(0).reversalReason()).isNull();
        assertThat(rows.get(0).ruleCode()).isEqualTo("DAILY_LOGIN");
        assertThat(rows.get(0).signedDelta()).isEqualTo("+2");
        assertThat(rows.get(1).reversalReason()).isEqualTo("TAKEDOWN");
        assertThat(rows.get(1).signedDelta()).isEqualTo("-5");
        // 运营调整的 reason = ADMIN_ADJUST 不是扣回
        assertThat(rows.get(2).reversalReason()).isNull();
        assertThat(rows.get(2).operator()).isEqualTo("Ana");
        assertThat(rows.get(2).note()).isEqualTo("客诉补偿撤回");
        // 操作人账号已不存在 → #id 兜底
        assertThat(rows.get(3).operator()).isEqualTo("#51");
        assertThat(rows.get(4).ruleCode()).isEqualTo("LAUNCH_BACKFILL");
    }

    @Test
    void refLinksUseIntentTokenNeverNumericIdAndBatchLookupsRunOncePerPage() {
        user("REAL", "ACTIVE", false, 100L, 2, null);
        count(45);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            Map<String, Object> r;
            switch (i % 4) {
                case 0 -> {
                    r = row(i, "SPEND_DIGITAL", 10);
                    r.put("ref_type", "VET_CONSULT");
                    r.put("ref_id", String.valueOf(1000 + i));
                    r.put("src_idem", "SPEND:PI:" + (1000 + i));
                }
                case 1 -> {
                    r = row(i, "POST_PUBLISHED", 5);
                    r.put("ref_type", "CONTENT_POST");
                    r.put("ref_id", String.valueOf(500 + i));
                    r.put("pet_ids", new Long[] {3L, 4L});
                }
                case 2 -> {
                    r = row(i, "OPS_CAMPAIGN", 5);
                    r.put("note", "活动");
                    r.put("operator_admin_id", (long) (50 + i % 3));
                }
                default -> {
                    r = row(i, "SPEND_DIGITAL", 3);
                    r.put("ref_type", "AI_UNLOCK");
                    r.put("ref_id", "77");
                    r.put("src_idem", "SPEND:COIN:coin-key");
                }
            }
            rows.add(r);
        }
        page(rows);
        when(adminAccounts.findAllById(any())).thenReturn(List.of(admin(50, "Ana"), admin(51, "Budi")));
        when(jdbc.queryForList(eq(AdminGrowthUserService.PET_NAMES_SQL), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("id", 3L, "name", "Mochi")));
        List<Map<String, Object>> tokens = new ArrayList<>();
        for (int i = 4; i <= 20; i += 4) {
            tokens.add(Map.of("id", (long) (1000 + i), "public_token", "pi_tok" + i));
        }
        when(jdbc.queryForList(eq(AdminGrowthUserService.INTENT_TOKENS_SQL), any(MapSqlParameterSource.class)))
                .thenReturn(tokens);

        Ledger l = service.ledger(U, null, null, null, "1");

        assertThat(l.rows()).hasSize(20);
        assertThat(l.page()).isEqualTo(1);
        assertThat(l.totalPages()).isEqualTo(3);
        // 🛡 禁 N+1：一页 20 行，三类补查各 1 次
        verify(adminAccounts, times(1)).findAllById(any());
        verify(jdbc, times(1)).queryForList(eq(AdminGrowthUserService.PET_NAMES_SQL), any(MapSqlParameterSource.class));
        verify(jdbc, times(1)).queryForList(eq(AdminGrowthUserService.INTENT_TOKENS_SQL),
                any(MapSqlParameterSource.class));

        LedgerRow pay = l.rows().get(3);
        assertThat(pay.refHref()).isEqualTo("/admin/payments/pi_tok4/drawer");
        assertThat(pay.refShown()).isEqualTo("pi_tok4");
        assertThat(pay.refTypeKey()).isEqualTo("admin.payments.purpose.VET_CONSULT");
        LedgerRow post = l.rows().get(0);
        assertThat(post.refHref()).isEqualTo("/admin/content/501/drawer");
        assertThat(post.refTypeKey()).isEqualTo("admin.v140.growth.ledger.ref.CONTENT_POST");
        // 宠物 4 已删 → 只剩 Mochi
        assertThat(post.petNames()).isEqualTo("Mochi");
        LedgerRow coin = l.rows().get(2);
        assertThat(coin.refHref()).isNull();
        assertThat(coin.refShown()).isEqualTo("77");
        for (LedgerRow r : l.rows()) {
            if (r.refHref() != null && r.refHref().startsWith("/admin/payments/")) {
                assertThat(r.refHref()).doesNotContainPattern("/10\\d\\d/");
            }
        }
        // 支付 token 批量查询的入参是 intentId（来自原入账幂等键），不是 ref_id 猜的
        ArgumentCaptor<MapSqlParameterSource> p = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForList(eq(AdminGrowthUserService.INTENT_TOKENS_SQL), p.capture());
        assertThat(new ArrayList<Object>((java.util.Collection<?>) p.getValue().getValue("ids")))
                .containsExactlyInAnyOrder(1004L, 1008L, 1012L, 1016L, 1020L);
    }

    @Test
    void reversalRowWithoutReasonDoesNotBreakTheDrawer() {
        user("REAL", "ACTIVE", false, 100L, 2, null);
        count(1);
        Map<String, Object> r = row(1, "LIKE_RECEIVED", -1);
        r.put("reverses_id", 9L);
        page(List.of(r));

        LedgerRow row = service.ledger(U, null, null, null, null).rows().get(0);

        assertThat(row.reversalReason()).isNull();
        assertThat(row.ruleCode()).isEqualTo("LIKE_RECEIVED");
    }

    @Test
    void officialAccountAdjustBlockedWithNotParticipating() {
        user("REAL", "ACTIVE", false, 0L, 1, null);
        when(ledgerService.participates(U)).thenReturn(false);
        assertThat(service.tab(U).adjustBlockedKey()).isEqualTo("admin.err.growth.notParticipating");
    }

    @Test
    void unknownRefTypeShowsRawCode() {
        user("REAL", "ACTIVE", false, 100L, 2, null);
        count(1);
        Map<String, Object> r = row(1, "COMMENT_POSTED", 1);
        r.put("ref_type", "SOMETHING_NEW");
        r.put("ref_id", "x1");
        page(List.of(r));

        LedgerRow row = service.ledger(U, null, null, null, null).rows().get(0);

        assertThat(row.refTypeKey()).isNull();
        assertThat(row.refType()).isEqualTo("SOMETHING_NEW");
        assertThat(row.refHref()).isNull();
        verify(adminAccounts, never()).findAllById(any());
    }

    @Test
    void filtersUseWibHalfOpenRangeAndExactRule() {
        user("REAL", "ACTIVE", false, 100L, 2, null);
        count(0);
        when(jdbc.queryForObject(eq(AdminGrowthUserService.EVER_SQL), any(MapSqlParameterSource.class),
                eq(Long.class))).thenReturn(3L);

        Ledger l = service.ledger(U, "OPS_CAMPAIGN", "2026-10-01", "2026-10-18", null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> p = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(2)).queryForObject(sql.capture(), p.capture(), eq(Long.class)); // 计数 + 「有没有过流水」
        assertThat(sql.getAllValues().get(0)).contains("l.rule_code = :rule", "l.created_at >= :from",
                "l.created_at < :to");
        assertThat(p.getAllValues().get(0).getValue("rule")).isEqualTo("OPS_CAMPAIGN");
        // [10-01 00:00 WIB, 10-19 00:00 WIB) = [09-30 17:00Z, 10-18 17:00Z)
        assertThat(p.getAllValues().get(0).getValue("from"))
                .isEqualTo(Timestamp.from(Instant.parse("2026-09-30T17:00:00Z")));
        assertThat(p.getAllValues().get(0).getValue("to"))
                .isEqualTo(Timestamp.from(Instant.parse("2026-10-18T17:00:00Z")));
        // 有流水但筛选无结果 → 「没有符合条件的记录」
        assertThat(l.everAny()).isTrue();
        assertThat(l.filtered()).isTrue();
    }

    @Test
    void invalidFilterInputsAreIgnored() {
        user("REAL", "ACTIVE", false, 100L, 2, null);
        count(0);

        Ledger l = service.ledger(U, "PASSIVE_GROUP", "not-a-date", "2026-13-40", "-3");

        assertThat(l.rule()).isNull();
        assertThat(l.from()).isNull();
        assertThat(l.to()).isNull();
        assertThat(l.page()).isZero();
        assertThat(l.filtered()).isFalse();
    }

    // ------------------------------------------------------------------ 调整

    @Test
    void previewComputesFromStoredValues() {
        user("REAL", "ACTIVE", false, 250L, 2, null);
        when(ledgerService.campaignEnabled()).thenReturn(true);

        AdminGrowthUserService.Preview p = service.previewAdjust(U, "OPS_CAMPAIGN", "+60", " 周年庆 ", TOKEN);

        assertThat(p.oldTotal()).isEqualTo(250);
        assertThat(p.newTotal()).isEqualTo(310);
        assertThat(p.oldLevel()).isEqualTo(2);
        assertThat(p.newLevel()).isEqualTo(3);
        assertThat(p.note()).isEqualTo("周年庆");
        assertThat(p.displayName()).isEqualTo("Ana");
        verify(ledgerService).requireAdjustable(U);
    }

    @Test
    void previewRejections() {
        user("REAL", "ACTIVE", false, 10L, 1, null);
        when(ledgerService.campaignEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.previewAdjust(U, "ADMIN_ADJUST", "-11", "x", TOKEN))
                .satisfies(t -> assertThat(((AppException) t).getMessageCode()).isEqualTo("admin.err.growth.belowZero"));
        assertThatThrownBy(() -> service.previewAdjust(U, "OPS_CAMPAIGN", "5", "x", TOKEN))
                .satisfies(t -> assertThat(((AppException) t).getMessageCode())
                        .isEqualTo("admin.err.growth.campaignDisabled"));
        assertThatThrownBy(() -> service.previewAdjust(U, "ADMIN_ADJUST", "abc", "x", TOKEN))
                .satisfies(t -> assertThat(((AppException) t).getMessageCode()).isEqualTo("admin.err.growth.deltaInvalid"));
        assertThatThrownBy(() -> service.previewAdjust(U, "ADMIN_ADJUST", "0", "x", TOKEN))
                .satisfies(t -> assertThat(((AppException) t).getMessageCode()).isEqualTo("admin.err.growth.deltaZero"));
        // 恰好到 0 允许
        assertThat(service.previewAdjust(U, "ADMIN_ADJUST", "-10", "x", TOKEN).newTotal()).isZero();
    }

    @Test
    void adjustWritesAuditOnceAndSkipsItOnReplay() {
        when(ledgerService.adjust(any())).thenReturn(new AdminAdjustCommand.Result(1420, 1470, 7, 7, false));

        service.adjust(U, "OPS_CAMPAIGN", "50", " 周年庆 ", TOKEN, 99L);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(99L), eq(AuditActions.GROWTH_USER_ADJUSTED), eq("USER"), eq("7"), summary.capture());
        assertThat(summary.getValue()).isEqualTo("类型=OPS_CAMPAIGN；增减=+50；累计 1420→1470；等级 7→7；活动/原因=周年庆；"
                + "幂等键=ADMIN:" + TOKEN);
        ArgumentCaptor<AdminAdjustCommand> cmd = ArgumentCaptor.forClass(AdminAdjustCommand.class);
        verify(ledgerService).adjust(cmd.capture());
        assertThat(cmd.getValue().operatorAdminId()).isEqualTo(99L);
        assertThat(cmd.getValue().type()).isEqualTo(AdminAdjustCommand.AdjustType.OPS_CAMPAIGN);

        when(ledgerService.adjust(any())).thenReturn(new AdminAdjustCommand.Result(1470, 1470, 7, 7, true));
        service.adjust(U, "OPS_CAMPAIGN", "50", "周年庆", TOKEN, 99L);
        verify(audit, times(1)).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void signedFormatting() {
        assertThat(AdminGrowthUserService.signed(5)).isEqualTo("+5");
        assertThat(AdminGrowthUserService.signed(-1)).isEqualTo("-1");
    }

    @Test
    void ruleOptionsExcludePseudoRow() {
        assertThat(AdminGrowthUserService.RULE_OPTIONS).doesNotContain("PASSIVE_GROUP")
                .contains("ADMIN_ADJUST", "OPS_CAMPAIGN", "LAUNCH_BACKFILL");
    }

    // ------------------------------------------------------------------ Story 4.4 · P-7 官方号展示等级

    @Test
    void officialAccountShowsEditableDisplayLevelAndNoLedger() {
        user("REAL", "ADMIN", "ACTIVE", false, null, null, 12);

        Tab t = service.tab(U);

        assertThat(t.official()).isTrue();
        assertThat(t.displayLevel()).isEqualTo(12);
        // 表里最高 Lv4（GUARDIAN）：Lv12 取不高于它的最高一行的段位
        assertThat(t.displayTier()).isEqualTo(GrowthTier.GUARDIAN);
        assertThat(t.ledger()).isNull();
        assertThat(t.displayLevelOptions()).extracting(com.tailtopia.growth.service.GrowthDisplayLevelService.DisplayLevel::level)
                .containsExactly(1, 2, 3, 4);
        assertThat(service.ledger(U, null, null, null, null).rows()).isEmpty();
    }

    @Test
    void setOfficialDisplayLevelWritesAndAuditsOnlyWhenChanged() {
        user("REAL", "ADMIN", "ACTIVE", false, null, null, 7);
        when(displayLevels.set(U, 3)).thenReturn(new com.tailtopia.growth.service.GrowthDisplayLevelService.Change(7, 3,
                true));

        service.setOfficialDisplayLevel(U, "3", 99L);
        verify(audit).record(99L, AuditActions.GROWTH_DISPLAY_LEVEL_CHANGED, "user", "7", "displayLevel 7→3");

        when(displayLevels.set(U, 3)).thenReturn(new com.tailtopia.growth.service.GrowthDisplayLevelService.Change(3, 3,
                false));
        service.setOfficialDisplayLevel(U, "3", 99L);
        verify(audit, times(1)).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void setOfficialDisplayLevelRejectsNonOfficialTargets() {
        user("REAL", "USER", "ACTIVE", false, null, null, null);
        assertThatThrownBy(() -> service.setOfficialDisplayLevel(U, "3", 99L))
                .satisfies(t -> assertThat(((AppException) t).getMessageCode())
                        .isEqualTo("admin.err.growth.display.officialOnly"));
        user("VIRTUAL", "USER", "ACTIVE", false, null, null, 6);
        assertThatThrownBy(() -> service.setOfficialDisplayLevel(U, "3", 99L))
                .satisfies(t -> assertThat(((AppException) t).getMessageCode())
                        .isEqualTo("admin.err.growth.display.officialOnly"));
        verify(displayLevels, never()).set(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void nonNumericDisplayLevelGoesToRangeCheck() {
        user("REAL", "ADMIN", "ACTIVE", false, null, null, 7);
        when(displayLevels.set(U, 0)).thenThrow(AppException.validation("x").code("admin.err.growth.display.outOfRange", 4));
        assertThatThrownBy(() -> service.setOfficialDisplayLevel(U, "abc", 99L))
                .satisfies(t -> assertThat(((AppException) t).getMessageCode())
                        .isEqualTo("admin.err.growth.display.outOfRange"));
    }

    @Test
    void vetAccountIsNotOfficial() {
        user("REAL", "VET", "ACTIVE", false, null, null, null);
        when(ledgerService.participates(U)).thenReturn(false);
        Tab t = service.tab(U);
        assertThat(t.state()).isEqualTo(State.REAL);
        assertThat(t.adjustBlockedKey()).isEqualTo("admin.err.growth.notParticipating");
        assertThatThrownBy(() -> service.setOfficialDisplayLevel(U, "3", 99L))
                .satisfies(e -> assertThat(((AppException) e).getMessageCode())
                        .isEqualTo("admin.err.growth.display.officialOnly"));
    }

    @Test
    void officialWithDeletionSubmittedIsRejected() {
        user("REAL", "ADMIN", "ACTIVE", false, null, null, 7);
        when(jdbc.queryForObject(eq(AdminGrowthUserService.DELETION_SQL), any(MapSqlParameterSource.class),
                eq(Long.class))).thenReturn(1L);
        assertThatThrownBy(() -> service.setOfficialDisplayLevel(U, "3", 99L))
                .satisfies(e -> assertThat(((AppException) e).getMessageCode())
                        .isEqualTo("admin.err.growth.userInactive"));
        verify(displayLevels, never()).set(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void officialTabCarriesDisplayNameForConfirm() {
        user("REAL", "ADMIN", "ACTIVE", false, null, null, 7);
        assertThat(service.tab(U).displayName()).isEqualTo("Ana");
    }
}
