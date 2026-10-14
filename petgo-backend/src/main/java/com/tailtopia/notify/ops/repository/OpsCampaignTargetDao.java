package com.tailtopia.notify.ops.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * IDS 模式指定用户子表 {@code ops_notification_campaign_targets} 的读写（V1.3.4 Story 6.1，子表无实体）。
 *
 * <p>{@code user_id} 无外键：后台 CSV 里的无效 id 也要落库，发送时才能「跳过并计数」（{@code skipped_count}）。
 */
@Repository
public class OpsCampaignTargetDao {

    private static final String INSERT =
            "INSERT INTO ops_notification_campaign_targets (campaign_id, user_id) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbc;

    public OpsCampaignTargetDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 去重后批量插入；已存在的 (campaign_id, user_id) 静默跳过。 */
    public void insertAll(long campaignId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>();
        for (Long userId : new LinkedHashSet<>(userIds)) {
            if (userId != null) {
                args.add(new Object[] {campaignId, userId});
            }
        }
        if (!args.isEmpty()) {
            jdbc.batchUpdate(INSERT, args);
        }
    }

    public long countByCampaign(long campaignId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM ops_notification_campaign_targets WHERE campaign_id = ?",
                Long.class, campaignId);
        return n == null ? 0L : n;
    }

    /** 注销级联（D1/D2）：被指定为收件人这件事也是个人数据。 */
    public int deleteByUserId(long userId) {
        return jdbc.update("DELETE FROM ops_notification_campaign_targets WHERE user_id = ?", userId);
    }
}
