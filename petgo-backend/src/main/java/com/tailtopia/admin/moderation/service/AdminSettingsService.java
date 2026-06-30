package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.domain.AdminSettings;
import com.tailtopia.admin.moderation.repository.AdminSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单行系统配置读写（Story 4.3，AB-3C）。人工审核开关——读缺省 false（防种子缺失），
 * 切换仅超管（控制器门控）+ 写审计 {@code SETTING_CHANGED}。
 */
@Service
public class AdminSettingsService {

    private final AdminSettingsRepository settings;
    private final AdminAuditService auditService;

    public AdminSettingsService(AdminSettingsRepository settings, AdminAuditService auditService) {
        this.settings = settings;
        this.auditService = auditService;
    }

    /** 人工审核是否激活；单行缺失时缺省 false（维持现网行为）。 */
    @Transactional(readOnly = true)
    public boolean isManualReviewEnabled() {
        return settings.findById(AdminSettings.SINGLETON_ID)
                .map(AdminSettings::isManualReviewEnabled)
                .orElse(false);
    }

    /** 切换人工审核开关 + 写审计。单行缺失则不写（迁移种子保证存在）。 */
    @Transactional
    public void setManualReviewEnabled(boolean enabled, long actorAccountId) {
        AdminSettings s = settings.findById(AdminSettings.SINGLETON_ID).orElseThrow();
        s.setManualReviewEnabled(enabled);
        settings.save(s);
        auditService.record(actorAccountId, AuditActions.SETTING_CHANGED, "ADMIN_SETTING",
                "manual_review_enabled", "人工审核开关 → " + (enabled ? "开启" : "关闭"));
    }
}
