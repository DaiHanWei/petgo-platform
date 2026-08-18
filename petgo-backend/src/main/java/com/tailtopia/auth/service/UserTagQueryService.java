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

    public UserTagQueryService(UserTagAssignmentRepository assignments) {
        this.assignments = assignments;
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
