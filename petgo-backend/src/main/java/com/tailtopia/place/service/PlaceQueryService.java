package com.tailtopia.place.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.place.domain.GeoBox;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.domain.PlacePhoto;
import com.tailtopia.place.dto.PlaceDetailResponse;
import com.tailtopia.place.dto.PlacePhotoView;
import com.tailtopia.place.dto.PlaceListItemResponse;
import com.tailtopia.place.dto.PlaceListResponse;
import com.tailtopia.place.repository.PlacePhotoRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 * 列表项的照片数直接从行内 JSONB 长度算；<b>评论数经
 * {@link PlaceCommentQueryService#countsByPlaceIds(List, Long)} 一次查询取回整页的 Map</b>
 * （Story 1.7）—— <b>绝不在循环里按 placeId 逐条查</b>：逐条查在 20 个场所的冷启动数据上
 * 看不出问题，正式运营起来就是整屏卡住的那个原因。
 *
 * <p>两个**态度**计数（👍/👎）经 {@link PlaceAttitudeCounters#countsOf(List)} 同样
 * **整页一次取回**（Story 1.8 · AC6：逐个场所查 Redis 等于把 N+1 从数据库搬到了 Redis）。
 *
 * <p>🔴 <b>评论数是 viewer 维度的</b>：拉黑过滤会让同一个场所对不同的人数字不同。
 * 所以计数与评论列表**共用同一套过滤条件**（见 {@code PlaceCommentRepository}）——
 * 不共用的话，用户会看到列表写着 3 条、点进去只有 2 条。
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
    private final AccountQueryService accounts;
    private final PlaceCommentQueryService placeComments;
    private final PlaceAttitudeCounters attitudeCounters;
    private final PlacePhotoRepository photos;

    /** 照片 key → 公开 URL（对齐决策 D5）。 */
    private final PlacePhotoService photoService;

    public PlaceQueryService(PlaceRepository places, AccountQueryService accounts,
            PlaceCommentQueryService placeComments, PlaceAttitudeCounters attitudeCounters,
            PlacePhotoRepository photos, PlacePhotoService photoService) {
        this.places = places;
        this.photoService = photoService;
        this.accounts = accounts;
        this.placeComments = placeComments;
        this.attitudeCounters = attitudeCounters;
        this.photos = photos;
    }

    /**
     * 场所详情（Story 1.5 · AC1/AC7）。
     *
     * <p>🔴 <b>下架 / 不存在一律 404，且文案相同</b>（AC7）：下架后不能泄漏任何原内容，
     * 也不能让「下架了」与「从来没有过」在响应上可区分 —— 后者可以被用来判断某个 token
     * 曾经存在。客户端两种情况都落同一个「场所不存在」空态。
     *
     * @param lat      可空；与 {@code lng} 同时给时计算距离（AC1 的「距离」位）
     * @param viewerId 当前查看者（游客为 null）—— 评论数是 viewer 维度的（拉黑过滤）
     */
    @Transactional(readOnly = true)
    public PlaceDetailResponse detail(String token, Double lat, Double lng, Long viewerId) {
        // D4：被合并的场所直接返回保留场所（响应 token 随之变成保留场所的，App 以它为准）。
        Place p = places.resolveForView(token)
                .orElseThrow(() -> AppException.notFound("场所不存在"));
        Integer distance = (lat != null && lng != null && GeoBox.isValidCoordinate(lat, lng))
                ? (int) Math.round(
                        GeoBox.distanceMeters(lat, lng, p.getLatitude(), p.getLongitude()))
                : null;
        // 照片（Story 1.9）：每张带上传者。viewer 自己挂起中的照片也给他看得见 ——
        // 否则他刚传完就发现照片"没上去"。
        List<PlacePhoto> rows = photos.findVisible(p.getId(), viewerId != null, viewerId);
        // 🔴 标记人与全部上传者**一次批量取投影**（AD-6）：逐张查作者就是 N+1，
        // 而一个场所最多 9 张、上传者可能是 9 个不同的人。
        List<Long> userIds = new ArrayList<>(rows.size() + 1);
        userIds.add(p.getCreatedBy());
        rows.forEach(r -> userIds.add(r.getUploaderId()));
        Map<Long, AuthorView> authors = accounts.findAuthorViews(userIds);

        AuthorView markedBy = authors.get(p.getCreatedBy());
        List<PlacePhotoView> photoViews = rows.stream()
                .map(r -> PlacePhotoView.of(r, photoService.publicUrlOf(r), authors.get(r.getUploaderId()), viewerId,
                        PlaceDetailResponse.DETAIL_PHOTO_WIDTH_PX))
                .toList();
        // 评论数是 viewer 维度（Story 1.7）；两个态度计数是**平台口径**（Story 1.8）——
        // 两者口径不同是有意的，见 PlaceCommentRepository 的说明。
        long commentCount = placeComments.countForPlace(p.getId(), viewerId);
        PlaceAttitudeCounters.Counts attitudes = attitudeCounters.countsOf(p.getId());
        // 剩余名额与补充照片时的上限判定**同一条查询**（countOccupyingSlots），两处不会走歧。
        int slotsRemaining = (int) (PlacePhotoService.MAX_PHOTOS_PER_PLACE
                - photos.countOccupyingSlots(p.getId()));
        return PlaceDetailResponse.of(p, markedBy, photoViews, distance, commentCount,
                attitudes.recommend(), attitudes.notRecommend(), slotsRemaining);
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
    public PlaceListResponse list(Double lat, Double lng, Long viewerId) {
        if (lat == null || lng == null) {
            return listRecent(viewerId);
        }
        return listByDistance(lat, lng, viewerId);
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
    private PlaceListResponse listByDistance(double lat, double lng, Long viewerId) {
        GeoBox box = GeoBox.around(lat, lng, SEARCH_RADIUS_METERS);
        List<Place> rows = places.findActiveWithinBox(PlaceStatus.ACTIVE, lat, lng,
                box.minLatitude(), box.maxLatitude(), box.minLongitude(), box.maxLongitude(),
                Limit.of(MAX_LIST_SIZE));
        if (rows.isEmpty()) {
            return listRecent(viewerId);
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
            return listRecent(viewerId);
        }
        // 距离相同时按 id 倒序兜底，保证同一请求顺序稳定（否则刷新一次顺序就变了）。
        // nullsLast：已落库实体的 id 不会为空，但比较器不该因为一个未持久化实体就抛 NPE。
        scored.sort(Comparator.comparingDouble(PlaceWithDistance::meters)
                .thenComparing(w -> w.place().getId(),
                        Comparator.nullsLast(Comparator.reverseOrder())));

        // 整页计数一次取回（AD-6 / AC6）。⚠️ 别挪进下面的循环 —— 那就是 N+1
        // （无论后面那个 N+1 落在数据库还是 Redis 上）。
        List<Long> ids = scored.stream().map(w -> w.place().getId()).toList();
        Map<Long, Long> commentCounts = placeComments.countsByPlaceIds(ids, viewerId);
        Map<Long, PlaceAttitudeCounters.Counts> attitudes = attitudeCounters.countsOf(ids);
        Map<Long, List<PlacePhoto>> photosByPlace = photosOf(ids);

        List<PlaceListItemResponse> items = new ArrayList<>(scored.size());
        for (PlaceWithDistance w : scored) {
            long id = w.place().getId();
            PlaceAttitudeCounters.Counts a =
                    attitudes.getOrDefault(id, PlaceAttitudeCounters.Counts.ZERO);
            List<PlacePhoto> ps = photosByPlace.getOrDefault(id, List.of());
            items.add(PlaceListItemResponse.of(w.place(), (int) Math.round(w.meters()),
                    firstUrl(ps), ps.size(),
                    commentCounts.getOrDefault(id, 0L), a.recommend(), a.notRecommend()));
        }
        return PlaceListResponse.distance(items);
    }

    /**
     * 整页照片一次取回后按场所分组（Story 1.9 · AD-6）。
     *
     * <p>🔴 **绝不按场所逐个查** —— 一页 20 个场所就是 20 次查询。
     * 列表口径只认对外可见的（不含某个人自己挂起中的那张）：列表是概览，
     * 没必要为一个人的待审照片让张数跳来跳去。
     */
    private Map<Long, List<PlacePhoto>> photosOf(List<Long> placeIds) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }
        return photos.findVisibleForPlaces(placeIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(PlacePhoto::getPlaceId));
    }

    /** 首图 URL（无照片 → null）。查询已按 sortOrder 排好，取第一条即可。 */
    private String firstUrl(List<PlacePhoto> photos) {
        return photos.isEmpty() ? null : photoService.publicUrlOf(photos.get(0));
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
    public PlaceListResponse listRecent(Long viewerId) {
        List<Place> rows = places.findByStatusOrderByCreatedAtDescIdDesc(
                PlaceStatus.ACTIVE, Limit.of(MAX_LIST_SIZE));
        if (rows.isEmpty()) {
            // 空列表让客户端走空态（引导「标记一个场所」），不是错误路径。
            return PlaceListResponse.recent(List.of());
        }
        List<Long> ids = rows.stream().map(Place::getId).toList();
        Map<Long, Long> commentCounts = placeComments.countsByPlaceIds(ids, viewerId);
        Map<Long, PlaceAttitudeCounters.Counts> attitudes = attitudeCounters.countsOf(ids);
        Map<Long, List<PlacePhoto>> photosByPlace = photosOf(ids);
        List<PlaceListItemResponse> items = new ArrayList<>(rows.size());
        for (Place p : rows) {
            PlaceAttitudeCounters.Counts a =
                    attitudes.getOrDefault(p.getId(), PlaceAttitudeCounters.Counts.ZERO);
            List<PlacePhoto> ps = photosByPlace.getOrDefault(p.getId(), List.of());
            items.add(PlaceListItemResponse.of(p, firstUrl(ps), ps.size(),
                    commentCounts.getOrDefault(p.getId(), 0L), a.recommend(), a.notRecommend()));
        }
        return PlaceListResponse.recent(items);
    }
}
