package com.tailtopia.entitlement.service;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.entitlement.domain.CreditFamily;
import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.repository.EntitlementCreditDao;
import com.tailtopia.entitlement.repository.EntitlementCreditDao.CandidateRow;
import com.tailtopia.entitlement.repository.EntitlementCreditDao.GrantedRow;
import com.tailtopia.entitlement.service.EntitlementGrantRules.Candidate;
import com.tailtopia.entitlement.service.EntitlementGrantRules.Take;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权益次数的<b>唯一</b>发放 / 扣减入口（V1.5.0 gift-pack 架构 §5 · D-15 / D-25 / D-31）。
 *
 * <p>{@link #grant} 是会话 ⑥ 运营活动的契约方法，签名、事务传播、幂等语义<b>冻结</b>；后续 story 只能加方法。
 * 不发通知、不发埋点、不写审计（归调用方）。日志只记 userId / type / quantity / source。
 */
@Service
public class EntitlementCreditService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementCreditService.class);

    private final EntitlementCreditDao dao;
    private final AccountQueryService accounts;
    private final AccountDeletionRepository deletions;
    private final Clock clock;

    @Autowired
    public EntitlementCreditService(EntitlementCreditDao dao, AccountQueryService accounts,
            AccountDeletionRepository deletions) {
        this(dao, accounts, deletions, Clock.systemUTC());
    }

    EntitlementCreditService(EntitlementCreditDao dao, AccountQueryService accounts,
            AccountDeletionRepository deletions, Clock clock) {
        this.dao = dao;
        this.accounts = accounts;
        this.deletions = deletions;
        this.clock = clock;
    }

    /**
     * 发放一批次数。
     * <ul>
     *   <li>事务：REQUIRED（并入调用方事务）。</li>
     *   <li>有效期：到账起 3 个月（WIB plusMonths(3) 的同一时刻）。</li>
     *   <li>幂等：(idemKey, type) 唯一；同键同类型重放 → 返回原批（replayed = true），不比对数量（不同记 WARN）。
     *       同一 idemKey 可以发多种类型。</li>
     *   <li>校验失败抛 422：entitlement-quantity-invalid / validation / entitlement-type-not-allowed /
     *       entitlement-user-inactive。</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public GrantResult grant(GrantCommand cmd) {
        EntitlementGrantRules.validate(cmd);
        requireActiveUser(cmd.userId());
        Instant grantedAt = clock.instant();
        Instant expiresAt = EntitlementGrantRules.expiresAt(grantedAt);
        Optional<GrantedRow> inserted = dao.insertIfAbsent(cmd.userId(), cmd.type(), cmd.quantity(), grantedAt,
                expiresAt, cmd.source(), cmd.sourceRef(), cmd.idemKey(), blankToNull(cmd.labelId()),
                blankToNull(cmd.labelEn()), cmd.operatorAdminId(),
                cmd.reason() == null ? null : cmd.reason().trim());
        if (inserted.isPresent()) {
            log.info("entitlement granted userId={} type={} quantity={} source={}", cmd.userId(), cmd.type(),
                    cmd.quantity(), cmd.source());
            return new GrantResult(inserted.get().id(), inserted.get().expiresAt(), false);
        }
        GrantedRow existing = dao.findByIdem(cmd.idemKey(), cmd.type())
                .orElseThrow(() -> new IllegalStateException("entitlement idem conflict without row"));
        if (existing.userId() != cmd.userId()) {
            // 契约按 (idemKey, type) 幂等、不分用户：调用方键里必须带用户维度；撞键记 WARN 交人工（code review #3）。
            log.warn("entitlement grant idem key reused across users creditId={} requestUserId={}", existing.id(),
                    cmd.userId());
        }
        if (existing.quantity() != cmd.quantity()) {
            log.warn("entitlement grant replay quantity mismatch creditId={} original={} requested={}",
                    existing.id(), existing.quantity(), cmd.quantity());
        }
        return new GrantResult(existing.id(), existing.expiresAt(), true);
    }

    /**
     * 扣次数（D-25，MANDATORY：必须在调用方的解锁 / 送礼事务里）。按目标幂等（{@code {kind}:{targetId}}）；
     * 可用不足 → 409 entitlement-insufficient（调用方事务随之回滚）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumeResult consume(ConsumeCommand cmd) {
        if (cmd.quantity() < 1) {
            throw new IllegalArgumentException("consume quantity must be >= 1");
        }
        if (cmd.kind() == null || cmd.type() == null || cmd.kind().family() != cmd.type().family()) {
            throw new IllegalArgumentException("consume kind/type mismatch: " + cmd.kind() + "/" + cmd.type());
        }
        if (cmd.type() == CreditType.KTP_HD_ANY) {
            throw new IllegalArgumentException("consume must name a concrete KTP template, not ANY");
        }
        dao.lockUser(cmd.userId());
        String useKey = cmd.kind().name() + ":" + cmd.targetId();
        if (dao.useKeyExists(useKey)) {
            return new ConsumeResult(true, 0, List.of(), null);
        }
        Instant now = clock.instant();
        List<CreditType> types = cmd.type().family() == CreditFamily.KTP_HD
                ? List.of(cmd.type(), CreditType.KTP_HD_ANY) : List.of(cmd.type());
        List<CandidateRow> candidates = dao.lockCandidates(cmd.userId(), types, cmd.type(), now);
        List<Take> takes = EntitlementGrantRules.plan(
                candidates.stream().map(c -> new Candidate(c.id(), c.remaining())).toList(), cmd.quantity());
        if (takes.isEmpty()) {
            throw AppException.entitlementInsufficient("可用免费次数不足");
        }
        Map<Long, CandidateRow> byId = candidates.stream()
                .collect(Collectors.toMap(CandidateRow::id, Function.identity()));
        String petName = EntitlementGrantRules.petNameSnapshot(cmd.petName());
        List<Long> creditIds = new ArrayList<>();
        for (Take t : takes) {
            if (dao.incrementUsed(t.creditId(), t.quantity()) != 1) {
                throw new IllegalStateException("entitlement credit over-consumed id=" + t.creditId());
            }
            dao.insertUse(cmd.userId(), t.creditId(), byId.get(t.creditId()).type(), cmd.kind().name(),
                    cmd.targetId(), useKey, t.quantity(), petName, now);
            creditIds.add(t.creditId());
        }
        CreditSource firstSource = byId.get(takes.get(0).creditId()).source();
        log.info("entitlement consumed userId={} type={} quantity={} source={}", cmd.userId(), cmd.type(),
                cmd.quantity(), firstSource);
        return new ConsumeResult(false, cmd.quantity(), List.copyOf(creditIds), firstSource);
    }

    /** 汇总（「Koin &amp; Jatah Saya」、商城胶囊、「我的」页）：只统计未用完且未过期的批。 */
    @Transactional(readOnly = true)
    public CreditSummary summary(long userId) {
        List<TypeSummary> rows = dao.availableByType(userId, clock.instant()).stream()
                .map(r -> new TypeSummary(r.type(), r.remaining(), r.earliestExpiry()))
                .toList();
        return EntitlementGrantRules.summarize(rows);
    }

    /**
     * V1.5.0 gift-pack Story 4-3：某类型当前可用剩余（解锁 / 送礼响应的 {@code remaining}）。KTP 某款 = 该款 + 「任意款」合计
     * （与扣减口径 D-25 一致）；其余 = 该类型。只读，按调用方事务读（扣减后同事务读到的是扣后值）。
     */
    @Transactional(readOnly = true)
    public int remainingFor(long userId, CreditType type) {
        List<TypeSummary> rows = summary(userId).families().stream().flatMap(f -> f.types().stream()).toList();
        int n = 0;
        for (TypeSummary r : rows) {
            if (r.type() == type || (type.family() == CreditFamily.KTP_HD && r.type() == CreditType.KTP_HD_ANY
                    && type != CreditType.KTP_HD_ANY)) {
                n += r.remaining();
            }
        }
        return n;
    }

    /** 不存在 / 已注销 / 注销受理中 / 虚拟号 → 422 entitlement-user-inactive。 */
    private void requireActiveUser(long userId) {
        User u = accounts.findUserById(userId).orElse(null);
        if (u == null || u.getDeletedAt() != null || u.getAccountType() == AccountType.VIRTUAL
                || deletions.findByUserId(userId).isPresent()) {
            throw AppException.entitlementUserInactive("该账号无法接收免费次数");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
