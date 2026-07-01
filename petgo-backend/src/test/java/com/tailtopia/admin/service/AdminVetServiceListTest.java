package com.tailtopia.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.dto.VetListFilter;
import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.consult.dto.VetRatingsView;
import com.tailtopia.consult.service.ConsultInterruptService;
import com.tailtopia.consult.service.ConsultRatingQueryService;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.domain.VetPresenceStatus;
import com.tailtopia.vet.domain.VetStatus;
import com.tailtopia.vet.service.VetAccountService;
import com.tailtopia.vet.service.VetPresenceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：兽医列表组装 + 多维筛选/搜索（AC1/AC2/AC3，mock 各 service）。 */
class AdminVetServiceListTest {

    private VetAccountService vetAccounts;
    private ConsultRatingQueryService ratingQuery;
    private VetPresenceService presence;
    private VetQualificationService vetQual;
    private AdminVetService service;

    @BeforeEach
    void setUp() {
        vetAccounts = mock(VetAccountService.class);
        ratingQuery = mock(ConsultRatingQueryService.class);
        presence = mock(VetPresenceService.class);
        vetQual = mock(VetQualificationService.class);
        ConsultInterruptService interrupt = mock(ConsultInterruptService.class);
        TencentImClient im = mock(TencentImClient.class);
        com.tailtopia.admin.audit.service.AdminAuditService audit =
                mock(com.tailtopia.admin.audit.service.AdminAuditService.class);
        service = new AdminVetService(vetAccounts, ratingQuery, presence, interrupt, im, vetQual, audit,
                mock(com.tailtopia.consult.service.ConsultQualityQueryService.class));
        // 默认均分空（count 0）。
        when(ratingQuery.forVet(anyLong())).thenReturn(new VetRatingsView(0L, 0.0, 0, List.of()));
    }

    private VetAccount vet(long id, String username, String displayName, VetStatus status) {
        VetAccount v = VetAccount.create(username, "{bcrypt}x", displayName);
        ReflectionTestUtils.setField(v, "id", id);
        ReflectionTestUtils.setField(v, "status", status);
        return v;
    }

    private void scenario() {
        VetAccount a = vet(1L, "anna@x", "Anna", VetStatus.ACTIVE);
        VetAccount b = vet(2L, "bob@x", "Bob", VetStatus.BANNED);
        VetAccount c = vet(3L, "carol@x", "Carol", VetStatus.ACTIVE);
        when(vetAccounts.listAll()).thenReturn(List.of(a, b, c));
        when(vetQual.getStatus(1L)).thenReturn(QualificationStatus.CERTIFIED);
        when(vetQual.getStatus(2L)).thenReturn(QualificationStatus.PENDING_COMPLETION);
        when(vetQual.getStatus(3L)).thenReturn(QualificationStatus.EXPIRED);
        when(presence.statusOf(1L)).thenReturn(VetPresenceStatus.ONLINE);
        when(presence.statusOf(2L)).thenReturn(VetPresenceStatus.OFFLINE);
        when(presence.statusOf(3L)).thenReturn(VetPresenceStatus.BUSY);
    }

    @Test
    void noFilterReturnsAllAssembledWithColumns() {
        scenario();
        when(ratingQuery.forVet(1L)).thenReturn(new VetRatingsView(1L, 4.8, 5, List.of()));

        List<VetAdminView> all = service.list(VetListFilter.none());

        assertThat(all).hasSize(3);
        VetAdminView anna = all.stream().filter(v -> v.id() == 1L).findFirst().orElseThrow();
        assertThat(anna.qualStatus()).isEqualTo("CERTIFIED");
        assertThat(anna.presence()).isEqualTo("ONLINE");
        assertThat(anna.ratingAvg()).isEqualTo(4.8);
        // 未评兽医均分 null。
        assertThat(all.stream().filter(v -> v.id() == 2L).findFirst().orElseThrow().ratingAvg()).isNull();
    }

    @Test
    void filterByAccountStatus() {
        scenario();
        List<VetAdminView> banned = service.list(new VetListFilter("BANNED", null, null, null));
        assertThat(banned).extracting(VetAdminView::id).containsExactly(2L);
    }

    @Test
    void filterByQualStatus() {
        scenario();
        List<VetAdminView> certified = service.list(new VetListFilter(null, "CERTIFIED", null, null));
        assertThat(certified).extracting(VetAdminView::id).containsExactly(1L);
    }

    @Test
    void filterByOnlineIncludesBusy() {
        scenario();
        // ONLINE 维度含 BUSY → anna(ONLINE) + carol(BUSY)。
        List<VetAdminView> online = service.list(new VetListFilter(null, null, "ONLINE", null));
        assertThat(online).extracting(VetAdminView::id).containsExactlyInAnyOrder(1L, 3L);
        // OFFLINE → 仅 bob。
        List<VetAdminView> offline = service.list(new VetListFilter(null, null, "OFFLINE", null));
        assertThat(offline).extracting(VetAdminView::id).containsExactly(2L);
    }

    @Test
    void searchByNameOrEmailCaseInsensitiveSubstring() {
        scenario();
        assertThat(service.list(new VetListFilter(null, null, null, "ANN")))
                .extracting(VetAdminView::id).containsExactly(1L);
        assertThat(service.list(new VetListFilter(null, null, null, "bob@")))
                .extracting(VetAdminView::id).containsExactly(2L);
    }

    @Test
    void onlineSnapshotReadsStatusAndNeverWritesPresence() {
        scenario();
        java.time.Instant t = java.time.Instant.parse("2026-06-29T03:00:00Z");
        var snap = service.onlineSnapshot(t);

        assertThat(snap.queriedAt()).isEqualTo(t);
        assertThat(snap.rows()).hasSize(3);
        assertThat(snap.rows()).extracting(com.tailtopia.admin.dto.VetOnlineSnapshot.Row::presence)
                .containsExactlyInAnyOrder("ONLINE", "OFFLINE", "BUSY");
        // 只读：绝不调 presence 写方法。
        verify(presence, never()).goOffline(anyLong());
        verify(presence, never()).goBusy(anyLong());
        verify(presence, never()).goAvailable(anyLong());
    }

    @Test
    void onlineSnapshotIncludesLastSeenLabel() {
        // Bug 20260701-168：每行补最后在线时间（WIB）；离线/无 lastSeen → 「—」。
        scenario();
        when(presence.lastSeenAt(1L))
                .thenReturn(java.util.Optional.of(java.time.Instant.parse("2026-06-29T03:25:00Z")));
        when(presence.lastSeenAt(2L)).thenReturn(java.util.Optional.empty());

        var snap = service.onlineSnapshot(java.time.Instant.parse("2026-06-29T03:30:00Z"));
        var byId = snap.rows().stream().collect(java.util.stream.Collectors.toMap(
                com.tailtopia.admin.dto.VetOnlineSnapshot.Row::id,
                com.tailtopia.admin.dto.VetOnlineSnapshot.Row::lastSeenLabel));
        // WIB = UTC+7：03:25Z → 10:25 WIB。
        assertThat(byId.get(1L)).isEqualTo("2026-06-29 10:25");
        assertThat(byId.get(2L)).isEqualTo("—");
    }

    @Test
    void filtersCombine() {
        scenario();
        // ACTIVE + online(含BUSY) → anna(ACTIVE/ONLINE) + carol(ACTIVE/BUSY)，排除 bob(BANNED)。
        List<VetAdminView> r = service.list(new VetListFilter("ACTIVE", null, "ONLINE", null));
        assertThat(r).extracting(VetAdminView::id).containsExactlyInAnyOrder(1L, 3L);
    }
}
