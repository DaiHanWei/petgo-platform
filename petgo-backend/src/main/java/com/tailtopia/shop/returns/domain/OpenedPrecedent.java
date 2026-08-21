package com.tailtopia.shop.returns.domain;

import com.tailtopia.shared.error.AppException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 「开封」判定判例（Story 5.6，AB-12D，SPEC-24 / D-4 / OQ-28）。
 *
 * <p>⚠️ <b>判例库是一致性工具，不是风控工具</b>（SPEC-24 原文明确指出）。
 * 它解决的是「同一情形不同客服判得不一样」；<b>骗退风控由 S-4 的 90 日 ≤2 次频次上限承担</b>，
 * 两者不可互相替代 —— 把判例库当风控用，会让 CS 以为「查过判例 = 查过风险」。
 *
 * <p>🔴 <b>首版不引入第三方质检</b>：判定仍由 CS 依凭证图主观进行，本表只是把做过的判断
 * 连同凭证沉淀下来供后续参考。
 */
@Entity
@Table(name = "opened_precedents")
public class OpenedPrecedent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 情形描述（检索的主要依据）。 */
    @Column(name = "situation", nullable = false, length = 300)
    private String situation;

    /** 判定结论：是否算「已开封」。 */
    @Column(name = "judged_opened", nullable = false)
    private boolean judgedOpened;

    /** 判定理由。 */
    @Column(name = "rationale", nullable = false, length = 1000)
    private String rationale;

    /** 凭证图 key，逗号分隔。 */
    @Column(name = "evidence_keys", length = 1000)
    private String evidenceKeys;

    /** 沉淀自哪张退货申请（可空：也允许 CS 直接录入一条参考判例）。 */
    @Column(name = "return_request_id")
    private Long returnRequestId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OpenedPrecedent() {
    }

    public static OpenedPrecedent of(String situation, boolean judgedOpened, String rationale,
            String evidenceKeys, Long returnRequestId, long createdBy) {
        if (situation == null || situation.isBlank()) {
            throw AppException.validation("请填写情形描述（检索靠它）");
        }
        if (rationale == null || rationale.isBlank()) {
            // 🔴 理由必填：一条没有理由的判例对下一个客服毫无参考价值，
            //    只会变成「因为上次这么判」的循环引用。
            throw AppException.validation("请填写判定理由");
        }
        OpenedPrecedent p = new OpenedPrecedent();
        p.situation = situation.trim();
        p.judgedOpened = judgedOpened;
        p.rationale = rationale.trim();
        p.evidenceKeys = evidenceKeys;
        p.returnRequestId = returnRequestId;
        p.createdBy = createdBy;
        p.createdAt = Instant.now();
        return p;
    }

    public Long getId() {
        return id;
    }

    public String getSituation() {
        return situation;
    }

    public boolean isJudgedOpened() {
        return judgedOpened;
    }

    public String getRationale() {
        return rationale;
    }

    public String getEvidenceKeys() {
        return evidenceKeys;
    }

    public Long getReturnRequestId() {
        return returnRequestId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
