package com.tailtopia.content.domain;

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
 * 内容装饰标签配置（V1.1.6 Story 5.2 · FR-75）。
 *
 * <p>🛡 与用户标签的配置表**完全独立、不共用**（AD-10）—— 两类的校验规则本就不同
 * （"仅公开内容可打标"只对本类成立），挤在同一条代码路径上迟早出事。
 */
@Entity
@Table(name = "content_tags")
public class ContentTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 48)
    private String code;

    @Column(name = "name", nullable = false, length = 48)
    private String name;

    @Column(name = "icon", nullable = false, length = 255)
    private String icon;

    /** 点标签后 tooltip 里显示的那句说明。 */
    @Column(name = "description", nullable = false, length = 140)
    private String description;

    /**
     * 下线时刻；NULL = 在线（V1.1.6 Story 11.2）。
     *
     * <p>🛡 用可空时间戳而不是布尔：「什么时候下线的」也一并留档。
     * 下线后不可再分配，**已分配的照旧生效到各自 ends_at** —— 下线是"不再发新的"。
     */
    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentTag() {
    }

    public static ContentTag of(String code, String name, String icon, String description) {
        ContentTag t = new ContentTag();
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

    /** 编辑展示信息（Story 11.2）。code 不可改 —— 它是对外稳定标识。 */
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
