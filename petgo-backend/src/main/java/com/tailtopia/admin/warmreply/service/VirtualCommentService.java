package com.tailtopia.admin.warmreply.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.warmreply.dto.DistributionFilter;
import com.tailtopia.admin.warmreply.dto.DistributionRow;
import com.tailtopia.admin.warmreply.dto.VirtualCommentDrawer;
import com.tailtopia.admin.warmreply.dto.VirtualCommentDrawer.ExistingComment;
import com.tailtopia.admin.warmreply.dto.VirtualCommentDrawer.IdentityOption;
import com.tailtopia.admin.warmreply.repository.PostDistributionQuery;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 暖评（V1.3.0 Story 4.2，AB-20A ②④⑤）：运营以虚拟身份给冷帖写一级评论，<b>走 App 同一入口、完整审核</b>（D-4）。
 * <ul>
 * <li>发布只调 {@link CommentService#createTopLevel}（{@code requireVisible} + L1 黑名单 + 落 {@code UNDER_REVIEW} + 发事件），
 * 审核 / 通知 / 入队全部继承；<b>绝不</b>直接 {@code comments.save}（那是绕审核直接 VISIBLE）。</li>
 * <li>身份是显式参数（虚拟账号 id），不伪造 SecurityContext；审计 actor 仍是运营的 adminAccountId。</li>
 * <li>幂等复用 {@link IdempotencyService}（Redis 24h，事务内推迟到提交后写）。</li>
 * <li>D-20：「10 分钟内已评」「同帖虚拟评论 ≥3」都只是提示，不拦截；不加每日上限。</li>
 * <li>只发一级评论（D-35）；身份只列虚拟账号池（真实身份池不出现）。</li>
 * </ul>
 */
@Service
public class VirtualCommentService {

    public static final int BODY_MAX = 200;
    public static final int MANY_VIRTUAL_THRESHOLD = 3;
    public static final Duration RECENT_WINDOW = Duration.ofMinutes(10);
    static final int SUMMARY_MAX = 80;
    static final int AUDIT_PREVIEW = 50;
    private static final int EXISTING_PAGE = 200;

    /** 发布结果：{@code replayed} = 幂等键命中，未产生第二条评论。 */
    public record Result(long commentId, boolean replayed) {
    }

    private final CommentService commentService;
    private final CommentRepository comments;
    private final UserRepository users;
    private final ContentPostRepository posts;
    private final ContentSpeciesResolver speciesResolver;
    private final IdempotencyService idempotency;
    private final AdminAuditService audit;
    private final SignedUrlService signedUrls;
    private final PostDistributionQuery distribution;

    public VirtualCommentService(CommentService commentService, CommentRepository comments, UserRepository users,
            ContentPostRepository posts, ContentSpeciesResolver speciesResolver, IdempotencyService idempotency,
            AdminAuditService audit, SignedUrlService signedUrls, PostDistributionQuery distribution) {
        this.commentService = commentService;
        this.comments = comments;
        this.users = users;
        this.posts = posts;
        this.speciesResolver = speciesResolver;
        this.idempotency = idempotency;
        this.audit = audit;
        this.signedUrls = signedUrls;
        this.distribution = distribution;
    }

    // ---------------------------------------------------------------- 抽屉

    /** 抽屉全部数据（AC1 / AC2 / AC4 / AC5）；帖子不存在 / 已删 → 404。{@code justSubmittedId} 非空时该条置顶并标「审核中」。 */
    @Transactional(readOnly = true)
    public VirtualCommentDrawer drawer(long postId, DistributionFilter filter, Long justSubmittedId) {
        ContentPost post = requirePost(postId, false);
        Map<Long, User> authorCache = new HashMap<>();
        User author = users.findById(post.getAuthorId()).orElse(null);
        String species = speciesResolver.resolve(post.getSpeciesOverride(), post.getAuthorId()).species();
        List<ExistingComment> existing = existingComments(postId, justSubmittedId, authorCache);
        long visible = comments.countByPostIdAndDeletedAtIsNullAndModerationStatus(postId, CommentModerationStatus.VISIBLE);
        long virtual = comments.countByPostAndAuthorType(postId, AccountType.VIRTUAL,
                List.of(CommentModerationStatus.VISIBLE, CommentModerationStatus.UNDER_REVIEW));

        List<User> pool = users.findByAccountTypeOrderByIdDesc(AccountType.VIRTUAL).stream().filter(User::isEnabled).toList();
        Map<Long, Long> today = todayCounts(pool.stream().map(User::getId).toList());
        List<IdentityOption> matching = new ArrayList<>();
        List<IdentityOption> others = new ArrayList<>();
        boolean anySpecies = species == null || "GENERAL".equals(species);
        for (User u : pool) {
            String acc = ContentSpeciesResolver.effectiveAccountSpecies(u);
            IdentityOption opt = new IdentityOption(u.getId(), u.getNickname(), acc, today.getOrDefault(u.getId(), 0L));
            if (anySpecies || species.equals(acc)) {
                matching.add(opt);
            } else {
                others.add(opt);
            }
        }
        Set<Long> poolIds = pool.stream().map(User::getId).collect(Collectors.toSet());
        List<Long> recent = comments.findRecentAuthorIdsOnPost(postId, Instant.now().minus(RECENT_WINDOW)).stream()
                .filter(poolIds::contains).toList();
        return new VirtualCommentDrawer(postId, summarize(post.getText(), SUMMARY_MAX), thumbnail(post),
                author == null ? "#" + post.getAuthorId() : displayName(author), author != null && author.getAccountType() == AccountType.VIRTUAL,
                species, visible, virtual, existing, List.copyOf(matching), List.copyOf(others), List.copyOf(recent),
                nextPostId(postId, filter), UUID.randomUUID().toString());
    }

    private List<ExistingComment> existingComments(long postId, Long justSubmittedId, Map<Long, User> cache) {
        List<Comment> top = comments.findByPostIdAndParentIdIsNull(postId,
                PageRequest.of(0, EXISTING_PAGE, Sort.by("createdAt", "id"))).getContent().stream()
                .filter(c -> c.getDeletedAt() == null).toList();
        List<Comment> replies = top.isEmpty() ? List.of()
                : comments.findByParentIdInOrderByCreatedAtAscIdAsc(top.stream().map(Comment::getId).toList()).stream()
                        .filter(c -> c.getDeletedAt() == null).toList();
        Set<Long> authorIds = new HashSet<>();
        top.forEach(c -> authorIds.add(c.getAuthorId()));
        replies.forEach(c -> authorIds.add(c.getAuthorId()));
        users.findAllById(authorIds).forEach(u -> cache.put(u.getId(), u));
        List<ExistingComment> out = new ArrayList<>();
        ExistingComment justSubmitted = null;
        for (Comment c : top) {
            ExistingComment e = toExisting(c, cache, justSubmittedId);
            if (e.justSubmitted()) {
                justSubmitted = e;
                continue;
            }
            out.add(e);
            for (Comment r : replies) {
                if (r.getParentId() != null && r.getParentId().equals(c.getId())) {
                    out.add(toExisting(r, cache, justSubmittedId));
                }
            }
        }
        if (justSubmitted != null) {
            out.add(0, justSubmitted); // AC4：刚提交的那条置顶并标「审核中」
        }
        return List.copyOf(out);
    }

    private static ExistingComment toExisting(Comment c, Map<Long, User> cache, Long justSubmittedId) {
        User u = cache.get(c.getAuthorId());
        boolean underReview = c.getModerationStatus() == CommentModerationStatus.UNDER_REVIEW;
        return new ExistingComment(c.getId(), c.getParentId(), u == null ? "#" + c.getAuthorId() : displayName(u),
                u != null && u.getAccountType() == AccountType.VIRTUAL, c.getBody(), c.getCreatedAt(),
                c.getModerationStatus().name(), underReview, justSubmittedId != null && justSubmittedId.equals(c.getId()));
    }

    private Map<Long, Long> todayCounts(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        LocalDate today = LocalDate.now(ScheduleWindow.WIB);
        Instant from = today.atStartOfDay(ScheduleWindow.WIB).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(ScheduleWindow.WIB).toInstant();
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : comments.countByAuthorsBetween(ids, from, to)) {
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return out;
    }

    /** 按 4-1 当前筛选结果集顺序取下一帖（同页内；本帖是页末或不在结果集 → null）。 */
    private Long nextPostId(long postId, DistributionFilter filter) {
        if (filter == null) {
            return null;
        }
        List<DistributionRow> rows = distribution.list(filter);
        for (int i = 0; i < rows.size() - 1; i++) {
            if (rows.get(i).postId() == postId) {
                return rows.get(i + 1).postId();
            }
        }
        return null;
    }

    private String thumbnail(ContentPost post) {
        List<String> imgs = post.getImageUrls();
        if (imgs == null || imgs.isEmpty() || imgs.get(0) == null || imgs.get(0).isBlank()) {
            return null;
        }
        String first = imgs.get(0);
        return first.startsWith("http://") || first.startsWith("https://") ? first : signedUrls.sign(first);
    }

    // ---------------------------------------------------------------- 发布

    /**
     * 以虚拟身份发一级评论（AC3 / AC5 / AC6）。顺序：幂等短路 → 校验身份 / 正文 / 帖子 → {@code createTopLevel}（L1 命中直接上抛 422）
     * → 审计（正文只记前 50 字）→ 幂等记录（提交后写）。
     */
    @Transactional
    public Result postAsVirtual(long postId, long virtualUserId, String body, String idempotencyKey, long actorAdminId) {
        Optional<Long> replay = idempotency.findResourceId(idempotencyKey);
        if (replay.isPresent()) {
            return new Result(replay.get(), true);
        }
        User identity = users.findById(virtualUserId).orElse(null);
        if (identity == null || identity.getAccountType() != AccountType.VIRTUAL || !identity.isEnabled()) {
            throw AppException.validation("虚拟账号不存在或已停用").code("admin.err.virtualComment.identityInvalid");
        }
        String text = body == null ? "" : body.strip();
        if (text.isEmpty() || text.length() > BODY_MAX) {
            throw AppException.validation("评论内容需为 1～200 字").code("admin.err.virtualComment.bodyInvalid");
        }
        requirePost(postId, true);
        CommentResponse created;
        try {
            created = commentService.createTopLevel(postId, virtualUserId, text);
        } catch (AppException e) {
            // L1 命中：App 侧只有中文 detail，后台三语按码渲染
            if (ErrorTypes.COMMENT_BLOCKED.equals(e.getType())) {
                throw e.code("admin.v130.comments.virtual.err.blocked");
            }
            throw e;
        }
        long commentId = created.id();
        audit.record(actorAdminId, AuditActions.COMMENT_VIRTUAL_POST, "COMMENT", String.valueOf(commentId),
                "postId=" + postId + ", virtualUserId=" + virtualUserId + ", body=" + summarize(text, AUDIT_PREVIEW));
        idempotency.store(idempotencyKey, commentId);
        return new Result(commentId, false);
    }

    private ContentPost requirePost(long postId, boolean mustBePublished) {
        ContentPost post = posts.findById(postId).filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("帖子不存在或已删除").code("admin.err.virtualComment.postGone"));
        if (mustBePublished && post.getStatus() != PostStatus.PUBLISHED) {
            throw AppException.notFound("帖子不可见，不能评论").code("admin.err.virtualComment.postHidden");
        }
        return post;
    }

    /** 摘要：折叠空白、截断到 {@code max} 字加省略号（正文预览 80 / 审计 50）。 */
    public static String summarize(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = text.strip().replaceAll("\\s+", " ");
        // 按码点截断，别劈开 emoji 代理对
        return t.codePointCount(0, t.length()) <= max ? t : t.substring(0, t.offsetByCodePoints(0, max)) + "…";
    }

    private static String displayName(User u) {
        return u.getNickname() == null || u.getNickname().isBlank() ? "#" + u.getId() : u.getNickname();
    }
}
