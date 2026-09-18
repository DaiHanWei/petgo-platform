package com.tailtopia.place.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.FeedCursor;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceComment;
import com.tailtopia.place.dto.PlaceCommentPageResponse;
import com.tailtopia.place.dto.PlaceCommentResponse;
import com.tailtopia.place.repository.PlaceCommentRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所评论只读（V1.3.0 batch-b1 Story 1.7 · AC6）。
 *
 * <h2>🔴 拉黑过滤在 SQL 里，不在应用层（AC6）</h2>
 * 拿一页回来再在 Java 里滤掉几条，会让「每页 10 条」变成「每页 7 条、8 条、10 条」——
 * 用户往下翻会看到长短不一的批次，而且 `total` 与列出来的条数对不上。
 * 过滤条件写在 {@link PlaceCommentRepository} 的 JPQL 里，**计数与列表逐字共用同一套条件**。
 *
 * <h2>拉黑关系走既有的那个统一读取口（AC6 / AD-7）</h2>
 * JPQL 里引用 {@code social} 的实体 {@code UserHideRelation}，Java 侧不 import 其仓储 ——
 * 与 {@code content.repository.CommentRepository} 同一既定破例，**不新写一套关系查询**。
 */
@Service
public class PlaceCommentQueryService {

    /** 每批条数（与内容评论一致，客户端的分页处理可以照抄）。 */
    public static final int PAGE_SIZE = 10;

    private final PlaceCommentRepository comments;
    private final PlaceRepository places;
    private final AccountQueryService accounts;

    public PlaceCommentQueryService(PlaceCommentRepository comments, PlaceRepository places,
            AccountQueryService accounts) {
        this.comments = comments;
        this.places = places;
        this.accounts = accounts;
    }

    /**
     * 某场所的评论，按**最新在前**游标分页。
     *
     * <p>🔒 **游客可读**（与场所详情同口径，后端 GET 放行）：{@code viewerId} 为 null 时
     * 只看得到 VISIBLE 的，也没有 R1（游客没有拉黑关系）。
     *
     * @throws AppException 场所不存在 / 已下架 → 404（与详情同口径，不泄漏 token 曾存在）
     */
    @Transactional(readOnly = true)
    public PlaceCommentPageResponse list(String placeToken, String cursor, Long viewerId) {
        Place place = places.resolveForView(placeToken)
                .orElseThrow(() -> AppException.notFound("场所不存在"));
        long placeId = place.getId();
        boolean hasViewer = viewerId != null;

        FeedCursor decoded = cursor == null || cursor.isBlank() ? null : FeedCursor.decode(cursor);
        List<PlaceComment> rows = comments.findVisiblePage(placeId,
                hasViewer,
                viewerId,
                decoded != null,
                // 🔴 游标缺省值不能是 null：PG 推不出类型（42P18）。`seek=false` 时这两个参数
                // 根本不参与判断，给一个哨兵值即可（同 findFeed 的既定处理）。
                decoded == null ? Instant.EPOCH : decoded.createdAt(),
                decoded == null ? 0L : decoded.id(),
                PageRequest.of(0, PAGE_SIZE + 1));

        boolean hasMore = rows.size() > PAGE_SIZE;
        List<PlaceComment> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;

        // 批量取作者投影（注销 → 匿名化，NFR-8）。⚠️ 不逐条查：评论区是最容易退化成 N+1 的地方。
        Map<Long, AuthorView> authors =
                accounts.findAuthorViews(page.stream().map(PlaceComment::getAuthorId).toList());

        List<PlaceCommentResponse> items = page.stream()
                .map(c -> PlaceCommentResponse.of(c, authors.get(c.getAuthorId()), viewerId))
                .toList();

        String next = hasMore && !page.isEmpty()
                ? new FeedCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
                : null;

        return new PlaceCommentPageResponse(items, next, hasMore,
                comments.countVisibleForViewer(placeId, hasViewer, viewerId));
    }

    /**
     * 一批场所各自的可见评论数（列表页用，AD-6 批量）。
     *
     * <p>返回 map 只含**有评论的**场所；调用方对缺键按 0 处理。
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> countsByPlaceIds(List<Long> placeIds, Long viewerId) {
        if (placeIds == null || placeIds.isEmpty()) {
            return Map.of();
        }
        return comments.countVisibleByPlaceIds(placeIds, viewerId != null, viewerId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> ((Number) row[1]).longValue()));
    }

    /**
     * 单个用户的作者投影 —— 发表成功后回显那一条时用（省一次多余查询）。
     *
     * <p>⚠️ 放在这里而不是让 controller 直接注 {@code AccountQueryService}：
     * 场所模块取用户投影**统一从这一层过**，controller 不直连其它模块的 service。
     */
    @Transactional(readOnly = true)
    public AuthorView authorViewOf(long userId) {
        return accounts.findAuthorViews(List.of(userId)).get(userId);
    }

    /** 单个场所的可见评论数（详情页那个数字）。 */
    @Transactional(readOnly = true)
    public long countForPlace(long placeId, Long viewerId) {
        return comments.countVisibleForViewer(placeId, viewerId != null, viewerId);
    }
}
