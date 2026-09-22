package com.tailtopia.place.repository;

import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    /**
     * 列表「按最新」分支（Story 1.1 · FR-112.2）：在架场所按创建时间倒序。
     *
     * <p>🔴 <b>「按距离」是另一条独立查询方法</b>（Story 1.2 加），不是给这一条加个可空的
     * 距离参数（AD-2 Rule 5：不得写成「距离为 null 排最后」把两种语义混在一条 SQL 里）。
     *
     * <p>{@code limit} 是<b>安全上限</b>而不是分页（本版不分页，PRD ⑤ 冷启动 10–20 个场所）：
     * 端点对游客放行，没有上限的话每个匿名请求都会序列化整张表（含全部标签与照片 URL）。
     * 见 {@code PlaceQueryService.MAX_LIST_SIZE}。
     */
    // 🔴 2026-09-18 场所表对齐：本表有后台的软删列 deleted_at，「对用户可见」= status 匹配 **且** 未软删。
    //    方法名沿用派生查询的旧名（调用方与测试不动），但一律改成显式 JPQL 把 deletedAt 条件写进去 ——
    //    漏了它，后台软删的场所会继续出现在 App 里。
    @Query("select p from Place p where p.status = :status and p.deletedAt is null "
            + "order by p.createdAt desc, p.id desc")
    List<Place> findByStatusOrderByCreatedAtDescIdDesc(@Param("status") PlaceStatus status, Limit limit);

    /**
     * 列表「按距离」分支的<b>粗筛</b>（Story 1.2 · AD-2 Rule 2）：在架 + 落在矩形范围内。
     *
     * <p>🔴 <b>这条查询里没有距离计算</b>，是有意的：在 SQL 里对 {@code latitude} /
     * {@code longitude} 套三角函数会让 {@code ix_places_active_latitude} /
     * {@code ix_places_active_longitude} 两条索引<b>全部失效</b>，退化成全表扫描。
     * 距离由 {@code GeoBox.distanceMeters} 在应用层算、在应用层排序。
     *
     * <p>🔴 <b>它与 {@link #findByStatusOrderByCreatedAtDescIdDesc} 是两条彼此独立的分支</b>
     * （AD-2 Rule 5 / AC4）：<b>不得</b>合并成一条带可空坐标的 SQL 再用「距离为 null 排最后」
     * 混排 —— 那样无坐标时也会带着距离列跑全表，而且两种排序语义缠在一起后没人敢改。
     *
     * <h2>🔴 排序用「曼哈顿代理距离」，不是 id 倒序</h2>
     * {@code limit} 的截断发生在**应用层按距离排序之前**，所以 SQL 的顺序决定了「被砍掉的是哪些行」。
     * 按 {@code id desc} 排的话，框内场所超过上限时留下的是「最新的 N 个」而不是「最近的 N 个」，
     * 最近的那家店会<b>整条消失</b>，而响应里的 {@code sortMode} 仍然说自己是 distance ——
     * 用户看到的是「按距离排序」少了最近的一家，没有任何地方能看出为什么。
     *
     * <p>{@code abs(lat-x) + abs(lng-y)} 与真实距离<b>单调性足够接近</b>（同一纬度带内，经度权重
     * 被 cos 压缩，误差在粗筛这一步无害），而且不需要三角函数。它只用来<b>挑出候选</b>，
     * 精确顺序仍由应用层的 haversine 决定 —— 两者职责不同，别把这条 order by 当成最终排序。
     *
     * <p>⚠️ 这条 order by <b>不影响索引</b>：范围条件已经用 BitmapAnd 把候选集缩小，
     * 排序是在那个结果集上做的（无论按什么排都要排一次）。
     */
    @Query("select p from Place p where p.status = :status and p.deletedAt is null "
            + "and p.latitude between :minLat and :maxLat "
            + "and p.longitude between :minLng and :maxLng "
            + "order by abs(p.latitude - :lat) + abs(p.longitude - :lng) asc, p.id desc")
    List<Place> findActiveWithinBox(@Param("status") PlaceStatus status,
            @Param("lat") java.math.BigDecimal lat, @Param("lng") java.math.BigDecimal lng,
            @Param("minLat") java.math.BigDecimal minLat, @Param("maxLat") java.math.BigDecimal maxLat,
            @Param("minLng") java.math.BigDecimal minLng, @Param("maxLng") java.math.BigDecimal maxLng,
            Limit limit);

    /** double 入参的便捷重载（列是 NUMERIC(9,6)，JPQL 参数须与列类型一致）。 */
    default List<Place> findActiveWithinBox(PlaceStatus status, double lat, double lng,
            double minLat, double maxLat, double minLng, double maxLng, Limit limit) {
        return findActiveWithinBox(status, bd(lat), bd(lng), bd(minLat), bd(maxLat), bd(minLng), bd(maxLng), limit);
    }

    private static java.math.BigDecimal bd(double v) {
        return java.math.BigDecimal.valueOf(v);
    }

    // ===== Story 1.11：带筛选的两条分支（原生查询） =====
    //
    // 🔴 为什么是原生：标签是 JSONB 数组，JPQL 表达不了「包含」。无筛选时调用方**继续走上面两条 JPQL**
    //    （零回归），只有带了 type / tag 才走这里。两条仍是**各自独立**的分支（AD-2 Rule 5），
    //    只是 WHERE 里各多了同样的两项筛选；deleted_at IS NULL 与 status 条件一个都不能丢。
    //
    // 绑定约定（由 PlaceListFilter 生成，只含已校验的枚举名，绝不含用户原始输入）：
    //   :types    逗号拼接的 PlaceType 名；'' = 不筛类型（类型之间「或」）
    //   :tagsJson JSON 数组；'[]' = 不筛标签（tags @> '[]' 恒真；数组内多个值 = 「且」）
    // V1 场所百级，不加 GIN 索引（AC6）。

    /** 「按最新」分支 + 筛选（Story 1.11 AC2/AC3）。排序、上限与 {@link #findByStatusOrderByCreatedAtDescIdDesc} 相同。 */
    @Query(value = "SELECT p.* FROM places p "
            + "WHERE p.status = :status AND p.deleted_at IS NULL "
            + "AND (CAST(:types AS text) = '' OR p.place_type = ANY(string_to_array(CAST(:types AS text), ','))) "
            + "AND p.tags @> CAST(:tagsJson AS jsonb) "
            + "ORDER BY p.created_at DESC, p.id DESC "
            + "LIMIT :limit", nativeQuery = true)
    List<Place> findRecentFiltered(@Param("status") String status, @Param("types") String types,
            @Param("tagsJson") String tagsJson, @Param("limit") int limit);

    /** 「按距离」粗筛 + 筛选（Story 1.11 AC3）。矩形条件与代理距离排序与 {@link #findActiveWithinBox} 相同。 */
    @Query(value = "SELECT p.* FROM places p "
            + "WHERE p.status = :status AND p.deleted_at IS NULL "
            + "AND p.lat BETWEEN :minLat AND :maxLat "
            + "AND p.lng BETWEEN :minLng AND :maxLng "
            + "AND (CAST(:types AS text) = '' OR p.place_type = ANY(string_to_array(CAST(:types AS text), ','))) "
            + "AND p.tags @> CAST(:tagsJson AS jsonb) "
            + "ORDER BY abs(p.lat - :lat) + abs(p.lng - :lng) ASC, p.id DESC "
            + "LIMIT :limit", nativeQuery = true)
    List<Place> findWithinBoxFiltered(@Param("status") String status,
            @Param("lat") java.math.BigDecimal lat, @Param("lng") java.math.BigDecimal lng,
            @Param("minLat") java.math.BigDecimal minLat, @Param("maxLat") java.math.BigDecimal maxLat,
            @Param("minLng") java.math.BigDecimal minLng, @Param("maxLng") java.math.BigDecimal maxLng,
            @Param("types") String types, @Param("tagsJson") String tagsJson, @Param("limit") int limit);

    /** double 入参的便捷重载。 */
    default List<Place> findWithinBoxFiltered(PlaceStatus status, double lat, double lng,
            double minLat, double maxLat, double minLng, double maxLng,
            String types, String tagsJson, int limit) {
        return findWithinBoxFiltered(status.name(), bd(lat), bd(lng), bd(minLat), bd(maxLat), bd(minLng), bd(maxLng),
                types, tagsJson, limit);
    }

    /** 按不可枚举 token 取在架场所（详情 / H5）。未知 token → 空，调用方回 404 防枚举探测。 */
    /**
     * 按 token 取（D4 解析的底层）：**不看状态**（MERGED 要转去保留场所），但软删的不算。
     * 🔴 面向用户的入口一律用 {@link #resolveForView}，别直接用它。
     * 调用方自己判：ACTIVE → 本身；MERGED → {@link #findVisibleById} 取保留场所；其余 → 不存在。
     */
    @Query("select p from Place p where p.publicToken = :publicToken and p.deletedAt is null")
    Optional<Place> findUndeletedByPublicToken(@Param("publicToken") String publicToken);

    /** 按 id 取「对用户可见」的场所（ACTIVE 且未软删）。合并跳转只跳一层：目标不可见就按不存在处理。 */
    @Query("select p from Place p where p.id = :id "
            + "and p.status = com.tailtopia.place.domain.PlaceStatus.ACTIVE and p.deletedAt is null")
    Optional<Place> findVisibleById(@Param("id") Long id);

    /**
     * 详情 / 分享页用的解析（对齐决策 D4）：ACTIVE → 本身；MERGED → 保留场所（只跳一层）；其余 → 不存在。
     *
     * <p>🔴 调用方拿到的可能是**另一个场所**（token 不同）—— 响应里一律用返回值的 token，App 以它为准。
     * 下架、软删、合并目标不可见，三种情况与「从来没有过」同一个结果（AC7：不泄漏曾经存在）。
     */
    default Optional<Place> resolveForView(String publicToken) {
        return findUndeletedByPublicToken(publicToken).flatMap(p -> switch (p.getStatus()) {
            case ACTIVE -> Optional.of(p);
            case MERGED -> p.getMergedIntoId() == null ? Optional.empty() : findVisibleById(p.getMergedIntoId());
            case DELISTED -> Optional.<Place>empty();
        });
    }
}
