package com.tailtopia.admin.warmreply.service;

import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.domain.WarmReplyFollowup;
import com.tailtopia.admin.warmreply.dto.WarmReplyViews.Detail;
import com.tailtopia.admin.warmreply.dto.WarmReplyViews.Row;
import com.tailtopia.admin.warmreply.dto.WarmReplyViews.ThreadComment;
import com.tailtopia.admin.warmreply.repository.WarmReplyFollowupRepository;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A9 暖贴回复跟进工作台的只读视图装配（V1.3.0 Story 4.4 AC2 / AC3 / AC6）：左栏行、右栏帖子卡 + 线程。
 * 写操作（回复 / 标记已读）在 {@link WarmReplyQueueService}。昵称整页一次取（≤20 行），注销 / 查不到回退 #id；不落日志。
 */
@Service
public class WarmReplyWorkbenchService {

    public static final int PAGE_SIZE = 20;
    static final int SUMMARY_MAX = 80;

    private final WarmReplyQueueService queue;
    private final WarmReplyFollowupRepository followups;
    private final UserRepository users;
    private final AccountQueryService accountQuery;
    private final AdminAccountRepository admins;
    private final CommentRepository comments;
    private final ContentPostRepository posts;

    public WarmReplyWorkbenchService(WarmReplyQueueService queue, WarmReplyFollowupRepository followups, UserRepository users,
            AccountQueryService accountQuery, AdminAccountRepository admins, CommentRepository comments, ContentPostRepository posts) {
        this.queue = queue;
        this.followups = followups;
        this.users = users;
        this.accountQuery = accountQuery;
        this.admins = admins;
        this.comments = comments;
        this.posts = posts;
    }

    /** 左栏队列：待跟进（{@code last_reply_at} 倒序）/ 已跟进（近 30 天，{@code handled_at} 倒序），每页 20。 */
    @Transactional(readOnly = true)
    public Page<Row> queue(FollowupStatus tab, int page) {
        Page<WarmReplyFollowup> p = tab == FollowupStatus.HANDLED ? queue.listHandled(page, PAGE_SIZE) : queue.listPending(page, PAGE_SIZE);
        List<WarmReplyFollowup> items = p.getContent();
        Map<Long, String> virtualNames = virtualNames(items.stream().map(WarmReplyFollowup::getVirtualUserId).collect(Collectors.toSet()));
        // 最近一条回复的作者昵称：last_reply_id → comment.author_id → AuthorView（注销 / 已删回退 #id）
        Map<Long, Long> replyAuthor = new HashMap<>();
        List<Long> replyIds = items.stream().map(WarmReplyFollowup::getLastReplyId).filter(Objects::nonNull).toList();
        if (!replyIds.isEmpty()) {
            comments.findAllById(replyIds).forEach(c -> replyAuthor.put(c.getId(), c.getAuthorId()));
        }
        Map<Long, String> replierNames = realNames(new HashSet<>(replyAuthor.values()));
        Map<Long, String> adminNames = adminNames(items.stream().map(WarmReplyFollowup::getHandledBy).filter(Objects::nonNull).collect(Collectors.toSet()));
        return p.map(f -> {
            Long authorId = f.getLastReplyId() == null ? null : replyAuthor.get(f.getLastReplyId());
            String replier = authorId == null ? "#" + f.getLastReplyId() : replierNames.getOrDefault(authorId, "#" + authorId);
            return new Row(f.getId(), virtualNames.getOrDefault(f.getVirtualUserId(), "#" + f.getVirtualUserId()), f.getPostId(), replier,
                    f.getPendingReplyCount(), f.getLastReplyAt(), f.getStatus(), f.getHandledAction(),
                    f.getHandledBy() == null ? null : adminNames.getOrDefault(f.getHandledBy(), "#" + f.getHandledBy()), f.getHandledAt());
        });
    }

    /** 页签计数（与角标同源 {@code pendingCount()}）。 */
    @Transactional(readOnly = true)
    public Map<String, Long> counts() {
        return Map.of("pending", queue.pendingCount(), "handled", queue.handledCount());
    }

