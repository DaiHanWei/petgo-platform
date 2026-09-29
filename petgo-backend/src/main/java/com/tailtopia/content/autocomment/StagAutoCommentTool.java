package com.tailtopia.content.autocomment;

import com.tailtopia.auth.domain.User;
import com.tailtopia.shared.ai.PetCommentGenerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 【stag 专用测试工具，只在 stag 分支，不合回任何分支】一键对「最新 N 条零评论帖」跑一次自动评论。
 *
 * <p>与定时任务的区别：忽略「发帖满 2 小时」和起始日期，按发帖时间<b>倒序</b>取最新的；其余条件、选号、
 * AI、发评论、留档完全走 {@link AutoCommentService#processOne}，所以测到的就是线上会跑的那套逻辑。
 * 已有留档的帖（FAILED 未到上限除外）不再取，避免同一批 AI 跳过的帖每次都被重复捞到。
 *
 * <p>{@code @Profile("stag")}：生产不注册这个 Bean。
 */
@Service
@Profile("stag")
public class StagAutoCommentTool {

    public static final int DEFAULT_LIMIT = 10;

    private static final String LATEST_CANDIDATES = """
            SELECT p.id FROM content_posts p
            JOIN users u ON u.id = p.author_id
            WHERE p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.deleted_at IS NULL
              AND u.account_type = 'REAL' AND u.role = 'USER' AND u.deleted_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.post_id = p.id AND c.deleted_at IS NULL
                              AND c.moderation_status IN ('VISIBLE', 'UNDER_REVIEW'))
              AND NOT EXISTS (SELECT 1 FROM auto_comment_logs l WHERE l.post_id = p.id
                              AND NOT (l.status = 'FAILED' AND l.attempts < ?))
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT ?
            """;

    private final AutoCommentService service;
    private final AutoCommentLogRepository logs;
    private final PetCommentGenerator generator;
    private final AutoCommentProperties props;
    private final JdbcTemplate jdbc;

    public StagAutoCommentTool(AutoCommentService service, AutoCommentLogRepository logs,
            PetCommentGenerator generator, AutoCommentProperties props, JdbcTemplate jdbc) {
        this.service = service;
        this.logs = logs;
        this.generator = generator;
        this.props = props;
        this.jdbc = jdbc;
    }

    /**
     * 单帖结果。{@code status} 为 null 表示 AI 生成期间已有人评论（未发、未留档），模板显示为 {@code ALREADY_COMMENTED}。
     */
    public record Item(long postId, String status, String virtualNickname, String comment, String reason,
            Long latencyMs) {
    }

    /** {@code aiLive=false} 时整轮未执行（stub 固定文案不发）。 */
    public record Result(boolean aiLive, int total, int posted, int skipped, int failed,
            Map<String, Long> skippedByStatus, List<Item> items, long tookMs) {
    }

    public Result run(int limit) {
        long start = System.currentTimeMillis();
        if (!generator.live()) {
            return new Result(false, 0, 0, 0, 0, Map.of(), List.of(), 0);
        }
        List<Long> postIds = jdbc.queryForList(LATEST_CANDIDATES, Long.class, props.getMaxAttempts(), limit);
        List<User> pool = service.virtualPool();
        Map<Long, String> nicknames = pool.stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() == null ? "#" + u.getId() : u.getNickname(),
                        (a, b) -> a));
        List<Item> items = new ArrayList<>();
        for (Long postId : postIds) {
            AutoCommentStatus status = service.processOne(postId, pool);
            items.add(toItem(postId, status, nicknames));
        }
        int posted = (int) items.stream().filter(i -> "POSTED".equals(i.status())).count();
        int failed = (int) items.stream().filter(i -> "FAILED".equals(i.status())).count();
        Map<String, Long> skippedByStatus = items.stream()
                .map(Item::status)
                .filter(s -> !"POSTED".equals(s) && !"FAILED".equals(s))
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        int skipped = (int) skippedByStatus.values().stream().mapToLong(Long::longValue).sum();
        return new Result(true, items.size(), posted, skipped, failed, skippedByStatus, items,
                System.currentTimeMillis() - start);
    }

    private Item toItem(long postId, AutoCommentStatus status, Map<Long, String> nicknames) {
        if (status == null) {
            return new Item(postId, "ALREADY_COMMENTED", null, null, null, null);
        }
        Optional<AutoCommentLog> log = logs.findByPostId(postId);
        String nickname = log.map(AutoCommentLog::getVirtualUserId).map(nicknames::get).orElse(null);
        String comment = log.map(AutoCommentLog::getGeneratedText).orElse(null);
        String reason = log.map(l -> l.getSkipReason() != null ? l.getSkipReason() : l.getErrorCode()).orElse(null);
        Long latency = log.map(AutoCommentLog::getLatencyMs).map(Integer::longValue).orElse(null);
        return new Item(postId, status.name(), nickname, comment, reason, latency);
    }
}
