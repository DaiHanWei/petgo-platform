package com.tailtopia.collect.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.collect.domain.ContentCollection;
import com.tailtopia.collect.dto.CollectedPlaceRow;
import com.tailtopia.collect.dto.CollectedPostRow;
import com.tailtopia.collect.dto.CollectionPage;
import com.tailtopia.collect.repository.ContentCollectionRepository;
import com.tailtopia.collect.repository.PlaceCollectionRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.MediaType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.PostMediaResolver;
import com.tailtopia.place.service.PlaceQueryService;
import com.tailtopia.place.service.PlaceQueryService.CollectedPlaceCard;
import com.tailtopia.shared.paging.KeysetCursor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 我的收藏列表（V1.4.0 ④ Story 2.2 · AD-11）。仅本人可见；按收藏行 {@code created_at DESC, id DESC}、每页 20、
 * {@link KeysetCursor}（坏游标当首页，不 400）。
 *
 * <p>🔴 不可见判定<b>只经 {@link CollectVisibility#visiblePostIds}</b>（与取消收藏决定发不发
 * {@code ContentUncollectedEvent} 是同一个判断，P-7），不在这里另写条件。不可见行照样占位、照样计入页大小。
 *
 * <p>🔴 批量组装、零 N+1：帖子页至多 5 条查询，场所页至多 3 条，与页大小无关。
 *
 * <p>视频帖（专题线 video · AD-7）：没声明视频能力的旧版客户端看到的是<b>不可用占位</b>（与 Feed / 详情页对旧版
 * 隐藏视频帖同口径，旧版没法播）；新版的封面经 {@link PostMediaResolver} 取（作者看自己未公开的视频帖时是签名地址）。
 */
@Service
public class MyCollectionQuery {

    private static final Logger log = LoggerFactory.getLogger(MyCollectionQuery.class);

    static final int PAGE_SIZE = 20;

    /** 摘要上限（code point）：UI 行最多 2 行 14 号字，120 足够。 */
    static final int EXCERPT_MAX = 120;

    private final ContentCollectionRepository contentCollections;
    private final PlaceCollectionRepository placeCollections;
    private final ContentPostRepository posts;
    private final CollectVisibility visibility;
    private final AccountQueryService accounts;
    private final PlaceQueryService placeCards;
    private final PostMediaResolver postMedia;

    public MyCollectionQuery(ContentCollectionRepository contentCollections,
            PlaceCollectionRepository placeCollections, ContentPostRepository posts, CollectVisibility visibility,
            AccountQueryService accounts, PlaceQueryService placeCards, PostMediaResolver postMedia) {
        this.contentCollections = contentCollections;
        this.placeCollections = placeCollections;
        this.posts = posts;
        this.visibility = visibility;
        this.accounts = accounts;
        this.placeCards = placeCards;
        this.postMedia = postMedia;
    }

    /** {@code includeVideo}：客户端是否声明了视频能力（Controller 解析请求头后显式传入）。 */
    @Transactional(readOnly = true)
    public CollectionPage<CollectedPostRow> posts(long me, String cursor, boolean includeVideo) {
        KeysetCursor c = cursorOrFirst(cursor);
        List<ContentCollection> rows = contentCollections.findPage(me, c.createdAt(), c.id(),
                PageRequest.of(0, PAGE_SIZE + 1));
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<ContentCollection> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        if (page.isEmpty()) {
            return new CollectionPage<>(List.of(), null, false);
        }
        Map<Long, ContentPost> byId = posts.findAllById(page.stream().map(ContentCollection::getPostId).toList())
                .stream().collect(Collectors.toMap(ContentPost::getId, Function.identity()));
        Set<Long> visible = new java.util.HashSet<>(visibility.visiblePostIds(me, byId.values()));
        if (!includeVideo) {
            byId.values().stream().filter(p -> p.getMediaType() == MediaType.VIDEO).forEach(p -> visible.remove(p.getId()));
        }
        List<Long> authorIds = byId.values().stream()
                .filter(p -> visible.contains(p.getId()))
                .map(ContentPost::getAuthorId)
                .distinct()
                .toList();
        Map<Long, AuthorView> authors = authorIds.isEmpty() ? Map.of()
                : accounts.findAuthorViewsWithoutTags(authorIds);

        List<CollectedPostRow> items = new ArrayList<>(page.size());
        for (ContentCollection row : page) {
            ContentPost p = byId.get(row.getPostId());
            boolean available = p != null && visible.contains(p.getId());
            CollectedPostRow.Post post = null;
            if (available) {
                AuthorView a = authors.get(p.getAuthorId());
                List<String> images = p.getMediaType() == MediaType.VIDEO
                        ? postMedia.resolve(p, me).imageUrls() : p.getImageUrls();
                post = new CollectedPostRow.Post(p.getType(), excerpt(p.getText()), firstImage(images),
                        a == null ? null : new CollectedPostRow.Author(a.nickname(), a.avatarUrl()));
            }
            items.add(new CollectedPostRow(row.getPostId(), row.getCreatedAt(), available, post));
        }
        ContentCollection last = page.get(page.size() - 1);
        String next = hasMore ? new KeysetCursor(last.getCreatedAt(), last.getId()).encode() : null;
        log.debug("我的帖子收藏 userId={} rows={}", me, items.size());
        return new CollectionPage<>(items, next, hasMore);
    }

    @Transactional(readOnly = true)
    public CollectionPage<CollectedPlaceRow> places(long me, String cursor) {
        KeysetCursor c = cursorOrFirst(cursor);
        List<Object[]> rows = placeCollections.findDedupedPage(me, c.createdAt(), c.id(), PAGE_SIZE + 1);
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<Object[]> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        if (page.isEmpty()) {
            return new CollectionPage<>(List.of(), null, false);
        }
        Map<Long, CollectedPlaceCard> cards = placeCards.collectedCards(
                page.stream().map(r -> ((Number) r[2]).longValue()).distinct().toList());

        List<CollectedPlaceRow> items = new ArrayList<>(page.size());
        for (Object[] r : page) {
            CollectedPlaceCard card = cards.get(((Number) r[2]).longValue());
            if (card == null) {
                continue; // 防御：场所行不存在（正常有 FK CASCADE）
            }
            CollectedPlaceRow.Place place = card.visible()
                    ? new CollectedPlaceRow.Place(card.type(), card.coverUrl(), card.addressText())
                    : null;
            items.add(new CollectedPlaceRow(card.token(), toInstant(r[1]), card.visible(), card.name(), place));
        }
        Object[] last = page.get(page.size() - 1);
        String next = hasMore ? new KeysetCursor(toInstant(last[1]), ((Number) last[0]).longValue()).encode() : null;
        log.debug("我的场所收藏 userId={} rows={}", me, items.size());
        return new CollectionPage<>(items, next, hasMore);
    }

    private static KeysetCursor cursorOrFirst(String cursor) {
        KeysetCursor c = KeysetCursor.decodeOrNull(cursor);
        return c == null ? KeysetCursor.firstPage() : c;
    }

    /** 去首尾空白后截前 120 个 code point（不切断代理对，不加省略号——App 负责）；空 → null。 */
    static String excerpt(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        if (t.isEmpty()) {
            return null;
        }
        return t.codePointCount(0, t.length()) <= EXCERPT_MAX ? t : t.substring(0, t.offsetByCodePoints(0, EXCERPT_MAX));
    }

    /** 首图原样（与 {@code FeedItemResponse.of} 同口径）。 */
    private static String firstImage(List<String> imageUrls) {
        return imageUrls == null || imageUrls.isEmpty() ? null : imageUrls.get(0);
    }

    /** native query 的时间列可能是 Timestamp / OffsetDateTime / Instant（同 {@code ContentService.toInstant}）。 */
    static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("无法识别的时间类型: " + value.getClass());
    }
}