    /**
     * 右栏（AC3）：帖子卡（#postId + 摘要 + 作者 + 可见评论数）→ 线程（虚拟一级 + 其下可见 / 审核中二级，时间正序；
     * 未处理的真实回复 {@code fresh} = 最新 pending_reply_count 条，仅 PENDING 项高亮）→ 操作区所需（身份锁定昵称、幂等键）。
     * {@code justRepliedId}：运营刚发出的回复 id（线程内标「审核中」并置于末尾，本来就是最新）。
     */
    @Transactional(readOnly = true)
    public Detail detail(long id, Long justRepliedId) {
        WarmReplyFollowup f = followups.findById(id)
                .orElseThrow(() -> AppException.notFound("跟进项不存在").code("admin.err.warmReply.notFound"));
        // 帖子已删或不可见（下架 / 挂审）都按「内容已删除」占位，只允许标记已读（与 WarmReplyQueueService.isContentDeleted 同口径）
        ContentPost post = posts.findById(f.getPostId()).filter(p -> p.getDeletedAt() == null && p.getStatus() == PostStatus.PUBLISHED).orElse(null);
        Comment virtualComment = comments.findById(f.getVirtualCommentId()).filter(c -> !c.isDeleted()).orElse(null);
        boolean contentDeleted = post == null || virtualComment == null;

        List<Comment> replies = virtualComment == null ? List.of()
                : comments.findByParentIdAndDeletedAtIsNull(virtualComment.getId()).stream()
                        .filter(c -> c.getModerationStatus() == CommentModerationStatus.VISIBLE
                                || c.getModerationStatus() == CommentModerationStatus.UNDER_REVIEW)
                        .sorted(Comparator.comparing(Comment::getCreatedAt).thenComparing(Comment::getId))
                        .toList();
        Set<Long> authorIds = new HashSet<>();
        authorIds.add(f.getVirtualUserId());
        replies.forEach(c -> authorIds.add(c.getAuthorId()));
        if (post != null) {
            authorIds.add(post.getAuthorId());
        }
        Map<Long, User> userById = new HashMap<>();
        users.findAllById(authorIds).forEach(u -> userById.put(u.getId(), u));
        Map<Long, String> names = new HashMap<>(realNames(authorIds));
        userById.forEach((uid, u) -> {
            if (u.isSyntheticAccount()) {
                names.put(uid, displayName(u)); // 虚拟 / 官方号：AuthorView 可能没有，直接取昵称
            }
        });

        String virtualNickname = names.getOrDefault(f.getVirtualUserId(), "#" + f.getVirtualUserId());
        ThreadComment top = virtualComment == null ? null
                : new ThreadComment(virtualComment.getId(), virtualNickname, true, virtualComment.getBody(), virtualComment.getCreatedAt(), false,
                        virtualComment.getModerationStatus() == CommentModerationStatus.UNDER_REVIEW);
        // 「新回复」= 运营还没处理的真实用户回复：PENDING 项下线程里最新的 pending_reply_count 条非合成账号回复（计数由入队 / 出队维护，
        // 天然等于未处理数）。不能比 created_at：项在评论转可见后的 AFTER_COMMIT 才建行，晚于触发入队的那条回复（复审 #1）。
        int freshBudget = f.getStatus() == FollowupStatus.PENDING ? f.getPendingReplyCount() : 0;
        Set<Long> freshIds = new HashSet<>();
        for (int i = replies.size() - 1; i >= 0 && freshBudget > 0; i--) {
            Comment c = replies.get(i);
            User u = userById.get(c.getAuthorId());
            if (u != null && !u.isSyntheticAccount() && c.getModerationStatus() == CommentModerationStatus.VISIBLE) {
                freshIds.add(c.getId());
                freshBudget--;
            }
        }
        List<ThreadComment> thread = new ArrayList<>(replies.size());
        for (Comment c : replies) {
            User u = userById.get(c.getAuthorId());
            boolean synthetic = u != null && u.isSyntheticAccount();
            thread.add(new ThreadComment(c.getId(), names.getOrDefault(c.getAuthorId(), "#" + c.getAuthorId()), synthetic, c.getBody(),
                    c.getCreatedAt(), freshIds.contains(c.getId()), c.getModerationStatus() == CommentModerationStatus.UNDER_REVIEW
                            || (justRepliedId != null && justRepliedId.equals(c.getId()))));
        }
        long visible = post == null ? 0 : comments.countByPostIdAndDeletedAtIsNullAndModerationStatus(post.getId(), CommentModerationStatus.VISIBLE);
        String handledBy = f.getHandledBy() == null ? null : adminNames(Set.of(f.getHandledBy())).getOrDefault(f.getHandledBy(), "#" + f.getHandledBy());
        return new Detail(f.getId(), f.getStatus(), f.getHandledAction(), handledBy, f.getHandledAt(),
                f.getPostId(), post == null ? "" : VirtualCommentService.summarize(post.getText(), SUMMARY_MAX),
                post == null ? "" : names.getOrDefault(post.getAuthorId(), "#" + post.getAuthorId()), visible, contentDeleted,
                f.getVirtualUserId(), virtualNickname, top, List.copyOf(thread), f.getPendingReplyCount(), f.getLastReplyAt(),
                UUID.randomUUID().toString(), justRepliedId);
    }

    private Map<Long, String> virtualNames(Set<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        if (!ids.isEmpty()) {
            users.findAllById(ids).forEach(u -> out.put(u.getId(), displayName(u)));
        }
        return out;
    }

    private Map<Long, String> realNames(Set<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map.Entry<Long, AuthorView> e : accountQuery.findAuthorViews(ids).entrySet()) {
            AuthorView v = e.getValue();
            if (v != null && v.nickname() != null && !v.nickname().isBlank()) {
                out.put(e.getKey(), v.nickname());
            }
        }
        return out;
    }

    private Map<Long, String> adminNames(Set<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        if (!ids.isEmpty()) {
            admins.findAllById(ids).forEach(a -> out.put(a.getId(), a.getDisplayName()));
        }
        return out;
    }

    private static String displayName(User u) {
        return u.getNickname() == null || u.getNickname().isBlank() ? "#" + u.getId() : u.getNickname();
    }
}
