package com.tailtopia.admin.moderation.dto;

import com.tailtopia.admin.moderation.read.ViolationType;
import java.util.Map;

/**
 * 账号累计违规计数视图投影（story 8，§5.4 展示）。由 {@code ViolationCountReader} 的 Map 快照转来，
 * 提供模板友好的具名访问（避免 Thymeleaf 按枚举键索引 Map 的别扭写法）。
 *
 * @param post    帖子违规累计
 * @param comment 评论违规累计
 * @param name    名称违规累计
 * @param avatar  头像违规累计
 */
public record ViolationCounts(int post, int comment, int name, int avatar) {

    /** 空计数（作者不可解析 / story 9 未接入 → 展示「—」）。 */
    public static ViolationCounts empty() {
        return new ViolationCounts(0, 0, 0, 0);
    }

    /** 由端口 Map 快照构造（缺省类型视作 0）。null → 空计数。 */
    public static ViolationCounts fromMap(Map<ViolationType, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return empty();
        }
        return new ViolationCounts(
                counts.getOrDefault(ViolationType.POST, 0),
                counts.getOrDefault(ViolationType.COMMENT, 0),
                counts.getOrDefault(ViolationType.NAME, 0),
                counts.getOrDefault(ViolationType.AVATAR, 0));
    }

    /** 累计总数（>0 时模板才展示徽标）。 */
    public int total() {
        return post + comment + name + avatar;
    }
}
