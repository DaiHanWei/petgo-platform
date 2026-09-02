package com.tailtopia.admin.usertag.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.usertag.dto.TaggableUserRow;
import com.tailtopia.admin.usertag.dto.UserAssignmentRow;
import com.tailtopia.admin.usertag.dto.UserTagRow;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.domain.UserTag;
import com.tailtopia.auth.domain.UserTagBadgeColor;
import com.tailtopia.auth.domain.UserTagAssignment;
import com.tailtopia.auth.dto.UserTagView;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.auth.repository.UserTagAssignmentRepository;
import com.tailtopia.auth.repository.UserTagRepository;
import com.tailtopia.auth.service.UserTagQueryService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户标签管理的后台视图与写入（Story 11.3 · AB-12A）。
 *
 * <h2>🔴 展示上限与排序：一律问 App 侧那份权威实现，本类不排序</h2>
 * 「同时只展示 3 个、按分配时间倒序」的实现是
 * {@link UserTagQueryService#findVisibleTags} —— 四处展示位（首页卡 / 详情页作者区 /
 * 评论区 / 迷你主页）全都经它拿标签。
 *
 * <p>后台要回答「这条分配现在会不会真的展示出来」时，<b>调它、拿它的答案</b>，
 * 而不是自己再写一遍"取前 3 个"。两处各写一遍的表现是
 * <b>后台显示会展示这三个、App 上却是另三个</b> —— 而运营手里没有任何线索能查。
 */
@Service
public class AdminUserTagService {

    private final UserTagRepository tags;
    private final UserTagAssignmentRepository assignments;
    private final UserRepository users;
    private final UserTagQueryService tagService;
    private final AdminAuditService audit;

    public AdminUserTagService(UserTagRepository tags, UserTagAssignmentRepository assignments,
            UserRepository users, UserTagQueryService tagService, AdminAuditService audit) {
        this.tags = tags;
        this.assignments = assignments;
        this.users = users;
        this.tagService = tagService;
        this.audit = audit;
    }

    // ——————————————————— 标签本体 ———————————————————

    @Transactional(readOnly = true)
    public List<UserTagRow> listTags(Instant now) {
        return tags.findAllByOrderByIdDesc().stream()
                .map(t -> new UserTagRow(t.getId(), t.getCode(), t.getName(), t.getIcon(),
                        t.getDescription(), t.getBadgeColor(), t.getRetiredAt(),
                        assignments.findActiveByTag(t.getId(), now).size()))
                .toList();
    }

    /** 分配下拉：仅在线标签。 */
    @Transactional(readOnly = true)
    public List<UserTag> assignableTags() {
        return tags.findByRetiredAtIsNullOrderByIdDesc();
    }

    /**
     * 新建标签。标签码<b>系统自动生成</b>（{@code ut-<自增id>}，2026-09-02）：
     * 原先由运营手填，多个运营会填出同一个码互相顶掉；码只进埋点与操作日志、
     * 用户看不到，没有任何理由让人来起名。（与内容标签同一改法。）
     *
     * <p>🔴 2026-09-02 产品定：<b>上传的图就是用户看到的整枚标签</b>（14×14 整图显示，
     * 端上不再画圆底）——「徽章底色」概念随之取消，不再入参。列上残留的 badge_color
     * 值仅供旧版本 App 兜底渲染，新链路不读不写。
     */
    @Transactional
    public void createTag(long adminId, String name, String icon, String description) {
        if (isBlank(name) || isBlank(icon) || isBlank(description)) {
            throw AppException.validation("名称、图标与说明文案均为必填")
                    .code("admin.err.userTag.fieldsRequired");
        }
        // 两步建号：唯一占位码 INSERT 拿自增 id → 同事务回填 ut-<id>（并发/回滚安全，
        // 详见 ContentTag.assignGeneratedCode 与内容标签侧同款注释）。
        UserTag saved = tags.save(UserTag.of(
                "ut-pending-" + java.util.UUID.randomUUID(), name, icon, description));
        String code = "ut-" + saved.getId();
        saved.assignGeneratedCode(code);
        audit.record(adminId, "USER_TAG_CREATE", "user_tag", String.valueOf(saved.getId()),
                "code=" + code + " name=" + name);
    }

    @Transactional
    public void editTag(long adminId, long id, String name, String icon, String description) {
        UserTag tag = tags.findById(id).orElseThrow(() -> AppException.notFound("标签不存在")
                .code("admin.err.userTag.notFound"));
        // Story 11.5：icon 为 null 表示"这次没传新文件" ⇒ **保留原图标**，不是清空。
        // 🛡 写成 tag.edit(name, icon, ...) 会把不改图标的那次编辑变成"把图标删了"，
        //    而那在界面上看不出来 —— 运营改个错别字，App 上的图标就没了。
        // 底色概念已取消（2026-09-02）：三参 edit 保持原 badge_color 不动（旧 App 兜底用）。
        tag.edit(name, icon == null ? tag.getIcon() : icon, description);
        audit.record(adminId, "USER_TAG_EDIT", "user_tag", String.valueOf(id),
                "name=" + name);
    }

    /** 下线 / 重新上线。🛡 下线只影响能否再分配，已分配的照旧生效到各自 ends_at。 */
    @Transactional
    public void setRetired(long adminId, long id, boolean retired) {
        UserTag tag = tags.findById(id).orElseThrow(() -> AppException.notFound("标签不存在")
                .code("admin.err.userTag.notFound"));
        if (retired) {
            tag.retire(Instant.now());
        } else {
            tag.restore();
        }
        audit.record(adminId, retired ? "USER_TAG_RETIRE" : "USER_TAG_RESTORE",
                "user_tag", String.valueOf(id), "code=" + tag.getCode());
    }

    // ——————————————————— 分配 ———————————————————

    /** 选择器每页候选数。与内容标签那边同量级：一屏能扫完，又不至于要翻很多页。 */
    private static final int PICK_PAGE_SIZE = 30;

    /**
     * 用户标签选择器的候选（bug 20260828）。
     *
     * <p>运营原先只能手填用户 ID —— 手上没有 ID 就无从下手，填错一位也没人拦，
     * 于是标签被分到了一个已注销账号上。这里给出与内容标签同形状的可搜索候选表。
     *
     * <p>🔴 已注销账号在**查询层**就被滤掉（见 {@code UserRepository#searchTaggableUsers}）。
     */
    @Transactional(readOnly = true)
    public List<TaggableUserRow> pickableUsers(String keyword, int page) {
        // 🔴 绝不传 null：无关键词 → "%" 匹配全部（首次加载走的正是这一支）。
        String pattern = (keyword == null || keyword.isBlank())
                ? "%" : "%" + keyword.trim().toLowerCase() + "%";
        return users.searchTaggableUsers(Role.USER, pattern,
                        PageRequest.of(Math.max(page, 0), PICK_PAGE_SIZE)).stream()
                .map(u -> new TaggableUserRow(
                        u.getId(),
                        displayNameOf(u),
                        u.getStatus() != UserStatus.ACTIVE,
                        u.getAccountType() == AccountType.VIRTUAL))
                .toList();
    }

    /** 昵称为空回落 displayName，都空给一个明确的占位（别在候选表里留一行空白）。 */
    private static String displayNameOf(com.tailtopia.auth.domain.User u) {
        if (u.getNickname() != null && !u.getNickname().isBlank()) {
            return u.getNickname();
        }
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) {
            return u.getDisplayName();
        }
        return "(未设昵称)";
    }

    /**
     * 批量分配同一标签给多个用户。
     *
     * <p>⚠️ 批量是"一次影响很多用户"的动作，因此：
     * <ul>
     *   <li>逐个用户独立处理，**单个失败不拖垮整批**（返回失败的用户 id 供回显）</li>
     *   <li>审计记录里带上本批的用户数与 id 列表，事后能追</li>
     * </ul>
     *
     * @return 分配失败的用户 id（成功的不返回）
     */
    @Transactional
    public List<Long> assignBulk(long adminId, List<Long> userIds, long tagId,
            Instant startsAt, Instant endsAt) {
        if (userIds == null || userIds.isEmpty()) {
            throw AppException.validation("请选择至少一个用户")
                    .code("admin.err.userTag.atLeastOneUser");
        }
        // 去重但保持顺序：同一用户在表单里被勾两次不该分配两条。
        Set<Long> unique = new LinkedHashSet<>(userIds);
        List<Long> failed = new java.util.ArrayList<>();
        for (Long uid : unique) {
            try {
                tagService.assign(uid, tagId, startsAt, endsAt);
            } catch (AppException e) {
                failed.add(uid);
            }
        }
        audit.record(adminId, "USER_TAG_ASSIGN_BULK", "user_tag", String.valueOf(tagId),
                "users=" + unique.size() + " failed=" + failed.size() + " ids=" + unique);
        return failed;
    }

    @Transactional
    public boolean unassign(long adminId, long assignmentId) {
        boolean removed = tagService.unassign(assignmentId);
        if (removed) {
            audit.record(adminId, "USER_TAG_UNASSIGN", "user_tag_assignment",
                    String.valueOf(assignmentId), null);
        }
        return removed;
    }

    /**
     * 🔴 某用户当前生效中的分配数 —— 供「已达 3 个展示上限」的提示判断。
     *
     * <p>注意这问的是「有几个在生效」，不是「会展示哪几个」。
     */
    @Transactional(readOnly = true)
    public long activeCount(long userId, Instant now) {
        return tagService.countActive(userId, now);
    }

    /** 展示上限，取自 App 侧那份权威实现的常量 —— 后台不另定义一个 3。 */
    public int maxVisible() {
        return UserTagQueryService.MAX_VISIBLE;
    }

    /** 按标签维度：该标签当前生效中的分配。 */
    @Transactional(readOnly = true)
    public List<UserAssignmentRow> assignmentsByTag(long tagId, Instant now) {
        return decorate(assignments.findActiveByTag(tagId, now), now);
    }

    /** 按用户维度：该用户全部分配（含已失效的历史）。 */
    @Transactional(readOnly = true)
    public List<UserAssignmentRow> assignmentsByUser(long userId, Instant now) {
        return decorate(assignments.findByUserIdOrderByStartsAtDesc(userId), now);
    }

    private List<UserAssignmentRow> decorate(List<UserTagAssignment> rows, Instant now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, UserTag> tagById = tags.findAllById(
                        rows.stream().map(UserTagAssignment::getTagId).distinct().toList()).stream()
                .collect(Collectors.toMap(UserTag::getId, Function.identity()));

        // 🔴 「这条会不会真的展示」直接问 App 侧那份权威实现，本类不排序、不截断。
        Set<Long> userIds = rows.stream().map(UserTagAssignment::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, List<UserTagView>> visible = tagService.findVisibleTags(userIds, now);
        // bug 20260828：「不展示」要能分辨原因 —— 被前 3 个顶掉 vs 账号已注销。
        Set<Long> deleted = tagService.deletedAmong(userIds);

        return rows.stream().map(a -> {
            UserTag t = tagById.get(a.getTagId());
            boolean shown = t != null && visible.getOrDefault(a.getUserId(), List.of()).stream()
                    .anyMatch(v -> v.code().equals(t.getCode()));
            return new UserAssignmentRow(a.getId(), a.getUserId(), a.getTagId(),
                    t == null ? null : t.getCode(), t == null ? null : t.getName(),
                    a.getStartsAt(), a.getEndsAt(), shown,
                    shown ? null : hiddenReason(a, deleted.contains(a.getUserId()), now));
        }).toList();
    }

    /**
     * 「不展示」的原因（bug 20260828）。
     *
     * <p>🔴 判定顺序 = **处置动作的优先级**，不是随手排的：
     * 账号没了就没有后续可言（撤掉），其次才轮到时间窗（等/改时间），
     * 都过了才是被顶掉（撤别的标签）。顺序反了会给出误导性的建议 ——
     * 比如对一个注销账号说「被顶掉了」，运营就会去撤别人的标签。
     */
    private static String hiddenReason(UserTagAssignment a, boolean userDeleted, Instant now) {
        if (userDeleted) {
            return UserAssignmentRow.REASON_DELETED_USER;
        }
        if (a.getStartsAt() != null && now.isBefore(a.getStartsAt())) {
            return UserAssignmentRow.REASON_NOT_STARTED;
        }
        if (a.getEndsAt() != null && !now.isBefore(a.getEndsAt())) {
            return UserAssignmentRow.REASON_ENDED;
        }
        return UserAssignmentRow.REASON_OVER_CAP;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
