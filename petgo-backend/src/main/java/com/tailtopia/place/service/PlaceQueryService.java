package com.tailtopia.place.service;

import com.tailtopia.place.domain.GeoBox;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.dto.PlaceListItemResponse;
import com.tailtopia.place.dto.PlaceListResponse;
import com.tailtopia.place.repository.PlaceRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所只读查询（V1.3.0 batch-b1 Story 1.1 · FR-112.2 · AD-6）。
 *
 * <p><b>只读</b>：标记场所（写）属 Story 1.3；本 story 不提供任何写方法，而且
 * <b>永远不会有编辑方法</b> —— 用户不可修改场所（2026-09-15 拍板），服务端硬拒而不是只藏入口。
 *
 * <h2>🔴 取数一律批量（AD-6）</h2>
 * 列表项的照片数直接从行内 JSONB 长度算，评论数与两个态度计数在本 story <b>恒为 0</b>
 * （{@code place_comments} 由 Story 1.7 建表、Redis 计数器由 1.8 接上）——
 * 所以整个列表<b>只有一条 SQL</b>，天然无 N+1。
 *
 * <p>⚠️ <b>1.7 / 1.8 接真实计数时，必须在这里做「一次查询取回整页的计数 Map」</b>
 * （范式见 {@code AdminContentManageService} 的 {@code likeCounts(...)}），
 * 而不是在循环里按 placeId 逐条查。逐条查在 20 个场所的冷启动数据上看不出问题，
 * 正式运营起来就是整屏卡住的那个原因。
 *
 * <p>🛡 <b>不引入任何缓存</b>：AD-9 授权的只有「单个整数计数器 + DB 回算自愈」，
 * 给列表整页结果做缓存<b>不在授权范围内</b>，仍受基线「禁通用缓存层」约束。
 */
@Service
public class PlaceQueryService {

    /**
     * 单次列表返回的**安全上限**（不是分页）。
     *
     * <p>本版按 PRD ⑤ 不分页 —— 冷启动只有雅加达 10–20 个场所，一次取完比让客户端翻页更快。
     * 但这个端点<b>对游客放行</b>，没有上限就等于给了任何匿名请求一条「把整张表连同全部标签
     * 与照片 URL 序列化一遍」的通道。200 远高于可预见的真实数据量，正常用户永远碰不到它。
     *
     * <p>🔴 <b>接近这个数就该上分页了，不要把它调大</b>：调大只是把同一个问题推后，
     * 而分页形态已经定好了（沿用 {@code FeedPageResponse} 的游标信封）。
     */
    static final int MAX_LIST_SIZE = 200;

    private final PlaceRepository places;

    public PlaceQueryService(PlaceRepository places) {
        this.places = places;
    }

    /**
     * 粗筛半径（米）—— AD-2 Rule 2 的「合理半径」在本版取 50 km。
     *
     * <p>依据：冷启动数据是**雅加达核心区** 10–20 个场所（PRD ⑤），大雅加达都会区东西向约 40 km，
     * 50 km 足够把「城里所有场所」都装进候选集。
     *
     * <p>🔴 **半径外不是「排在后面」而是「不出现」** —— 这正是 AD-2 说的「把候选缩到合理半径内」。
     * 为了不让外地用户面对一个空列表，{@link #list(Double, Double)} 在粗筛为空时**整条回落到按最新**
     * （并把 {@code sortMode} 如实下发为 {@code recent}），而不是给出一个空结果。
     *
     * <p>⚠️ 场所覆盖扩到雅加达以外时这个值要重新评估；调它之前先想清楚
     * 「一个 300 km 外的场所排在列表里对用户有没有意义」。
     */
    static final double SEARCH_RADIUS_METERS = 50_000d;

