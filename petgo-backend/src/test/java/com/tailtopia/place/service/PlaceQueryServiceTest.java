package com.tailtopia.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.dto.PlaceListResponse;
import com.tailtopia.place.repository.PlaceRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Limit;

/**
 * L0（mock 仓储，无 DB）：场所列表两条分支的分流与口径
 * （V1.3.0 batch-b1 Story 1.2 · AC4 · AD-2 Rule 5）。
 *
 * <p>这组用例守的是**分支不许合并**这条架构约束 —— 它是可证伪的：
 * 「按最新」那次调用**绝不能**碰到粗筛查询，反之亦然。
 */
class PlaceQueryServiceTest {

    private static final double JKT_LAT = -6.2350;
    private static final double JKT_LNG = 106.8100;

    private PlaceRepository places;
    private AccountQueryService accounts;
    private PlaceCommentQueryService placeComments;
    private PlaceAttitudeCounters attitudeCounters;
    private PlaceQueryService service;

    @BeforeEach
    void setUp() {
        places = Mockito.mock(PlaceRepository.class);
        accounts = Mockito.mock(AccountQueryService.class);
        // Story 1.7：评论数从这里批量取（默认空 Map = 一条评论都没有）。
        placeComments = Mockito.mock(PlaceCommentQueryService.class);
        Mockito.when(placeComments.countsByPlaceIds(Mockito.anyList(), Mockito.any()))
                .thenReturn(java.util.Map.of());
        // Story 1.8：态度计数从这里批量取（默认空 Map = 都是 0）。
        attitudeCounters = Mockito.mock(PlaceAttitudeCounters.class);
        Mockito.when(attitudeCounters.countsOf(Mockito.anyList()))
                .thenReturn(java.util.Map.of());
        Mockito.when(attitudeCounters.countsOf(Mockito.anyLong()))
                .thenReturn(PlaceAttitudeCounters.Counts.ZERO);
        service = new PlaceQueryService(places, accounts, placeComments, attitudeCounters);
    }

    /** 自增 id 的发号器 —— 只要不同就行（评论数 Map 按 id 取）。 */
    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(1);

    private static Place place(String token, double lat, double lng) {
        // 🔴 必须带 id：Story 1.7 起列表/详情要按 placeId 批量取评论数，
        // 没有 id 的裸实体在真实路径上不存在（JPA 一定赋了值）。
        return withId(Place.mark(token, "Tempat " + token, PlaceType.CAFE,
                List.of(PlaceTag.PETS_ALLOWED_INSIDE), lat, lng, "Jl. Test", null,
                List.of("https://cdn/x.jpg"), 1L), SEQ.getAndIncrement());
    }

    private static Place withId(Place p, long id) {
        try {
            var f = Place.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
            return p;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Place.id 字段名变了，改这里", e);
        }
    }

    // ===== AC4：两条独立分支 =====

    @Test
    void noCoordinatesUsesRecentBranchAndNeverTouchesTheBoxQuery() {
        when(places.findByStatusOrderByCreatedAtDescIdDesc(eq(PlaceStatus.ACTIVE), any(Limit.class)))
                .thenReturn(List.of(place("a", JKT_LAT, JKT_LNG)));

        PlaceListResponse resp = service.list(null, null, null);

        assertThat(resp.sortMode()).isEqualTo(PlaceListResponse.SORT_MODE_RECENT);
        // 🔴 按最新分支**一次都不能**走粗筛查询 —— 合并成一条 SQL 的写法在这里会红。
        verify(places, never()).findActiveWithinBox(any(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), any(Limit.class));
    }

    @Test
    void coordinatesUseDistanceBranchAndNeverTouchTheRecentQuery() {
        when(places.findActiveWithinBox(eq(PlaceStatus.ACTIVE), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), any(Limit.class)))
                .thenReturn(List.of(place("a", JKT_LAT, JKT_LNG)));

        PlaceListResponse resp = service.list(JKT_LAT, JKT_LNG, null);

