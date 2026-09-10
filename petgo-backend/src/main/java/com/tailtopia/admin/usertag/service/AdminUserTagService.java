package com.tailtopia.admin.usertag.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.usertag.dto.TaggableUserRow;
import com.tailtopia.admin.usertag.dto.UserAssignmentRow;
import com.tailtopia.admin.usertag.dto.UserTagRow;
import com.tailtopia.admin.usertag.dto.UserTagSummary;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.UserStatus;
import com.tailtopia.auth.domain.UserTag;
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
    /**
     * 分配记录的「操作人」列（Story 8.2 · AC3）：分配表本身没有这一列，
     * 靠审计里那条 {@code USER_TAG_ASSIGN} 反查。
     */
    private final com.tailtopia.admin.audit.repository.AdminAuditLogRepository auditLogs;
    private final com.tailtopia.admin.account.repository.AdminAccountRepository adminAccounts;

    public AdminUserTagService(UserTagRepository tags, UserTagAssignmentRepository assignments,
            UserRepository users, UserTagQueryService tagService, AdminAuditService audit,
            com.tailtopia.admin.audit.repository.AdminAuditLogRepository auditLogs,
            com.tailtopia.admin.account.repository.AdminAccountRepository adminAccounts) {
        this.tags = tags;
        this.assignments = assignments;
        this.users = users;
        this.tagService = tagService;
        this.audit = audit;
        this.auditLogs = auditLogs;
        this.adminAccounts = adminAccounts;
    }

    /** 抽屉页签二每页条数。与内容标签同量级（480px 抽屉一屏扫得完）。 */
    private static final int ASSIGNMENT_PAGE_SIZE = 20;

    // ——————————————————— 标签本体 ———————————————————

    @Transactional(readOnly = true)
    public List<UserTagRow> listTags(Instant now) {
        return tags.findAllByOrderByIdDesc().stream().map(t -> toRow(t, now)).toList();
    }

    /**
     * 摘要条两格（Story 8.2 · AC1）。
     *
     * <p>⚠️ 入参就是列表那一份 {@code rows}，不另查一遍：各查各的不只是多一倍查询，
     * 跨秒时「生效中分配数」还能与表格里那一列对不上（7.4 同款）。
     */
    public UserTagSummary summary(List<UserTagRow> rows) {
        return new UserTagSummary(
                rows.stream().filter(r -> !r.retired()).count(),
                rows.stream().mapToLong(UserTagRow::activeAssignments).sum());
    }

    /** 单个标签（抽屉头 / 编辑页签）。不存在 → 404。 */
    @Transactional(readOnly = true)
    public UserTagRow tag(long id, Instant now) {
        UserTag t = tags.findById(id).orElseThrow(() -> AppException.notFound("标签不存在")
                .code("admin.err.userTag.notFound"));
        return toRow(t, now);
    }

    private UserTagRow toRow(UserTag t, Instant now) {
        return new UserTagRow(t.getId(), t.getCode(), t.getName(), t.getIcon(),
                t.getDescription(), t.getBadgeColor(), t.getRetiredAt(),
                assignments.findActiveByTag(t.getId(), now).size());
    }

    /** 抽屉页签二的一页分配记录（Story 8.2 · AC3）。 */
    public record AssignmentPage(List<UserAssignmentRow> rows, boolean hasNext, int page,
            long total) {
    }

    /**
     * 该标签的分配记录，**含已到期**，分配时间倒序 + 分页（Story 8.2 · AC3）。
     *
     * <p>⚠️ 与旧的 {@code assignmentsByTag} 不同，这里<b>不只看生效中的</b> ——
     * AC3 要的正是三态并列，只列生效中的话运营看不出「上周那次分配到期了没」，
     * 只会看到它凭空消失。
     */
    @Transactional(readOnly = true)
    public AssignmentPage assignmentPage(long tagId, Instant now, int page) {
        var sort = org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id");
        int wanted = Math.max(page, 0);
        var found = assignments.findByTagId(tagId, PageRequest.of(wanted, ASSIGNMENT_PAGE_SIZE, sort));
        // 页码越界（移除掉最后一页最后一条后按原页码重拉，或有人手改 URL）→ 回退到最后一页，
        // 并把**实际**页码带给分页器；不是 500、也不是一张让人以为「记录没了」的空表。
        long total = found.getTotalElements();
        int lastPage = total == 0 ? 0 : (int) ((total - 1) / ASSIGNMENT_PAGE_SIZE);
        if (wanted > lastPage) {
            wanted = lastPage;
            found = assignments.findByTagId(tagId, PageRequest.of(wanted, ASSIGNMENT_PAGE_SIZE, sort));
        }
        return new AssignmentPage(decorate(found.getContent(), now), wanted < lastPage, wanted, total);
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
    public long createTag(long adminId, String name, String icon, String description) {
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
        // Story 8.2 · AC4：返回新 id —— 建完标签要立刻把它的抽屉开在「分配记录」页签。
        return saved.getId();
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
        List<com.tailtopia.auth.domain.User> hits = users.searchTaggableUsers(Role.USER, pattern,
                PageRequest.of(Math.max(page, 0), PICK_PAGE_SIZE)).getContent();
        if (hits.isEmpty()) {
            return List.of();
        }
        // 「满 3 会顶掉最早的」预告（Story 8.2 · AC3）：整页一次统计，逐行问一次就是 N+1。
        Map<Long, Long> activeByUser = new java.util.HashMap<>();
        for (Object[] row : assignments.countActiveByUsers(
                hits.stream().map(com.tailtopia.auth.domain.User::getId).toList(), Instant.now())) {
            activeByUser.put((Long) row[0], (Long) row[1]);
        }
        return hits.stream()
                .map(u -> new TaggableUserRow(
                        u.getId(),
                        displayNameOf(u),
                        u.getStatus() != UserStatus.ACTIVE,
                        u.getAccountType() == AccountType.VIRTUAL,
                        activeByUser.getOrDefault(u.getId(), 0L)))
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
                UserTagAssignment saved = tagService.assign(uid, tagId, startsAt, endsAt);
                // Story 8.2 · AC3：**逐条**再记一行（target = 本条分配 id）——
                // 抽屉里的「操作人」列靠它反查。汇总那条照旧写（下面），两条各有用途：
                // 汇总回答「这一批是谁一次分出去的」，逐条回答「这一行是谁分的」。
                audit.record(adminId, "USER_TAG_ASSIGN", "user_tag_assignment",
                        String.valueOf(saved.getId()), "tag=" + tagId + " user=" + uid);
            } catch (AppException e) {
                failed.add(uid);
            }
        }
        audit.record(adminId, "USER_TAG_ASSIGN_BULK", "user_tag", String.valueOf(tagId),
                "users=" + unique.size() + " failed=" + failed.size() + " ids=" + unique);
        return failed;
    }

    /**
     * 移除一条分配。
     *
     * <p>返回**被移除记录所属的标签 id**（没找到 → empty）：Story 8.2 起抽屉要按这个 id
     * 重渲染分配记录页签。
     * ⚠️ 刻意不让控制器把 tagId 当请求参数传上来 —— AC5 要求这个端点的参数名逐字不变，
     * 而且客户端传上来的 tagId 本来就该以库里那条记录为准。
     */
    @Transactional
    public java.util.Optional<Long> unassign(long adminId, long assignmentId) {
        // 先取 tagId 再删：删完就查不到了。
        Long tagId = assignments.findById(assignmentId).map(UserTagAssignment::getTagId).orElse(null);
        boolean removed = tagService.unassign(assignmentId);
        if (!removed) {
            return java.util.Optional.empty();
        }
        audit.record(adminId, "USER_TAG_UNASSIGN", "user_tag_assignment",
                String.valueOf(assignmentId), tagId == null ? null : "tag=" + tagId);
        return java.util.Optional.ofNullable(tagId);
    }

    /** 展示上限，取自 App 侧那份权威实现的常量 —— 后台不另定义一个 3。 */
    public int maxVisible() {
        return UserTagQueryService.MAX_VISIBLE;
    }

    /**
     * 按用户维度：该用户全部分配（含已失效的历史）。
     *
     * <p>⚠️ <b>V1.3.0 Story 8.2 起页面上没有消费者</b> —— AC1 把「按用户看」这个筛选维度
     * 随分配记录区块一并退役了（抽屉只按标签看）。保留它有两个理由：
     * <ul>
     *   <li>它是 {@code AdminUserTagIntegrationTest} 里那组
     *       「后台算出来的 visible 必须与 App 侧 {@code findVisibleTags} 给出同一答案」
     *       断言唯一的入口 —— 那是本模块最安全攸关的一条约束，不能为了消掉一个未被调用的
     *       方法而把它一起删掉；</li>
     *   <li>「某个用户身上有哪些标签」这个问题本身没有消失，只是暂时没有页面回答它
     *       （已记入 Story 8.2 的 Completion Notes 待拍板：要么并进 B7 用户抽屉，要么明确不做）。</li>
     * </ul>
     */
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

        // 「用户」列（Story 8.2 · AC3）：整页一次取，逐行 findById 就是 N+1。
        Map<Long, String> nameByUser = new java.util.HashMap<>();
        users.findAllById(userIds).forEach(u -> nameByUser.put(u.getId(), displayNameOf(u)));
        Map<Long, String> actorByAssignment = actorNames(rows);

        return rows.stream().map(a -> {
            UserTag t = tagById.get(a.getTagId());
            boolean shown = t != null && visible.getOrDefault(a.getUserId(), List.of()).stream()
                    .anyMatch(v -> v.code().equals(t.getCode()));
            // 生效判定与查询侧同一口径：[starts_at, ends_at)，ends_at 空 = 永久。
            boolean pending = a.getStartsAt() != null && a.getStartsAt().isAfter(now);
            boolean active = !pending && (a.getEndsAt() == null || a.getEndsAt().isAfter(now));
            return new UserAssignmentRow(a.getId(), a.getUserId(),
                    nameByUser.get(a.getUserId()),
                    a.getTagId(),
                    t == null ? null : t.getCode(), t == null ? null : t.getName(),
                    a.getStartsAt(), a.getEndsAt(), a.getCreatedAt(),
                    actorByAssignment.get(a.getId()),
                    active, pending, shown,
                    shown ? null : hiddenReason(a, deleted.contains(a.getUserId()), now));
        }).toList();
    }

    /**
     * 分配 id → 操作人显示名，整页一次取（逐行查审计就是 N+1）。
     *
     * <p>⚠️ 取不到是**正常情况**：分配表没有操作人列，这里靠审计里那条 {@code USER_TAG_ASSIGN}
     * 反查；8.2 之前落库的记录只有一条汇总审计（{@code USER_TAG_ASSIGN_BULK}，target = 标签 id），
     * 逐条对不上，界面显示「—」。
     */
    private Map<Long, String> actorNames(List<UserTagAssignment> rows) {
        List<String> ids = rows.stream().map(a -> String.valueOf(a.getId())).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        var logs = auditLogs.findByActionTypeAndTargetIdIn("USER_TAG_ASSIGN", ids);
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
