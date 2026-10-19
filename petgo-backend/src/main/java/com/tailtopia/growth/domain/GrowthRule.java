package com.tailtopia.growth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 成长值规则配置（V1.4.0 ① 架构 AD-4，{@code growth_rules}）。迁移 seed，后台（Epic 4）改；
 * 每次入账读库，修改天然「从下一次入账起生效」。
 */
@Entity
@Table(name = "growth_rules")
public class GrowthRule {

    @Id
    @Column(name = "code", length = 32)
    private String code;

    @Column(name = "points", nullable = false)
    private int points;

    /** 按金额比例的规则：每 {@code unitAmount}（IDR）得 {@link #points}。 */
    @Column(name = "unit_amount")
    private Integer unitAmount;

    @Column(name = "daily_cap")
    private Integer dailyCap;

    @Column(name = "cap_group", length = 16)
    private String capGroup;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected GrowthRule() {
    }

    /** 测试 / 内存构造用。 */
    public static GrowthRule of(RuleCode code, int points, Integer unitAmount, Integer dailyCap, String capGroup,
            boolean enabled) {
        GrowthRule r = new GrowthRule();
        r.code = code.name();
        r.points = points;
        r.unitAmount = unitAmount;
        r.dailyCap = dailyCap;
        r.capGroup = capGroup;
        r.enabled = enabled;
        r.updatedAt = Instant.now();
        return r;
    }

    public String getCode() {
        return code;
    }

    public int getPoints() {
        return points;
    }

    public Integer getUnitAmount() {
        return unitAmount;
    }

    public Integer getDailyCap() {
        return dailyCap;
    }

    public String getCapGroup() {
        return capGroup;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isPassive() {
        return RuleCode.PASSIVE_CAP_GROUP.equals(capGroup);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }
}
