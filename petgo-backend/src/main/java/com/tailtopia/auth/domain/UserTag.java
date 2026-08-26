package com.tailtopia.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 用户标签配置（V1.1.6 Story 5.1 · FR-74）。
 *
 * <p>🛡 与内容装饰标签的配置表**完全独立、不共用**（AD-10）—— 一张表加类型字段会让分配表的外键
 * 同时指向两张实体表，PostgreSQL 建不出约束，完整性只能靠代码自律。
 */
@Entity
@Table(name = "user_tags")
public class UserTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 运营侧的稳定标识（改名不影响引用）。 */
    @Column(name = "code", nullable = false, length = 48)
    private String code;

    @Column(name = "name", nullable = false, length = 48)
    private String name;

    /** 图标（emoji 或图片地址，由运营配）。 */
    @Column(name = "icon", nullable = false, length = 255)
    private String icon;

    /** 点标签后 tooltip 里显示的那句说明。 */
    @Column(name = "description", nullable = false, length = 140)
    private String description;

    /** 下线时刻；NULL = 在线（Story 11.3，与装饰标签同形状）。 */
    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserTag() {
    }

    public static UserTag of(String code, String name, String icon, String description) {
        UserTag t = new UserTag();
        t.code = code;
        t.name = name;
        t.icon = icon;
        t.description = description;
        return t;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** 编辑展示信息。code 不可改 —— 它是对外稳定标识。 */
    public void edit(String name, String icon, String description) {
        this.name = name;
        this.icon = icon;
        this.description = description;
    }

    /** 下线（幂等）。 */
    public void retire(Instant at) {
        if (this.retiredAt == null) {
            this.retiredAt = at;
        }
    }

    /** 重新上线（幂等）。 */
    public void restore() {
        this.retiredAt = null;
    }

    public boolean isRetired() {
        return retiredAt != null;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getIcon() {
        return icon;
    }

    public String getDescription() {
        return description;
    }
}
