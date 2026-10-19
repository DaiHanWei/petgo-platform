package com.tailtopia.growth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;

/**
 * 成长值流水（V1.4.0 ① 架构 AD-2）。<b>只追加</b>：扣回 = 追加一条 {@code reverses_id} 指向原流水的反向记录，
 * 永不改删原流水（注销清理除外）。
 *
 * <p>实体只读（{@link Immutable}）：写入经 {@code GrowthLedgerService} 的原生 INSERT 完成。
 * {@code pet_ids BIGINT[]} 列<b>有意不映射</b>（读写走原生 SQL）——避免数组类型在 {@code ddl-auto=validate} 下的方言差异。
 */
@Entity
@Immutable
@Table(name = "growth_ledger")
public class GrowthLedgerEntry {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "rule_code", nullable = false, length = 32)
    private String ruleCode;

    @Column(name = "delta", nullable = false)
    private int delta;

    @Column(name = "ref_type", length = 32)
    private String refType;

    @Column(name = "ref_id", length = 64)
    private String refId;

    @Column(name = "idem_key", nullable = false, length = 160)
    private String idemKey;

    @Column(name = "reverses_id")
    private Long reversesId;

    @Column(name = "reason", length = 24)
    private String reason;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "operator_admin_id")
    private Long operatorAdminId;

    @Column(name = "batch_no", length = 32)
    private String batchNo;

    @Column(name = "wib_date", nullable = false)
    private LocalDate wibDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GrowthLedgerEntry() {
    }

    /** 测试 / 内存构造用。 */
    public static GrowthLedgerEntry of(long id, long userId, String ruleCode, int delta, String refType,
            String refId, String idemKey) {
        GrowthLedgerEntry e = new GrowthLedgerEntry();
        e.id = id;
        e.userId = userId;
        e.ruleCode = ruleCode;
        e.delta = delta;
        e.refType = refType;
        e.refId = refId;
        e.idemKey = idemKey;
        e.createdAt = Instant.now();
        e.wibDate = LocalDate.now();
        return e;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public int getDelta() {
        return delta;
    }

    public String getRefType() {
        return refType;
    }

    public String getRefId() {
        return refId;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public Long getReversesId() {
        return reversesId;
    }

    public String getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public Long getOperatorAdminId() {
        return operatorAdminId;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public LocalDate getWibDate() {
        return wibDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
