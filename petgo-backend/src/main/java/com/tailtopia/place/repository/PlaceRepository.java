package com.tailtopia.place.repository;

import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

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
    List<Place> findByStatusOrderByCreatedAtDescIdDesc(PlaceStatus status, Limit limit);

    /** 按不可枚举 token 取在架场所（详情 / H5）。未知 token → 空，调用方回 404 防枚举探测。 */
    Optional<Place> findByPublicTokenAndStatus(String publicToken, PlaceStatus status);
}
