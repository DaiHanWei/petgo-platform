package com.tailtopia.admin.audit.service;

import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台安全告警（Story 1.3，AC7）。面向「全体在职 SUPER_ADMIN」的高危事件预警——
 * 经既有 logback JSON 日志管道以 <b>WARN 级结构化安全告警</b>下发（运维侧据此监控/转发），
 * <b>不引入 MQ / 新中间件</b>（F5），也不写 App 用户端 {@code notifications}（语义不符且受 type CHECK 约束）。
 *
 * <p>护栏：告警日志只记 <b>事件 + 受众数量 + 操作人账号 id</b>，**绝不**外泄邮箱/密码/令牌/签名 URL/PII
 * （actor 邮箱等明细仅落在不可篡改的审计行 summary 中，按需取证）。
 */
@Service
public class AdminAlertService {

    private static final Logger securityAlert = LoggerFactory.getLogger("ADMIN_SECURITY_ALERT");

    private final AdminAccountRepository adminAccounts;

    public AdminAlertService(AdminAccountRepository adminAccounts) {
        this.adminAccounts = adminAccounts;
    }

    /**
     * 向全体在职超管发安全告警。
     *
     * @param event          事件类型（与审计 action_type 对齐，UPPER_SNAKE）
     * @param actorAccountId 触发事件的后台账号 id（可空）；不记邮箱等 PII
     */
    @Transactional(readOnly = true)
    public void alertSuperAdmins(String event, Long actorAccountId) {
        int audience = adminAccounts
                .findByAccountTypeAndStatus(AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE)
                .size();
        securityAlert.warn("event={} actorAccountId={} superAdminAudience={}",
                event, actorAccountId, audience);
    }
}
