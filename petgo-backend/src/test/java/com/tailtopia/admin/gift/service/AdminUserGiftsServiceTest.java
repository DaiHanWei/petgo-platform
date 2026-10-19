package com.tailtopia.admin.gift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.gift.dto.GiftOrderAdminRow;
import com.tailtopia.admin.gift.dto.UserGiftsTab;
import com.tailtopia.support.TestMessages;
import java.sql.ResultSet;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** L0：用户抽屉「收送礼记录」页签取数（V1.4.0 ③ gift Story 3.2 · AC5）。mock {@link NamedParameterJdbcTemplate}。 */
class AdminUserGiftsServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AdminUserGiftsService svc = new AdminUserGiftsService(jdbc);

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    /** 让 STATS 那条聚合回指定的数（走真实 RowMapper，把 mock ResultSet 喂进去）。 */
    @SuppressWarnings("unchecked")
    private void stats(long sentCount, long sentTotal, long recvCount, long recvQty, long recvSenders, long recvPets,
            String petName) {
        // doAnswer：同一用例里重新打桩时不会先触发上一次的 answer。
        doAnswer(inv -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("sent_count")).thenReturn(sentCount);
                    when(rs.getLong("sent_total")).thenReturn(sentTotal);
                    when(rs.getLong("recv_count")).thenReturn(recvCount);
                    when(rs.getLong("recv_qty")).thenReturn(recvQty);
                    when(rs.getLong("recv_senders")).thenReturn(recvSenders);
                    when(rs.getLong("recv_pets")).thenReturn(recvPets);
                    when(rs.getString("recv_pet_name")).thenReturn(petName);
                    return ((RowMapper<Object>) inv.getArgument(2)).mapRow(rs, 0);
                }).when(jdbc).queryForObject(eq(AdminUserGiftsService.STATS), any(SqlParameterSource.class),
                        any(RowMapper.class));
    }

    @SuppressWarnings("unchecked")
    private void rows(int n) {
        GiftOrderAdminRow r = AdminGiftOrderQueryServiceTest.row("DELIVERED", "PAWCOIN", null);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(java.util.Collections.nCopies(n, r));
    }

    @Test
    void sentCountsAllStatusesAndReceivedOnlyDelivered() {
        // STATS 是唯一的计数来源：送出 = sender_id 全部状态；收到 = owner_id 且 DELIVERED。
        assertThat(AdminUserGiftsService.STATS)
                .contains("count(*) FILTER (WHERE o.sender_id = :u) AS sent_count")
                .contains("count(*) FILTER (WHERE o.owner_id = :u AND o.status = 'DELIVERED') AS recv_count")
                .contains("SUM(o.total) FILTER (WHERE o.sender_id = :u AND o.status = 'DELIVERED')")
                .doesNotContain("pet_profiles");
        assertThat(AdminUserGiftsService.SENT_WHERE).isEqualTo(" WHERE o.sender_id = :u");
        assertThat(AdminUserGiftsService.RECEIVED_WHERE).isEqualTo(" WHERE o.owner_id = :u AND o.status = 'DELIVERED'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstPageIsSentPageOneOfTenAndTwoStatements() {
        stats(12, 92000, 31, 31, 9, 1, "Pipi");
        rows(10);

        UserGiftsTab t = svc.firstPage(10233L);

        assertThat(t.dir()).isEqualTo("sent");
        assertThat(t.isSent()).isTrue();
        assertThat(t.sentCount()).isEqualTo(12);
        assertThat(t.sentTotal()).isEqualTo(92000);
        assertThat(t.receivedCount()).isEqualTo(31);
        assertThat(t.rows()).hasSize(10);
        assertThat(t.page()).isZero();
        assertThat(t.totalPages()).as("12 条每页 10 → 2 页").isEqualTo(2);
        assertThat(t.hasNext()).isTrue();
        assertThat(t.hasPrev()).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(1)).query(sql.capture(), params.capture(), any(RowMapper.class));
        verify(jdbc, times(1)).queryForObject(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue()).contains(AdminUserGiftsService.SENT_WHERE).contains("LEFT JOIN users s");
        assertThat(params.getValue().getValue("limit")).isEqualTo(AdminUserGiftsService.PAGE_SIZE);
        assertThat(params.getValue().getValue("limit")).isEqualTo(10);
        assertThat(params.getValue().getValue("offset")).isEqualTo(0L);
        assertThat(params.getValue().getValue("u")).isEqualTo(10233L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void receivedUsesDeliveredOwnerRowsAndClampsPage() {
        stats(12, 92000, 31, 31, 9, 1, "Pipi");
        rows(1);

        UserGiftsTab t = svc.page(10233L, "RECEIVED", 99);

        assertThat(t.dir()).isEqualTo("received");
        assertThat(t.totalPages()).isEqualTo(4);
        assertThat(t.page()).as("越界夹到末页").isEqualTo(3);
        assertThat(t.hasNext()).isFalse();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains(AdminUserGiftsService.RECEIVED_WHERE);
        assertThat(params.getValue().getValue("offset")).isEqualTo(30L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyBothSidesSkipsTheRowQuery() {
        stats(0, 0, 0, 0, 0, 0, null);

        UserGiftsTab sent = svc.page(5L, null, 0);
        UserGiftsTab recv = svc.page(5L, "received", 0);

        assertThat(sent.rows()).isEmpty();
        assertThat(sent.totalPages()).isZero();
        assertThat(sent.page()).isZero();
        assertThat(recv.rows()).isEmpty();
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }

    @Test
    void dirIsParsedLeniently() {
        assertThat(AdminUserGiftsService.parseDir(null)).isEqualTo("sent");
        assertThat(AdminUserGiftsService.parseDir("bogus")).isEqualTo("sent");
        assertThat(AdminUserGiftsService.parseDir(" received ")).isEqualTo("received");
    }

    @Test
    void receivedSummaryCopyBranchesOnPetCount() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        var msg = TestMessages.real();
        stats(0, 0, 31, 31, 9, 1, "Pipi");
        rows(10);
        UserGiftsTab one = svc.page(1L, "received", 0);
        assertThat(one.receivedOnePet()).isTrue();
        assertThat(msg.get("admin.v140.users.gifts.receivedOnePet", one.receivedPetName())).isEqualTo("宠物 Pipi 收到");
        assertThat(msg.get("admin.v140.users.gifts.receivedQty", String.valueOf(one.receivedQty()))).isEqualTo("31 个");

        stats(0, 0, 31, 40, 9, 2, "Boba");
        UserGiftsTab many = svc.page(1L, "received", 0);
        assertThat(many.receivedOnePet()).isFalse();
        assertThat(msg.get("admin.v140.users.gifts.receivedManyPets", String.valueOf(many.receivedPets())))
                .isEqualTo("2 只宠物共收到");
        assertThat(msg.get("admin.v140.users.gifts.sent", "0")).isEqualTo("送出 · 0");
        assertThat(msg.get("admin.v140.users.gifts.received", "0")).isEqualTo("收到 · 0");
    }
}
