package com.tailtopia.admin.entitlement.service;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.entitlement.service.GrantCommand;
import com.tailtopia.entitlement.service.GrantResult;
import com.tailtopia.shared.error.AppException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台运营补发免费次数（V1.5.0 gift-pack Story 5-2 · AC2 · D-30 / D-31）：写入只经 {@link EntitlementCreditService#grant}
 * （来源 ADMIN、幂等键 {@code ADMIN:{grantToken}}），本类在<b>同一事务</b>里补写审计 {@code ENTITLEMENT_ADMIN_GRANT}。
 *
 * <p>校验：次数 1–3、原因 trim 后非空且 ≤ 200、{@code grantToken} 形状同名额补发（≤ 64 位 {@code [A-Za-z0-9-]}）、
 * 类型不得为「任意款」（D-31）、用户不存在 404 / 已注销 422。同一 token 重放 → 不重复发放、不重复审计。
 * 审计摘要含运营填写的原因（PRD 要求；界面已提示勿填用户隐私），日志不记原因。
 */
@Service
public class AdminEntitlementGrantService {

    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 3;
    public static final int MAX_REASON = 200;
    static final Pattern GRANT_TOKEN = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final EntitlementCreditService credits;
    private final AccountQueryService accounts;
    private final AccountDeletionRepository deletions;
    private final AdminAuditService audit;

    public AdminEntitlementGrantService(EntitlementCreditService credits, AccountQueryService accounts,
            AccountDeletionRepository deletions, AdminAuditService audit) {
        this.credits = credits;
        this.accounts = accounts;
        this.deletions = deletions;
        this.audit = audit;
    }

    /** 补发结果（{@code replayed} = 同 token 重放，未重复发放 / 审计）。 */
    public record Result(CreditType type, int quantity, boolean replayed) {
    }

    @Transactional
    public Result grant(long userId, CreditType type, int quantity, String reason, String grantToken, long adminId) {
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw AppException.validation("补发次数须为 1–3").code("admin.err.entitlement.quantityInvalid");
        }
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) {
            throw AppException.validation("请填写补发原因").code("admin.err.entitlement.reasonRequired");
        }
        if (r.length() > MAX_REASON) {
            throw AppException.validation("补发原因不能超过 200 字").code("admin.err.entitlement.reasonTooLong");
        }
        String token = grantToken == null ? "" : grantToken.trim();
        if (!GRANT_TOKEN.matcher(token).matches()) {
            throw AppException.validation("缺少幂等标识，请刷新页面后重试").code("admin.err.entitlement.missingToken");
        }
        if (type == null || type == CreditType.KTP_HD_ANY) {
            throw AppException.validation("不能补发该类型").code("admin.err.entitlement.typeInvalid");
        }
        User target = accounts.findUserById(userId)
                .orElseThrow(() -> AppException.notFound("用户不存在").code("admin.err.user.notFound"));
        if (target.getDeletedAt() != null || deletions.findByUserId(userId).isPresent()) {
            throw AppException.validation("该账号已注销，不可补发").code("admin.err.entitlement.userDeleted");
        }
        GrantResult g = credits.grant(new GrantCommand(userId, type, quantity, CreditSource.ADMIN, token,
                "ADMIN:" + token, null, null, adminId, r));
        if (!g.replayed()) {
            audit.record(adminId, AuditActions.ENTITLEMENT_ADMIN_GRANT, "USER", String.valueOf(userId),
                    type.wire() + " × " + quantity + " · " + r);
        }
        return new Result(type, quantity, g.replayed());
    }
}
