package com.tailtopia.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private PlaceQueryService service;

    @BeforeEach
    void setUp() {
        places = Mockito.mock(PlaceRepository.class);
        service = new PlaceQueryService(places);
    }

    private static Place place(String token, double lat, double lng) {
        return Place.mark(token, "Tempat " + token, PlaceType.CAFE,
                List.of(PlaceTag.PETS_ALLOWED_INSIDE), lat, lng, "Jl. Test", null,
                List.of("https://cdn/x.jpg"), 1L);
    }

    // ===== AC4：两条独立分支 =====

    @Test
    void noCoordinatesUsesRecentBranchAndNeverTouchesTheBoxQuery() {
        when(places.findByStatusOrderByCreatedAtDescIdDesc(eq(PlaceStatus.ACTIVE), any(Limit.class)))
                .thenReturn(List.of(place("a", JKT_LAT, JKT_LNG)));

        PlaceListResponse resp = service.list(null, null);

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

        PlaceListResponse resp = service.list(JKT_LAT, JKT_LNG);

        assertThat(resp.sortMode()).isEqualTo(PlaceListResponse.SORT_MODE_DISTANCE);
        verify(places, never())
                .findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class));
    }

    /** 只给一个坐标时服务层按「没给坐标」处理（422 在 controller 挡，这里是纵深防御）。 */
    @Test
    void halfCoordinateFallsBackToRecent() {
        when(places.findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class)))
                .thenReturn(List.of());

        assertThat(service.list(JKT_LAT, null).sortMode())
                .isEqualTo(PlaceListResponse.SORT_MODE_RECENT);
        assertThat(service.list(null, JKT_LNG).sortMode())
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

        PlaceListResponse resp = service.list(JKT_LAT, JKT_LNG);

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

        PlaceListResponse resp = service.list(null, null);

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
        PlaceListResponse resp = service.list(-7.2575, 112.7521);

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

        PlaceListResponse resp = service.list(JKT_LAT, JKT_LNG);

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

        PlaceListResponse resp = service.list(JKT_LAT, JKT_LNG);

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

        service.list(JKT_LAT, JKT_LNG);

        verify(places).findActiveWithinBox(eq(PlaceStatus.ACTIVE), eq(JKT_LAT), eq(JKT_LNG),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                eq(Limit.of(PlaceQueryService.MAX_LIST_SIZE)));
    }

    /** 空库两条分支都给空 items（客户端走空态，不是错误态）。 */
    @Test
    void emptyDatabaseYieldsEmptyItemsOnBothBranches() {
        when(places.findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class)))
                .thenReturn(List.of());
        when(places.findActiveWithinBox(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), any(Limit.class))).thenReturn(List.of());

        assertThat(service.list(null, null).items()).isEmpty();
        assertThat(service.list(JKT_LAT, JKT_LNG).items()).isEmpty();
    }

    /** 安全上限传到了仓储（端点匿名可达，没上限等于把整张表序列化一遍）。 */
    @Test
    void safetyLimitIsPassedToRepository() {
        when(places.findByStatusOrderByCreatedAtDescIdDesc(any(), any(Limit.class)))
                .thenReturn(List.of());

        service.list(null, null);

        verify(places).findByStatusOrderByCreatedAtDescIdDesc(PlaceStatus.ACTIVE,
                Limit.of(PlaceQueryService.MAX_LIST_SIZE));
    }
}
