package com.tailtopia.admin.pin.service;

import com.tailtopia.admin.pin.dto.PinnableContentRow;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.repository.ContentPostRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台「选一条公开内容」的候选查询（顶置 Story 11.1 / 内容打标共用，bug 20260924-562 抽出）。
 *
 * <p>一页 {@link #PAGE_SIZE} 条；作者昵称整页<b>一次</b>批量取（禁止逐行查）；首图取 {@code imageUrls[0]}。
 * 关键词是纯数字时额外按内容 id 精确命中（置于第一页最前），运营常拿着 id 来找帖子。
 */
@Component
public class PinnableContentPicker {

    /** 每页条数。页面按「本页是否满页」判断还有没有下一页。 */
    public static final int PAGE_SIZE = 20;
    private static final int SUMMARY_MAX = 80;

    private final ContentPostRepository posts;
    private final AccountQueryService accountQuery;

    public PinnableContentPicker(ContentPostRepository posts, AccountQueryService accountQuery) {
        this.posts = posts;
        this.accountQuery = accountQuery;
    }

    @Transactional(readOnly = true)
    public List<PinnableContentRow> page(String keyword, int page) {
        // 🔴 绝不传 null：绑 null 时 Postgres 推不出类型（lower(bytea) does not exist），
        //    而"不带关键词"正是页面首次加载的那一次。无关键词 → "%" 匹配全部。
        String kw = keyword == null ? "" : keyword.trim();
        String pattern = kw.isEmpty() ? "%" : "%" + kw.toLowerCase() + "%";
        List<ContentPost> found = new ArrayList<>(
                posts.searchPinnable(pattern, PageRequest.of(Math.max(page, 0), PAGE_SIZE)));
        if (page <= 0 && kw.matches("\\d{1,18}")) {
            posts.findById(Long.parseLong(kw))
                    .filter(PinnableContentPicker::pinnable)
                    .filter(p -> found.stream().noneMatch(f -> f.getId().equals(p.getId())))
                    .ifPresent(p -> found.add(0, p));
        }
        Map<Long, AuthorView> authors = authorViews(found);
        return found.stream().map(p -> toRow(p, authors.get(p.getAuthorId()))).toList();
    }

    /** 与 {@code searchPinnable} 同一组条件：未删、已发布、公开。 */
    static boolean pinnable(ContentPost p) {
        return p.getDeletedAt() == null && p.getStatus() == PostStatus.PUBLISHED
                && p.getVisibility() == ContentVisibility.PUBLIC;
    }

    private Map<Long, AuthorView> authorViews(List<ContentPost> rows) {
        List<Long> ids = rows.stream().map(ContentPost::getAuthorId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : accountQuery.findAuthorViews(ids);
    }

    private static PinnableContentRow toRow(ContentPost p, AuthorView author) {
        List<String> images = p.getImageUrls();
        String thumb = images == null || images.isEmpty() ? null : images.get(0);
        return new PinnableContentRow(p.getId(), p.getType().name(), truncate(p.getText()), p.getCreatedAt(),
                p.getAuthorId(), author == null || author.deleted() ? null : author.nickname(),
                author != null && author.deleted(), thumb);
    }

    static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() <= SUMMARY_MAX ? t : t.substring(0, SUMMARY_MAX) + "…";
    }
}
