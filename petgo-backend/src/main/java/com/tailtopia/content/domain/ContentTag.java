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
     * 胶囊底色（2026-08-28，UI 稿 `.deco-badge` 的 135° 双色渐变）。
     *
     * <p>⚠️ 与 {@link #icon} 是两件事：icon 是胶囊里那枚 9px 小图，本列是它底下那道渐变。
     * 胶囊上的字是白色粗体 9.5px，所以只提供"白字读得出"的固定几档
     * （见 {@link ContentTagBadgeStyle}）。
     */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "badge_style", nullable = false, length = 24)
    private ContentTagBadgeStyle badgeStyle = ContentTagBadgeStyle.SUNSET;

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
        return of(code, name, icon, description, ContentTagBadgeStyle.SUNSET);
    }

    public static ContentTag of(String code, String name, String icon, String description,
            ContentTagBadgeStyle badgeStyle) {
        ContentTag t = new ContentTag();
        t.badgeStyle = badgeStyle == null ? ContentTagBadgeStyle.SUNSET : badgeStyle;
        t.code = code;
        t.name = name;
        t.icon = icon;
        t.description = description;
        return t;
    }

    /**
     * 建号（2026-09-02：标签码改为系统自动生成，运营不再手填）。
     *
     * <p>⚠️ <b>只允许在创建事务内调用一次</b>：先以占位码 INSERT 拿到自增 id，
     * 再回填 {@code ct-<id>}。「建后不可修改」的对外约定不变 —— 后台没有任何
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

    /** 编辑展示信息（Story 11.2）。code 不可改 —— 它是对外稳定标识。 */
    public void edit(String name, String icon, String description) {
        edit(name, icon, description, this.badgeStyle);
    }

    /** 编辑展示信息（含胶囊底色）。code 不可改 —— 它是对外稳定标识。 */
    public void edit(String name, String icon, String description,
            ContentTagBadgeStyle badgeStyle) {
        this.name = name;
        this.icon = icon;
        this.description = description;
        this.badgeStyle = badgeStyle == null ? ContentTagBadgeStyle.SUNSET : badgeStyle;
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

    public ContentTagBadgeStyle getBadgeStyle() {
        return badgeStyle;
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
