package com.tailtopia.auth.service;

import com.tailtopia.auth.domain.UserTag;
import com.tailtopia.auth.domain.UserTagAssignment;
import com.tailtopia.auth.dto.UserTagView;
import com.tailtopia.auth.repository.UserTagAssignmentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户标签取数（V1.1.6 Story 5.1 · FR-74 / AD-11）。
 *
 * <p>🛡 **只有批量入口**。四处展示位（首页卡 / 详情页作者区 / 评论区 / 迷你主页）
 * 全都经作者投影拿标签，所以**没有哪一处能绕过去逐条查** —— 这比"四处各写一遍取数、
 * 再各自记得写成批量"稳得多。
 */
@Service
public class UserTagQueryService {

    /** 🛡 同时展示上限（AD-10 Rule 4）。超出的分配记录**保留在库、仅不展示**。 */
    public static final int MAX_VISIBLE = 3;

    private final UserTagAssignmentRepository assignments;
    private final com.tailtopia.auth.repository.UserTagRepository tags;

    public UserTagQueryService(UserTagAssignmentRepository assignments,
            com.tailtopia.auth.repository.UserTagRepository tags) {
        this.assignments = assignments;
        this.tags = tags;
    }

    /**
     * 给一个用户分配标签（Story 11.3 后台接入）。
     *
     * <p>🛡 校验落在这里而不是后台层，理由与装饰标签同：后台是目前唯一的入口，
     * 但把规则放在机制这一侧，将来多一个入口也不会漏。
     * <ul>
     *   <li>已下线的标签不可再分配（已分配的不受影响 —— 下线是"不再发新的"）</li>
     *   <li>{@code endsAt} 可空 = 永久；非空时必须晚于 {@code startsAt}</li>
     * </ul>
     *
     * <p>⚠️ **分配数量不设上限** —— 展示才封顶 {@link #MAX_VISIBLE} 个。
     * 超出的分配记录保留在库、仅不展示（AD-10 Rule 4）。
     */
    @Transactional
    public UserTagAssignment assign(long userId, long tagId, Instant startsAt, Instant endsAt) {
        UserTag tag = tags.findById(tagId)
                .orElseThrow(() -> com.tailtopia.shared.error.AppException.validation("标签不存在"));
        if (tag.isRetired()) {
            throw com.tailtopia.shared.error.AppException.validation("该标签已下线，不能再分配");
        }
        if (startsAt == null || (endsAt != null && !endsAt.isAfter(startsAt))) {
            throw com.tailtopia.shared.error.AppException.validation("结束时间必须晚于开始时间");
        }
        return assignments.save(UserTagAssignment.of(userId, tagId, startsAt, endsAt));
    }

    /** 取消分配。不存在视为幂等 no-op。 */
    @Transactional
    public boolean unassign(long assignmentId) {
        return assignments.findById(assignmentId).map(a -> {
            assignments.delete(a);
            return true;
        }).orElse(false);
    }

    /**
     * 某用户当前生效中的分配**数量**（后台"第 4 个"提示用）。
     *
     * <p>⚠️ 与 {@link #findVisibleTags} 是两个不同的问题：这问「有几个在生效」，
     * 那问「会展示哪几个」。后台需要"会展示哪 3 个"时**必须调 findVisibleTags**，
     * 不要用本方法的数字自己再排一遍序。
     */
    @Transactional(readOnly = true)
    public long countActive(long userId, Instant now) {
        return assignments.countActiveByUser(userId, now);
    }

    /**
     * 一批用户各自当前生效中的标签，**每人最多 {@link #MAX_VISIBLE} 个**（按分配时间倒序取最近的）。
     *
     * <p>空集合直接短路，不发这次查询 —— Feed 每页都要调一次，纯文字页没必要白跑。
     */
    @Transactional(readOnly = true)
    public Map<Long, List<UserTagView>> findVisibleTags(Collection<Long> userIds, Instant now) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<UserTagView>> byUser = new HashMap<>();
        // 查询已按分配时间倒序，这里只管按人分组后截断。
        for (Object[] row : assignments.findActiveWithTag(userIds, now)) {
            UserTagAssignment a = (UserTagAssignment) row[0];
            UserTag t = (UserTag) row[1];
            List<UserTagView> list = byUser.computeIfAbsent(a.getUserId(), k -> new ArrayList<>());
            if (list.size() < MAX_VISIBLE) {
                list.add(new UserTagView(t.getCode(), t.getName(), t.getIcon(), t.getDescription()));
            }
        }
        return byUser;
    }
}
