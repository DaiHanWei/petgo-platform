package com.tailtopia.content.rank;

import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 近 N 天各内容的互动量（V1.1.6 Story 16.4）—— 供 P95 重算。
 *
 * <p>🛡 <b>三次批量查询，不逐条 COUNT</b>：这里的规模是"近 30 天全部公开内容"，
 * 比推荐序候选池还大，逐条查会跑很久（而它跑在调度线程上，没人会看到慢）。
 *
 * <p>⚠️ 与推荐序打分共用同一个「互动量」定义：{@code 赞 + commentWeight × 评}。
 * 分母的口径必须和分子一致 —— 不一致的表现是互动度整体偏大或偏小，而那不会报错。
 */
@Component
public class FeedRankInteractionStats {

    private final ContentPostRepository posts;
    private final ContentLikeRepository likes;
    private final CommentRepository comments;

    public FeedRankInteractionStats(ContentPostRepository posts, ContentLikeRepository likes,
            CommentRepository comments) {
        this.posts = posts;
        this.likes = likes;
        this.comments = comments;
    }

    /**
     * 近期公开内容各自的互动量。
     *
     * <p>⚠️ 返回<b>含 0</b>：分位数要算在"全部近期内容"上，只统计有互动的那些会把 P95 抬得很高，
     * 于是绝大多数内容的互动度都趋近 0 —— 那等于把这一维关掉。
     */
    @Transactional(readOnly = true)
    public List<Double> interactionValues(Instant since, double commentWeight) {
        List<Long> ids = posts.findPublicIdsPublishedSince(since);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> likeCounts = likes.countByPostIdIn(ids).stream()
                .collect(Collectors.toMap(ContentLikeRepository.PostLikeCount::getPostId,
                        ContentLikeRepository.PostLikeCount::getLikeCount));
        Map<Long, Long> commentCounts = new HashMap<>();
        for (CommentRepository.PostCommentCount c : comments.countVisibleForViewerIn(ids, null)) {
            commentCounts.put(c.getPostId(), c.getCommentCount());
        }
        List<Double> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            out.add(likeCounts.getOrDefault(id, 0L)
                    + commentWeight * commentCounts.getOrDefault(id, 0L));
        }
        return out;
    }
}
