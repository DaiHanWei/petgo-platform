package com.tailtopia.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
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
    /** V1.3.0 Story 9.1b：列表的均分 / 已评 / 总量改由评分总览一次给出（原来是每行一次 forVet）。 */
    private com.tailtopia.admin.rating.service.AdminRatingService ratingService;

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
        ratingService = mock(com.tailtopia.admin.rating.service.AdminRatingService.class);
        when(ratingService.overview(any(), any(), any())).thenReturn(List.of());
        service = new AdminVetService(vetAccounts, ratingQuery, presence, interrupt, im, vetQual, audit,
                mock(com.tailtopia.consult.service.ConsultQualityQueryService.class),
                mock(com.tailtopia.shared.media.AliyunOssClient.class),
                mock(com.tailtopia.shared.media.MediaProperties.class),
                ratingService);
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
        // V1.3.0 Story 9.1a：列表的在线态改为**一次取全集**再在内存里推
        //    （在线集合的成员 = 在线；再看忙碌集合分 BUSY / ONLINE），
        //    不再逐行 statusOf —— 一张列表原来要 2N 次 Redis 往返。
        //    anna=ONLINE、bob=OFFLINE（不在集合里）、carol=BUSY。
        when(presence.lastSeenAll()).thenReturn(java.util.Map.of(
                1L, java.time.Instant.parse("2026-06-29T03:25:00Z"),
                3L, java.time.Instant.parse("2026-06-29T03:26:00Z")));
        when(presence.busyAll()).thenReturn(java.util.Set.of(3L));
    }

    /**
     * 不给排序 / 时间窗时走**便宜路径**：每行一次 {@code forVet}，只出均分。
     *
     * <p>🔴 不能一律走评分总览：`overview` 为每个兽医拉全部 CLOSED 会话再逐会话查评分，
     * 查询量是 O(全部已结束会话)，而这一页的三个下拉是 autosubmit、每变一次就重拉。
     */
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
        // 未评兽医均分 null（0.0 会被排成「最差的兽医」，而它的含义是「还没人评过」）。
        assertThat(all.stream().filter(v -> v.id() == 2L).findFirst().orElseThrow().ratingAvg()).isNull();
        // 便宜路径不碰评分总览。
        verify(ratingService, never()).overview(any(), any(), any());
    }

    /** 给了排序或时间窗 = 运营在做评分查询：这时才走总览，并带出已评 / 总量两个计数。 */
    @Test
    void aRatingQueryGoesThroughTheOverviewAndCarriesTheCounts() {
        scenario();
        when(ratingService.overview(any(), any(), any())).thenReturn(List.of(
                new com.tailtopia.admin.rating.dto.VetRatingOverviewRow(1L, "Anna", 4.8, 5, 2, 7),
                new com.tailtopia.admin.rating.dto.VetRatingOverviewRow(2L, "Bob", 0.0, 0, 3, 3)));

        List<VetAdminView> all = service.list(VetListFilter.none(),
                com.tailtopia.admin.rating.service.AdminRatingService.AVG_DESC, null, null);

        VetAdminView anna = all.stream().filter(v -> v.id() == 1L).findFirst().orElseThrow();
        assertThat(anna.ratingAvg()).isEqualTo(4.8);
        assertThat(anna.ratedCount()).isEqualTo(5);
        assertThat(anna.totalVolume()).isEqualTo(7);
        VetAdminView bob = all.stream().filter(v -> v.id() == 2L).findFirst().orElseThrow();
        assertThat(bob.ratingAvg()).as("0 条评分 → null，不是 0.0").isNull();
        assertThat(bob.totalVolume()).isEqualTo(3);
    }

    /**
     * 🛡 无 {@code rating.view}：均分与两个计数**一个都不装**，也不走总览。
     *
     * <p>评分那份数据在退役前是 {@code GET /admin/ratings} 独占的；并进这一页之后
     * 如果只挡 `vet.view`，等于把整张总览白送出去 ——「安全规则层只升不降」。
     */
    @Test
    void withoutRatingViewNoRatingDataIsAssembledAtAll() {
        scenario();
        List<VetAdminView> all = service.list(VetListFilter.none(),
                com.tailtopia.admin.rating.service.AdminRatingService.AVG_DESC, null, null, false);

        assertThat(all).allSatisfy(v -> {
            assertThat(v.ratingAvg()).isNull();
            assertThat(v.ratedCount()).isZero();
            assertThat(v.totalVolume()).isZero();
        });
        verify(ratingService, never()).overview(any(), any(), any());
        verify(ratingQuery, never()).forVet(anyLong());
    }

    /** 🔴 sort 为空时**不重排**：默认按均分重排会让运营每次打开列表都发现行序变了。 */
    @Test
    void anEmptySortKeepsTheOriginalOrder() {
        scenario();
        when(ratingService.overview(any(), any(), any())).thenReturn(List.of(
                new com.tailtopia.admin.rating.dto.VetRatingOverviewRow(3L, "Carol", 5.0, 9, 0, 9),
                new com.tailtopia.admin.rating.dto.VetRatingOverviewRow(1L, "Anna", 4.8, 5, 2, 7),
                new com.tailtopia.admin.rating.dto.VetRatingOverviewRow(2L, "Bob", 0.0, 0, 3, 3)));

        assertThat(service.list(VetListFilter.none(), null, null, null))
                .extracting(VetAdminView::id).containsExactly(1L, 2L, 3L);
        // 给了 sort 就按**总览返回的那一份顺序**排（不在这里另写比较器，否则两处会分叉）。
        assertThat(service.list(VetListFilter.none(),
                        com.tailtopia.admin.rating.service.AdminRatingService.AVG_DESC, null, null))
                .extracting(VetAdminView::id).containsExactly(3L, 1L, 2L);
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
        // onlineSnapshot 走的是**逐行** statusOf（另一个口径的只读快照，本 story 未动）——
        // 列表那条路已改成批量取集合，所以这里要单独把 statusOf 桩上。
        when(presence.statusOf(1L)).thenReturn(VetPresenceStatus.ONLINE);
        when(presence.statusOf(2L)).thenReturn(VetPresenceStatus.OFFLINE);
        when(presence.statusOf(3L)).thenReturn(VetPresenceStatus.BUSY);
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
        // onlineSnapshot 仍走逐行 statusOf / lastSeenAt（它是另一个口径的只读快照，本 story 未动）。
        VetAccount a = vet(1L, "anna@x", "Anna", VetStatus.ACTIVE);
        VetAccount b = vet(2L, "bob@x", "Bob", VetStatus.BANNED);
        when(vetAccounts.listAll()).thenReturn(List.of(a, b));
        when(presence.statusOf(1L)).thenReturn(VetPresenceStatus.ONLINE);
        when(presence.statusOf(2L)).thenReturn(VetPresenceStatus.OFFLINE);
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
