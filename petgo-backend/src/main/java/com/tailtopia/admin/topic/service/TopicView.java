package com.tailtopia.admin.topic.service;

import com.tailtopia.topic.domain.TopicStatus;
import com.tailtopia.topic.domain.TopicType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 后台话题行 / 抽屉投影（V1.4.2 Story 6.1）。只在后台用（后台 URL 用数字 id，不对外）。
 *
 * @param postCount   {@code topics.post_count}（热度任务每小时重算，R-5 接受滞后）；抽屉 / 黄条另查实时数
 * @param featuredOrder 首页话题行序号 1–5；{@code null} = 不在行内
 */
public record TopicView(long id, String nameDisplay, String nameNorm, TopicType type, TopicStatus status,
        String descId, String descEn, int postCount, BigDecimal heatScore, Integer featuredOrder,
        Instant disabledAt, Instant createdAt) {

    public boolean official() {
        return type == TopicType.OFFICIAL;
    }

    public boolean active() {
        return status == TopicStatus.ACTIVE;
    }

    public boolean hidden() {
        return status == TopicStatus.HIDDEN;
    }

    public boolean disabled() {
        return status == TopicStatus.DISABLED;
    }

    public boolean featured() {
        return featuredOrder != null;
    }

    /** 热度一位小数（DISABLED 由模板显示「—」）。 */
    public String heatText() {
        return heatScore == null ? "0.0" : heatScore.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
