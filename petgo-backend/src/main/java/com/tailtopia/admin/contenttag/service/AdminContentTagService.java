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

    private final ContentTagRepository tags;
    private final ContentTagAssignmentRepository assignments;
    private final ContentTagQueryService tagService;
    private final ContentPostRepository posts;
    private final AdminAuditService audit;

    public AdminContentTagService(ContentTagRepository tags,
            ContentTagAssignmentRepository assignments, ContentTagQueryService tagService,
            ContentPostRepository posts, AdminAuditService audit) {
        this.tags = tags;
        this.assignments = assignments;
        this.tagService = tagService;
        this.posts = posts;
        this.audit = audit;
    }

    // ——————————————————— 标签本体 ———————————————————

    @Transactional(readOnly = true)
    public List<TagRow> listTags(Instant now) {
        return tags.findAllByOrderByIdDesc().stream()
                .map(t -> new TagRow(t.getId(), t.getCode(), t.getName(), t.getIcon(),
                        t.getDescription(), t.getBadgeStyle(), t.getRetiredAt(),
                        assignments.findActiveByTag(t.getId(), now).size()))
                .toList();
    }

    /** 打标下拉：仅在线标签（已下线的不可再分配）。 */
    @Transactional(readOnly = true)
    public List<ContentTag> assignableTags() {
        return tags.findByRetiredAtIsNullOrderByIdDesc();
    }

    @Transactional
    public void createTag(long adminId, String code, String name, String icon, String description,
            String badgeStyle) {
        if (code == null || code.isBlank() || name == null || name.isBlank()
                || icon == null || icon.isBlank() || description == null || description.isBlank()) {
            throw AppException.validation("标签码、名称、图标与说明文案均为必填")
                    .code("admin.err.contentTag.fieldsRequired");
        }
        tags.findByCode(code).ifPresent(t -> {
            throw AppException.validation("标签码已存在：" + code)
                    .code("admin.err.contentTag.codeExists", code);
        });
        // ⚠️ 宽松解析、不抛：底色从下拉里选，值不对只可能是有人手改了请求 ——
        //    为此让整次建标签失败不划算，回落 UI 稿原始的橙→红即可。
        ContentTagBadgeStyle style = ContentTagBadgeStyle.parse(badgeStyle);
        ContentTag saved = tags.save(ContentTag.of(code, name, icon, description, style));
        audit.record(adminId, "CONTENT_TAG_CREATE", "content_tag", String.valueOf(saved.getId()),
                "code=" + code + " name=" + name + " style=" + style);
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

    /** 按标签维度：该标签当前生效中的分配。 */
    @Transactional(readOnly = true)
    public List<AssignmentRow> assignmentsByTag(long tagId, Instant now) {
        List<ContentTagAssignment> rows = assignments.findActiveByTag(tagId, now);
        return decorate(rows);
    }

    /** 按内容维度：该内容的全部分配（含已失效的历史）。 */
    @Transactional(readOnly = true)
    public List<AssignmentRow> assignmentsByPost(long postId) {
        return decorate(assignments.findByPostIdOrderByStartsAtDesc(postId));
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

    private List<AssignmentRow> decorate(List<ContentTagAssignment> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ContentPost> postById = posts.findAllById(
                        rows.stream().map(ContentTagAssignment::getPostId).distinct().toList()).stream()
                .collect(Collectors.toMap(ContentPost::getId, Function.identity()));
        Map<Long, ContentTag> tagById = tags.findAllById(
                        rows.stream().map(ContentTagAssignment::getTagId).distinct().toList()).stream()
                .collect(Collectors.toMap(ContentTag::getId, Function.identity()));
        return rows.stream().map(a -> {
            ContentPost p = postById.get(a.getPostId());
            ContentTag t = tagById.get(a.getTagId());
            return new AssignmentRow(a.getId(), a.getPostId(),
                    p == null ? null : truncate(p.getText()),
                    a.getTagId(), t == null ? null : t.getName(),
                    a.getStartsAt(), a.getEndsAt());
        }).toList();
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() <= SUMMARY_MAX ? t : t.substring(0, SUMMARY_MAX) + "…";
    }
}
