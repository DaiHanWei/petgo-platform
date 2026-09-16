package com.tailtopia.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 配置变更日志（Story 9.2，append-only）。每字段变更（old→new）一条；审计哈希链另经 AdminAuditService。
 */
@Entity
@Table(name = "config_change_logs")
public class ConfigChangeLog {

    /** 配置类别。 */
    public enum ConfigType {
        PRICING, PAWCOIN, TOPUP_TIER,
        /** 首页推荐算法打分参数（V1.1.6 Story 16.4）。 */
        FEED_RANK,
        /** 客服联系方式（V1.3.0 Story 3-1）。 */
        SUPPORT_CONTACT
    }

    // 🔴 加枚举值**不够**：列上有 CHECK 白名单，不同步放开会在写日志时撞约束，
    //    表现是「保存配置报 500」而错误栈指向 config_change_logs、不指向配置模块。
    //    放开必须 DROP + ADD **重列全集**，值集取自当前树里最后一条重建该约束的迁移。
    //    此处已出过三次事故（每次都是照着一份过期列表抄，丢掉一批值）。

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_type", nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private ConfigType configType;

    @Column(name = "field", nullable = false)
    private String field;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(name = "changed_by", nullable = false)
    private long changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected ConfigChangeLog() {
    }

    public static ConfigChangeLog of(ConfigType type, String field, String oldValue, String newValue,
            long changedBy) {
        ConfigChangeLog c = new ConfigChangeLog();
        c.configType = type;
        c.field = field;
        c.oldValue = oldValue;
        c.newValue = newValue;
        c.changedBy = changedBy;
        c.changedAt = Instant.now();
        return c;
    }

    public Long getId() {
        return id;
    }

    public ConfigType getConfigType() {
        return configType;
    }

    public String getField() {
        return field;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public long getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
