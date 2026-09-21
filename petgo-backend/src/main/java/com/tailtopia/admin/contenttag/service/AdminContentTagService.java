package com.tailtopia.admin.contenttag.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.contenttag.dto.AssignmentRow;
import com.tailtopia.admin.contenttag.dto.TagRow;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentTag;
import com.tailtopia.content.domain.ContentTagBadgeStyle;
import com.tailtopia.content.domain.ContentTagAssignment;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.repository.ContentTagAssignmentRepository;
import com.tailtopia.content.repository.ContentTagRepository;
import com.tailtopia.content.service.ContentTagQueryService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 装饰标签管理的后台视图与写入（Story 11.2 · AB-10C）。
 *
 * <p>🔴 <b>打标机制本身不在这里</b>。打标（含「只有公开内容可打标」的校验）、生效判定、
 * ×1.3 加权的口径，都早在 Story 5.2 就随 {@link ContentTagQueryService} 落地了 ——
 * 那个 story 的注释原话：「后台入口本轮不做，所以校验落在这里」。本类是把它接上入口，
 * 并补上当时没有的两件事：<b>标签的增改下线</b> 与 <b>取消打标</b>。
 */
@Service
public class AdminContentTagService {

    /** 打标内容选择器每页条数。候选集接近全量内容，必须分页。 */
    private static final int PICK_PAGE_SIZE = 20;

    private static final int SUMMARY_MAX = 40;

    /** 分配记录每页条数（V1.3.0 Story 7.4 · AC3，抽屉页签二）。 */
    public static final int ASSIGNMENT_PAGE_SIZE = 20;

    private final ContentTagRepository tags;
    private final ContentTagAssignmentRepository assignments;
    private final ContentTagQueryService tagService;
    private final ContentPostRepository posts;
    private final AdminAuditService audit;
    /** 分配记录的「操作人」：分配表没有这一列，取自审计里那条 CONTENT_TAG_ASSIGN（Story 7.4 · AC3）。 */
    private final com.tailtopia.admin.audit.repository.AdminAuditLogRepository auditLogs;
    private final com.tailtopia.admin.account.repository.AdminAccountRepository adminAccounts;

    public AdminContentTagService(ContentTagRepository tags,
            ContentTagAssignmentRepository assignments, ContentTagQueryService tagService,
            ContentPostRepository posts, AdminAuditService audit,
            com.tailtopia.admin.audit.repository.AdminAuditLogRepository auditLogs,
            com.tailtopia.admin.account.repository.AdminAccountRepository adminAccounts) {
        this.tags = tags;
        this.assignments = assignments;
        this.tagService = tagService;
        this.posts = posts;
        this.audit = audit;
        this.auditLogs = auditLogs;
        this.adminAccounts = adminAccounts;
    }

    /**
     * 摘要条（Story 7.4 · AC1）：在线标签数 · 生效中分配数。
     *
     * <p>⚠️ **纯函数**，接收已经算好的列表行：列表页两样都要，自己再查一遍就是把
     * 同一批查询跑两遍，而且两次各取一次 {@code now}、跨秒时还能给出对不上的两个数。
     */
    public com.tailtopia.admin.contenttag.dto.TagSummary summary(List<TagRow> rows) {
        return new com.tailtopia.admin.contenttag.dto.TagSummary(
                rows.stream().filter(t -> !t.retired()).count(),
                rows.stream().mapToLong(TagRow::activeAssignments).sum());
    }

    /** 一页分配记录 + 有无下一页。 */
    public record AssignmentPage(List<AssignmentRow> rows, boolean hasNext, int page, long total) {
    }

    /**
     * 抽屉页签二（Story 7.4 · AC3）：该标签的分配记录，**含已到期**，打标时间倒序 + 分页。
     *
     * <p>⚠️ 与旧的 {@code assignmentsByTag} 不同，这里<b>不只看生效中的</b> ——
     * AC3 要的正是「生效中 / 已到期」两态并列，只列生效中的话运营看不出「上周那次打标到期了没」。
     */
    @Transactional(readOnly = true)
    public AssignmentPage assignmentPage(long tagId, Instant now, int page) {
        var sort = org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id");
        int wanted = Math.max(page, 0);
        var found = assignments.findByTagId(tagId,
                PageRequest.of(wanted, ASSIGNMENT_PAGE_SIZE, sort));
        // 页码越界（处置掉最后一页最后一条后按原页码重拉，或有人手改 URL）→ 回退到最后一页，
        // 并把**实际**页码带给分页器；不是 500、也不是一张让人以为「记录没了」的空表。
        long total = found.getTotalElements();
        int lastPage = total == 0 ? 0 : (int) ((total - 1) / ASSIGNMENT_PAGE_SIZE);
        if (wanted > lastPage) {
            wanted = lastPage;
            found = assignments.findByTagId(tagId, PageRequest.of(wanted, ASSIGNMENT_PAGE_SIZE, sort));
        }
        return new AssignmentPage(decorate(found.getContent(), now), wanted < lastPage, wanted, total);
    }

    // ——————————————————— 标签本体 ———————————————————

