package com.tailtopia.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
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

    /**
     * 徽章圆底颜色（2026-08-28，UI 稿 `.utag-icon` 按标签取不同底色）。
     *
     * <p>⚠️ 与 {@link #icon} 是两件事：icon 是圆里那枚**纯白剪影**，本列是它底下那个圆。
     * 正因为图标是纯白的，底色只提供足够深的固定几档（见 {@link UserTagBadgeColor}）。
     */
    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "badge_color", nullable = false, length = 16)
    private UserTagBadgeColor badgeColor = UserTagBadgeColor.GOLD;

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
        return of(code, name, icon, description, UserTagBadgeColor.GOLD);
    }

    public static UserTag of(String code, String name, String icon, String description,
            UserTagBadgeColor badgeColor) {
        UserTag t = new UserTag();
        t.code = code;
        t.name = name;
        t.icon = icon;
        t.description = description;
        t.badgeColor = badgeColor == null ? UserTagBadgeColor.GOLD : badgeColor;
        return t;
    }

    /**
     * 建号（2026-09-02：标签码改为系统自动生成，运营不再手填）。
     *
     * <p>⚠️ <b>只允许在创建事务内调用一次</b>：先以占位码 INSERT 拿到自增 id，
     * 再回填 {@code ut-<id>}。「建后不可修改」的对外约定不变 —— 后台没有任何
     * 编辑入口触达本方法。
     */
    public void assignGeneratedCode(String code) {
        this.code = code;
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
        edit(name, icon, description, this.badgeColor);
    }

    /** 编辑展示信息（含徽章底色）。code 不可改 —— 它是对外稳定标识。 */
    public void edit(String name, String icon, String description, UserTagBadgeColor badgeColor) {
        this.name = name;
        this.icon = icon;
        this.description = description;
        this.badgeColor = badgeColor == null ? UserTagBadgeColor.GOLD : badgeColor;
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

    public UserTagBadgeColor getBadgeColor() {
        return badgeColor;
    }

    public String getIcon() {
        return icon;
    }

    public String getDescription() {
        return description;
    }
}