    /**
     * 场所列表（AC1/AC4）：带坐标走**距离分支**，不带坐标走**按最新分支**。
     *
     * <p>🔴 <b>两条分支在这里就分开，不在 SQL 里合并</b>（AD-2 Rule 5 / AC4）：
     * 合并成一条「距离为 null 排最后」的 SQL 会让无坐标请求也带着距离计算跑全表，
     * 而且两种排序语义缠在一起之后没人敢改。
     *
     * @param lat 纬度，可空；与 {@code lng} <b>必须同时给或同时不给</b>（校验在 controller）
     * @param lng 经度，可空
     */
    @Transactional(readOnly = true)
    public PlaceListResponse list(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return listRecent();
        }
        return listByDistance(lat, lng);
    }

    /**
     * 「按距离」分支（AC1/AC2）：矩形范围粗筛 → 应用层算直线距离 → 升序。
     *
     * <p>🔴 <b>距离不在 SQL 里算</b>：对经纬度列套三角函数会让 Story 1.2 建的两条索引全部失效
     * （见 {@code PlaceRepository.findActiveWithinBox} 的说明）。
     *
     * <p>粗筛一个都没捞到（用户在雅加达以外）→ <b>回落按最新</b>，`sortMode` 如实回 {@code recent}。
     * 给一个空列表在技术上"正确"，但用户看到的是「这个功能什么都没有」。
     */
    private PlaceListResponse listByDistance(double lat, double lng) {
        GeoBox box = GeoBox.around(lat, lng, SEARCH_RADIUS_METERS);
        List<Place> rows = places.findActiveWithinBox(PlaceStatus.ACTIVE, lat, lng,
                box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude(),
                Limit.of(MAX_LIST_SIZE));
        if (rows.isEmpty()) {
            return listRecent();
        }
        // 先算好距离再排序（一次 map + 一次 sort），不要在比较器里反复算 haversine。
        //
        // 🔴 这里**还要按半径复筛一次**：矩形的四个角到中心是 √2 × 半径（50 km 的框角上有 70.7 km），
        // 而 GeoBox.around 在贴极点 / 跨对日线时会把经度放宽成全范围。不复筛的话
        // SEARCH_RADIUS_METERS 的注释（「半径外不出现」）就是假的，而这个端点任何人都能
        // 用任意合法坐标调 —— 传一个极点坐标就能把整条纬度带的场所当「按距离」拿走。
        List<PlaceWithDistance> scored = new ArrayList<>(rows.size());
        for (Place p : rows) {
            double meters = GeoBox.distanceMeters(lat, lng, p.getLatitude(), p.getLongitude());
            if (meters <= SEARCH_RADIUS_METERS) {
                scored.add(new PlaceWithDistance(p, meters));
            }
        }
        if (scored.isEmpty()) {
            // 框里有行但全在半径外（用户在两个城市之间）→ 与「框里一个都没有」同一处理。
            return listRecent();
        }
        // 距离相同时按 id 倒序兜底，保证同一请求顺序稳定（否则刷新一次顺序就变了）。
        // nullsLast：已落库实体的 id 不会为空，但比较器不该因为一个未持久化实体就抛 NPE。
        scored.sort(Comparator.comparingDouble(PlaceWithDistance::meters)
                .thenComparing(w -> w.place().getId(),
                        Comparator.nullsLast(Comparator.reverseOrder())));

        List<PlaceListItemResponse> items = new ArrayList<>(scored.size());
        for (PlaceWithDistance w : scored) {
            items.add(PlaceListItemResponse.of(w.place(), (int) Math.round(w.meters()),
                    0L, 0L, 0L));
        }
        return PlaceListResponse.distance(items);
    }

    /** 排序中间体（距离只算一次）。 */
    private record PlaceWithDistance(Place place, double meters) {
    }

    /**
     * 场所列表「按最新」分支（AC2）：在架场所按创建时间倒序。
     *
     * <p>🔴 「按距离」是{@link #listByDistance}那条<b>独立分支</b>（AD-2 Rule 5），不是给这条加个
     * 可空坐标参数然后在 SQL 里把两种语义混在一起。
     */
    @Transactional(readOnly = true)
    public PlaceListResponse listRecent() {
        List<Place> rows = places.findByStatusOrderByCreatedAtDescIdDesc(
                PlaceStatus.ACTIVE, Limit.of(MAX_LIST_SIZE));
        if (rows.isEmpty()) {
            // 空列表让客户端走空态（引导「标记一个场所」），不是错误路径。
            return PlaceListResponse.recent(List.of());
        }
        List<PlaceListItemResponse> items = new ArrayList<>(rows.size());
        for (Place p : rows) {
            // Story 1.1：评论与态度计数尚未交付 → 恒 0（契约先定，1.7/1.8 接真值）。
            items.add(PlaceListItemResponse.of(p, 0L, 0L, 0L));
        }
        return PlaceListResponse.recent(items);
    }
}