        assertThat(resp.sortMode()).isEqualTo(PlaceListResponse.SORT_MODE_DISTANCE);
        verify(places, never())
                .findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class));
    }

    /** 只给一个坐标时服务层按「没给坐标」处理（422 在 controller 挡，这里是纵深防御）。 */
    @Test
    void halfCoordinateFallsBackToRecent() {
        when(places.findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class)))
                .thenReturn(List.of());

        assertThat(service.list(JKT_LAT, null, null).sortMode())
                .isEqualTo(PlaceListResponse.SORT_MODE_RECENT);
        assertThat(service.list(null, JKT_LNG, null).sortMode())
                .isEqualTo(PlaceListResponse.SORT_MODE_RECENT);
    }

    // ===== AC1：距离升序 =====

    @Test
    void distanceBranchSortsAscendingAndFillsDistanceMeters() {
        // 故意按「远 → 中 → 近」的顺序喂进去，验证排序真的发生了。
        Place far = place("far", JKT_LAT + 0.20, JKT_LNG);     // ~22 km
        Place mid = place("mid", JKT_LAT + 0.05, JKT_LNG);     // ~5.5 km
        Place near = place("near", JKT_LAT + 0.001, JKT_LNG);  // ~110 m
        when(places.findActiveWithinBox(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(Limit.class))).thenReturn(List.of(far, mid, near));

        PlaceListResponse resp = service.list(JKT_LAT, JKT_LNG, null);

        assertThat(resp.items()).extracting("token")
                .containsExactly("near", "mid", "far");
        assertThat(resp.items()).allSatisfy(
                it -> assertThat(it.distanceMeters()).isNotNull().isGreaterThanOrEqualTo(0));
        assertThat(resp.items().get(0).distanceMeters()).isLessThan(500);
        assertThat(resp.items().get(2).distanceMeters()).isGreaterThan(20_000);
    }

    /**
     * 🔴 「按最新」分支的距离位必须是 **null 而不是 0**。
     * 0 米是「就在脚下」，客户端会把每一项都显示成「0 m」。
     */
    @Test
    void recentBranchLeavesDistanceNull() {
        when(places.findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class)))
                .thenReturn(List.of(place("a", JKT_LAT, JKT_LNG)));

        PlaceListResponse resp = service.list(null, null, null);

        assertThat(resp.items().get(0).distanceMeters()).isNull();
    }

    /**
     * 粗筛半径外（用户不在雅加达）→ **整条回落按最新**，且 `sortMode` 如实回 recent。
     *
     * <p>给一个空列表在技术上"正确"，但用户看到的是「这个功能什么都没有」。
     */
    @Test
    void emptyCoarseFilterFallsBackToRecentAndSaysSo() {
        when(places.findActiveWithinBox(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(Limit.class))).thenReturn(List.of());
        when(places.findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class)))
                .thenReturn(List.of(place("jakarta", JKT_LAT, JKT_LNG)));

        // 泗水（Surabaya），离雅加达约 660 km，远在 50 km 粗筛半径外。
        PlaceListResponse resp = service.list(-7.2575, 112.7521, null);

        assertThat(resp.sortMode()).isEqualTo(PlaceListResponse.SORT_MODE_RECENT);
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).distanceMeters())
                .as("回落后不得残留距离值").isNull();
    }

    /**
     * 🔴 矩形的角比半径远（50 km 的框，角上 70.7 km）→ 半径外的行必须被**复筛掉**。
     *
     * <p>不复筛的话 `SEARCH_RADIUS_METERS` 的「半径外不出现」就是假的；而这个端点任何人
     * 都能用任意合法坐标调，传个极点坐标就能把整条纬度带的场所当「按距离」拿走。
     */
    @Test
    void rowsOutsideTheRadiusAreFilteredOutEvenIfTheBoxReturnedThem() {
        Place inside = place("inside", JKT_LAT + 0.01, JKT_LNG);          // ~1.1 km
        Place corner = place("corner", JKT_LAT + 0.44, JKT_LNG + 0.44);   // ~69 km（框内、半径外）
        when(places.findActiveWithinBox(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(Limit.class))).thenReturn(List.of(inside, corner));

        PlaceListResponse resp = service.list(JKT_LAT, JKT_LNG, null);

        assertThat(resp.items()).extracting("token").containsExactly("inside");
    }

    /** 框里有行但全在半径外（用户在两城之间）→ 与「框里一个都没有」同处理：回落按最新。 */
    @Test
    void allRowsOutsideRadiusFallsBackToRecent() {
        Place corner = place("corner", JKT_LAT + 0.44, JKT_LNG + 0.44);
        when(places.findActiveWithinBox(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(Limit.class))).thenReturn(List.of(corner));
        when(places.findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class)))
                .thenReturn(List.of(place("jakarta", JKT_LAT, JKT_LNG)));

        PlaceListResponse resp = service.list(JKT_LAT, JKT_LNG, null);

        assertThat(resp.sortMode()).isEqualTo(PlaceListResponse.SORT_MODE_RECENT);
        assertThat(resp.items()).extracting("token").containsExactly("jakarta");
        assertThat(resp.items().get(0).distanceMeters()).isNull();
    }

    /**
     * 粗筛查询必须拿到**中心坐标**（它的 order by 用它做代理距离排序）。
     *
     * <p>🔴 这条守的是「截断留下的是最近的那些，而不是最新的那些」—— 截断发生在
     * 应用层排序之前，SQL 的顺序决定被砍掉谁。
     */
    @Test
    void boxQueryReceivesTheCenterCoordinateForProxyOrdering() {
        when(places.findActiveWithinBox(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(Limit.class))).thenReturn(List.of());
        when(places.findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class)))
                .thenReturn(List.of());

        service.list(JKT_LAT, JKT_LNG, null);

        verify(places).findActiveWithinBox(eq(PlaceStatus.ACTIVE), eq(JKT_LAT), eq(JKT_LNG),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                eq(Limit.of(PlaceQueryService.MAX_LIST_SIZE)));
    }

    // ===== Story 1.5 详情 =====

    /**
     * 🔴 **下架与不存在必须无法区分**（AC7）：两者都走同一个 `findByPublicTokenAndStatus(ACTIVE)`
     * 的空结果 → 同一个 404 + 同一句文案。让它们可区分等于给出「这个 token 曾经存在」这条信息。
     */
    @Test
    void detailOfATakenDownOrUnknownPlaceIsNotFound() {
        when(places.findByPublicTokenAndStatus("gone", PlaceStatus.ACTIVE))
                .thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.detail("gone", null, null, null))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class)
                .hasMessageContaining("场所不存在");
    }

    @Test
    void detailWithCoordinatesFillsDistanceAndWithoutLeavesItNull() {
        Place p = place("kopi", JKT_LAT + 0.01, JKT_LNG);
        when(places.findByPublicTokenAndStatus("kopi", PlaceStatus.ACTIVE))
                .thenReturn(java.util.Optional.of(p));
        when(accounts.findAuthorViews(any()))
                .thenReturn(java.util.Map.of(1L, com.tailtopia.auth.dto.AuthorView.anonymized(1L)));

        assertThat(service.detail("kopi", JKT_LAT, JKT_LNG, null).distanceMeters())
                .isNotNull().isBetween(900, 1300);
        assertThat(service.detail("kopi", null, null, null).distanceMeters()).isNull();
    }

    /** 非法坐标不当距离用（也不报错）—— 详情页比列表宽容：缺个距离位 ≠ 打不开页面。 */
    @Test
    void detailIgnoresOutOfRangeCoordinates() {
        Place p = place("kopi", JKT_LAT, JKT_LNG);
        when(places.findByPublicTokenAndStatus("kopi", PlaceStatus.ACTIVE))
                .thenReturn(java.util.Optional.of(p));
        when(accounts.findAuthorViews(any()))
                .thenReturn(java.util.Map.of(1L, com.tailtopia.auth.dto.AuthorView.anonymized(1L)));

        assertThat(service.detail("kopi", 999d, 999d, null).distanceMeters()).isNull();
    }

    /** 标记人经既有作者投影出口取（不让 place 直 join users；注销自动匿名化）。 */
    @Test
    void detailResolvesMarkerThroughTheSharedAuthorProjection() {
        Place p = place("kopi", JKT_LAT, JKT_LNG);
        when(places.findByPublicTokenAndStatus("kopi", PlaceStatus.ACTIVE))
                .thenReturn(java.util.Optional.of(p));
        when(accounts.findAuthorViews(any()))
                .thenReturn(java.util.Map.of(1L, com.tailtopia.auth.dto.AuthorView.anonymized(1L)));

        assertThat(service.detail("kopi", null, null, null).markedBy().deleted()).isTrue();
        verify(accounts).findAuthorViews(List.of(1L));
    }

    /** 空库两条分支都给空 items（客户端走空态，不是错误态）。 */
    @Test
    void emptyDatabaseYieldsEmptyItemsOnBothBranches() {
        when(places.findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class)))
                .thenReturn(List.of());
        when(places.findActiveWithinBox(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(Limit.class))).thenReturn(List.of());

        assertThat(service.list(null, null, null).items()).isEmpty();
        assertThat(service.list(JKT_LAT, JKT_LNG, null).items()).isEmpty();
    }

    /** 安全上限传到了仓储（端点匿名可达，没上限等于把整张表序列化一遍）。 */
    @Test
    void safetyLimitIsPassedToRepository() {
        when(places.findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class)))
                .thenReturn(List.of());

        service.list(null, null, null);

        verify(places).findByStatusOrderByCreatedAtDescIdDesc(PlaceStatus.ACTIVE,
                Limit.of(PlaceQueryService.MAX_LIST_SIZE));
    }
}
