package com.tailtopia.social.service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 自动关注的目标账号（V1.4.1，2026-10-29 拍板）：新用户自动关注、老用户一次性补关注的对象。
 *
 * <p>原来是「所有 role=ADMIN 的官方号」（生产即 TailTopia 运营号）；现改为<b>配置指定</b>
 * （env {@code AUTO_FOLLOW_USER_IDS}，逗号分隔的 users.id，生产 = TailUs）。不把 TailUs 改成 ADMIN：
 * 官方号在审核、Feed、通知推送等处都有特判，改角色牵连面太大。
 *
 * <p>未配置 = 不自动关注任何人。stag 与生产各自配（两边 id 不同）。
 */
@Component
public class AutoFollowTargets {

    private final List<Long> ids;

    public AutoFollowTargets(@Value("${petgo.social.auto-follow-user-ids:}") String raw) {
        this.ids = parse(raw);
    }

    static List<Long> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong) // 配错直接启动失败（fail-fast），不静默忽略
                .distinct()
                .toList();
    }

    /** 配置的目标账号 id（保持配置顺序，已去重）。 */
    public List<Long> ids() {
        return ids;
    }

    /** 是否自动关注目标（取关时据此记 optout，之后不再被自动关注回来）。 */
    public boolean contains(long userId) {
        return Set.copyOf(ids).contains(userId);
    }
}
