package com.tailtopia.place.service;

import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceStatus;
import com.tailtopia.place.dto.PlaceListItemResponse;
import com.tailtopia.place.dto.PlaceListResponse;
import com.tailtopia.place.repository.PlaceRepository;
import java.util.ArrayList;
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
     * 场所列表「按最新」分支（AC2）：在架场所按创建时间倒序。
     *
     * <p>🔴 「按距离」是 Story 1.2 加的<b>另一条独立分支</b>（AD-2 Rule 5），不是给这条加个
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