    @Transactional(readOnly = true)
    public List<TagRow> listTags(Instant now) {
        Map<Long, Long> counts = activeCounts(now);
        return tags.findAllByOrderByIdDesc().stream()
                .map(t -> row(t, counts.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    /** 抽屉头（Story 7.4 · AC2）：单个标签，口径与列表行同一处生成。 */
    @Transactional(readOnly = true)
    public TagRow tag(long id, Instant now) {
        ContentTag t = tags.findById(id)
                .orElseThrow(() -> AppException.notFound("标签不存在")
                        .code("admin.err.contentTag.notFound"));
        return row(t, assignments.findActiveByTag(id, now).size());
    }

    /** 标签 id → 生效中分配条数，整页一条聚合取（逐个标签查一次就是 N+1）。 */
    private Map<Long, Long> activeCounts(Instant now) {
        Map<Long, Long> out = new java.util.HashMap<>();
        for (Object[] row : assignments.countActiveByTag(now)) {
            out.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return out;
    }

    private TagRow row(ContentTag t, long activeAssignments) {
        return new TagRow(t.getId(), t.getCode(), t.getName(), t.getIcon(),
                t.getDescription(), t.getBadgeStyle(), t.getRetiredAt(), activeAssignments);
    }

    /**
     * 取消打标后要回到哪个标签的抽屉（Story 7.4）。
     *
     * <p>⚠️ 必须在删除**之前**读 —— 删完就查不到了；而给
     * {@code POST /assignments/{id}/remove} 加一个 {@code tagId} 参数会破坏「5 个 POST 端点零变更」。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> tagIdOfAssignment(long assignmentId) {
        return assignments.findById(assignmentId).map(ContentTagAssignment::getTagId);
    }

    /**
     * 新建标签。标签码<b>系统自动生成</b>（{@code ct-<自增id>}，2026-09-02）：
     * 原先由运营手填，多个运营会填出同一个码互相顶掉；码只进埋点与操作日志、
     * 用户看不到，没有任何理由让人来起名。
     *
     * @return 新标签 id（Story 7.4 · AC4：建完要立刻打开它的抽屉并停在「分配记录」页签；
     *         既有整页调用方忽略返回值，行为不变）
     */
    @Transactional
    public long createTag(long adminId, String name, String icon, String description,
            String badgeStyle) {
        if (name == null || name.isBlank()
                || icon == null || icon.isBlank() || description == null || description.isBlank()) {
            throw AppException.validation("名称、图标与说明文案均为必填")
                    .code("admin.err.contentTag.fieldsRequired");
        }
        // ⚠️ 宽松解析、不抛：底色从下拉里选，值不对只可能是有人手改了请求 ——
        //    为此让整次建标签失败不划算，回落 UI 稿原始的橙→红即可。
        ContentTagBadgeStyle style = ContentTagBadgeStyle.parse(badgeStyle);
        // 两步建号：先以**唯一占位码** INSERT 拿到自增 id，再在同一事务里回填 ct-<id>。
        // 占位码带 UUID 是为并发兜底（code 列有唯一约束，两个运营同时点「新建」也不撞）；
        // 事务失败整体回滚，占位码不会留在库里。
        ContentTag saved = tags.save(ContentTag.of(
                "ct-pending-" + java.util.UUID.randomUUID(), name, icon, description, style));
        String code = "ct-" + saved.getId();
        saved.assignGeneratedCode(code);
        audit.record(adminId, "CONTENT_TAG_CREATE", "content_tag", String.valueOf(saved.getId()),
                "code=" + code + " name=" + name + " style=" + style);
        return saved.getId();
    }

    @Transactional
    public void editTag(long adminId, long id, String name, String icon, String description,
            String badgeStyle) {
        ContentTag tag = tags.findById(id)
                .orElseThrow(() -> AppException.notFound("标签不存在")
                        .code("admin.err.contentTag.notFound"));
        // Story 11.5：icon 为 null 表示"这次没传新文件" ⇒ **保留原图标**，不是清空。
        // 🛡 直接传 icon 会把"只改错别字"的那次编辑变成"把图标删了"，
        //    而那在后台界面上看不出来，只有 App 上图标消失才会被发现。
        ContentTagBadgeStyle style = ContentTagBadgeStyle.parse(badgeStyle);
        tag.edit(name, icon == null ? tag.getIcon() : icon, description, style);
        audit.record(adminId, "CONTENT_TAG_EDIT", "content_tag", String.valueOf(id),
                "name=" + name + " style=" + style);
    }

    /**
     * 下线 / 重新上线。
     *
     * <p>🛡 下线**只影响能否再分配**，已分配的照旧生效到各自 {@code ends_at}。
     * 真要立刻全部失效，运营应逐条取消分配 —— 那是另一个动作，刻意不合并。
     */
    @Transactional
    public void setRetired(long adminId, long id, boolean retired) {
        ContentTag tag = tags.findById(id)
                .orElseThrow(() -> AppException.notFound("标签不存在")
                        .code("admin.err.contentTag.notFound"));
        if (retired) {
            tag.retire(Instant.now());
        } else {
            tag.restore();
        }
        audit.record(adminId, retired ? "CONTENT_TAG_RETIRE" : "CONTENT_TAG_RESTORE",
                "content_tag", String.valueOf(id), "code=" + tag.getCode());
    }

    // ——————————————————— 分配 ———————————————————

    /**
     * 打标。🛡 「只有公开内容可打标」与「已下线标签不可分配」两条校验都在
     * {@link ContentTagQueryService#assign} 里 —— 本类不重复实现，也不绕过。
     */
    @Transactional
    public void assign(long adminId, long postId, long tagId, Instant startsAt, Instant endsAt) {
        ContentTagAssignment saved = tagService.assign(postId, tagId, startsAt, endsAt);
        audit.record(adminId, "CONTENT_TAG_ASSIGN", "content_tag_assignment",
                String.valueOf(saved.getId()),
                "postId=" + postId + " tagId=" + tagId);
    }

    @Transactional
    public boolean unassign(long adminId, long assignmentId) {
        boolean removed = tagService.unassign(assignmentId);
        if (removed) {
            audit.record(adminId, "CONTENT_TAG_UNASSIGN", "content_tag_assignment",
                    String.valueOf(assignmentId), null);
        }
        return removed;
    }

    /** 打标内容选择器：复用顶置那条「只返回可公开展示内容」的分页查询，不另写一份。 */
    @Transactional(readOnly = true)
    public List<com.tailtopia.admin.pin.dto.PinnableContentRow> pickable(String keyword, int page) {
        // 🔴 绝不传 null：绑 null 时 Postgres 推不出类型（lower(bytea) does not exist），
        //    而"不带关键词"正是页面首次加载的那一次。无关键词 → "%" 匹配全部。
        String pattern = (keyword == null || keyword.isBlank())
                ? "%" : "%" + keyword.trim().toLowerCase() + "%";
        return posts.searchPinnable(pattern, PageRequest.of(Math.max(page, 0), PICK_PAGE_SIZE)).stream()
                .map(p -> new com.tailtopia.admin.pin.dto.PinnableContentRow(
                        p.getId(), p.getType().name(), truncate(p.getText()), p.getCreatedAt()))
                .toList();
    }

    private List<AssignmentRow> decorate(List<ContentTagAssignment> rows, Instant now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ContentPost> postById = posts.findAllById(
                        rows.stream().map(ContentTagAssignment::getPostId).distinct().toList()).stream()
                .collect(Collectors.toMap(ContentPost::getId, Function.identity()));
        Map<Long, ContentTag> tagById = tags.findAllById(
                        rows.stream().map(ContentTagAssignment::getTagId).distinct().toList()).stream()
                .collect(Collectors.toMap(ContentTag::getId, Function.identity()));
        Map<Long, String> actorByAssignment = actorNames(rows);
        return rows.stream().map(a -> {
            ContentPost p = postById.get(a.getPostId());
            ContentTag t = tagById.get(a.getTagId());
            // 生效判定与查询侧同一口径：[starts_at, ends_at)，ends_at 空 = 永久。
            boolean pending = a.getStartsAt().isAfter(now);
            boolean active = !pending && (a.getEndsAt() == null || a.getEndsAt().isAfter(now));
            return new AssignmentRow(a.getId(), a.getPostId(),
                    p == null ? null : truncate(p.getText()),
                    a.getTagId(), t == null ? null : t.getName(),
                    a.getStartsAt(), a.getEndsAt(), a.getCreatedAt(),
                    actorByAssignment.get(a.getId()), active, pending);
        }).toList();
    }

    /**
     * 分配 id → 操作人显示名，整页一次取（逐行查审计就是 N+1）。
     *
     * <p>⚠️ 取不到是**正常情况**：分配表没有操作人列，这里靠审计里那条 {@code CONTENT_TAG_ASSIGN}
     * 反查；经其它路径落库的历史记录没有对应审计行，界面上显示「—」。
     */
    private Map<Long, String> actorNames(List<ContentTagAssignment> rows) {
        List<String> ids = rows.stream().map(a -> String.valueOf(a.getId())).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        var logs = auditLogs.findByActionTypeAndTargetIdIn("CONTENT_TAG_ASSIGN", ids);
        if (logs.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nameByAccount = new java.util.HashMap<>();
        adminAccounts.findAllById(logs.stream().map(l -> l.getActorAccountId())
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(acc -> nameByAccount.put(acc.getId(), acc.getDisplayName()));
        Map<Long, String> out = new java.util.HashMap<>();
        for (var log : logs) {
            try {
                out.putIfAbsent(Long.parseLong(log.getTargetId()),
                        nameByAccount.getOrDefault(log.getActorAccountId(),
                                log.getActorAccountId() == null ? null : "#" + log.getActorAccountId()));
            } catch (NumberFormatException ignored) {
                // 目标不是数字 id（不该出现）：跳过，不因为一条脏审计毁掉整页
            }
        }
        return out;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() <= SUMMARY_MAX ? t : t.substring(0, SUMMARY_MAX) + "…";
    }
}
