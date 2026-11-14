package com.tailtopia.notify.service;

import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.profile.service.ClientAppVersion;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 推送版本门槛（V1.5.0 地基 Story 0.1）：「App 最后上报版本 ≥ 1.5.0」的唯一判定。
 *
 * <p>健康记录提醒（FR-166：旧版不发健康推送）与推送与召回（FR-185：关注更新、D1 / D3 / D7 召回只发新版）
 * <b>共用本类</b>，不各写一套（推送与召回 PRD §6）。版本来自 {@code users.last_app_version}
 * （{@code UserActivityFilter} 按 {@code X-App-Version} 写入）；从没上报过（NULL）、解析不了一律按旧版。
 *
 * <p>粒度是「用户最后一次上报」，不是逐台设备：腾讯推送按用户下发，没有设备维度的版本记录。
 * 同一用户新旧两台设备时，以最后打开的那台为准。
 *
 * <p>签名是两条线的契约：<b>只调用、不改签名、不做替身</b>。
 */
@Service
public class AppVersionGate {

    private final UserRepository users;

    public AppVersionGate(UserRepository users) {
        this.users = users;
    }

    /** 该用户最后上报的版本是否 ≥ 1.5.0；用户不存在 → false。 */
    @Transactional(readOnly = true)
    public boolean reportedV150OrLater(long userId) {
        return !usersOnV150OrLater(List.of(userId)).isEmpty();
    }

    /**
     * 批量：返回入参里最后上报版本 ≥ 1.5.0 的用户 id（子集）。一次查询，禁止调用方逐个调单条版本做 N+1。
     * 入参为空 → 空集。
     */
    @Transactional(readOnly = true)
    public Set<Long> usersOnV150OrLater(Collection<Long> userIds) {
        Set<Long> out = new HashSet<>();
        if (userIds == null || userIds.isEmpty()) {
            return out;
        }
        for (Object[] row : users.findLastAppVersions(userIds)) {
            if (ClientAppVersion.isV150OrLater((String) row[1])) {
                out.add(((Number) row[0]).longValue());
            }
        }
        return out;
    }
}
