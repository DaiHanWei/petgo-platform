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
        PRICING, PAWCOIN, TOPUP_TIER
    }

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
