package com.tailtopia.admin.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.dto.VetListFilter;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.consult.dto.VetRatingsView;
import com.tailtopia.consult.service.ConsultInterruptService;
import com.tailtopia.consult.service.ConsultRatingQueryService;
import com.tailtopia.shared.im.ImAccountMapper;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.domain.VetPresenceStatus;
import com.tailtopia.vet.domain.VetStatus;
import com.tailtopia.vet.service.VetAccountService;
import com.tailtopia.vet.service.VetPresenceService;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin slice：兽医账号 CRUD（Story 5.1，G-1 跨切面 slice）。
 *
 * <p>复用 {@link VetAccountService}（不自建 vet repository，遵守「禁跨模块直访对方 repository」）。
 * 本服务是 5.6 评分查看、5.7 封禁的同一 Admin 落点。明文密码绝不外泄到日志/视图。
 */
@Service
public class AdminVetService {

    private static final Logger log = LoggerFactory.getLogger(AdminVetService.class);

    private final VetAccountService vetAccounts;
    private final ConsultRatingQueryService ratingQuery;
    private final VetPresenceService presence;
    private final ConsultInterruptService interruptService;
    private final TencentImClient imClient;
    private final VetQualificationService vetQualifications;
    private final AdminAuditService auditService;
    private final com.tailtopia.consult.service.ConsultQualityQueryService qualityQuery;
    private final com.tailtopia.shared.media.AliyunOssClient ossClient;
    private final com.tailtopia.shared.media.MediaProperties mediaProps;

    public AdminVetService(VetAccountService vetAccounts, ConsultRatingQueryService ratingQuery,
            VetPresenceService presence, ConsultInterruptService interruptService,
            TencentImClient imClient, VetQualificationService vetQualifications,
            AdminAuditService auditService,
            com.tailtopia.consult.service.ConsultQualityQueryService qualityQuery,
            com.tailtopia.shared.media.AliyunOssClient ossClient,
            com.tailtopia.shared.media.MediaProperties mediaProps) {
        this.vetAccounts = vetAccounts;
        this.ratingQuery = ratingQuery;
        this.presence = presence;
        this.interruptService = interruptService;
        this.imClient = imClient;
        this.vetQualifications = vetQualifications;
        this.auditService = auditService;
        this.qualityQuery = qualityQuery;
        this.ossClient = ossClient;
        this.mediaProps = mediaProps;
    }

    /** 全量列表（简单视图，无附加列）——保留向后兼容。 */
    public List<VetAdminView> list() {
        return vetAccounts.listAll().stream().map(VetAdminView::from).toList();
    }

    /**
     * 列表 + 多维筛选/搜索（Story 2.2）。组装资质（2.1）/ 在线（Redis presence）/ 均分（consult 评分）后内存过滤
     * （V1 兽医量小可接受）。跨模块只经既有 service（禁跨 repo）。绝不含 passwordHash。
     */
    @Transactional(readOnly = true)
    public List<VetAdminView> list(VetListFilter rawFilter) {
        VetListFilter f = (rawFilter == null ? VetListFilter.none() : rawFilter).normalized();
        String qLower = f.q() == null ? null : f.q().toLowerCase(Locale.ROOT);
        return vetAccounts.listAll().stream()
                .map(this::assemble)
                .filter(v -> f.accountStatus() == null || f.accountStatus().equals(v.status()))
                .filter(v -> f.qualStatus() == null || f.qualStatus().equals(v.qualStatus()))
                .filter(v -> matchesOnline(f.online(), v.presence()))
                .filter(v -> qLower == null
                        || (v.displayName() != null && v.displayName().toLowerCase(Locale.ROOT).contains(qLower))
                        || (v.username() != null && v.username().toLowerCase(Locale.ROOT).contains(qLower)))
                .toList();
    }

    private VetAdminView assemble(VetAccount v) {
        String qual = vetQualifications.getStatus(v.getId()).name();
        String pres = presence.statusOf(v.getId()).name();
        VetRatingsView ratings = ratingQuery.forVet(v.getId());
        Double avg = ratings.count() == 0 ? null : ratings.average();
        return VetAdminView.of(v, qual, pres, avg);
    }

    /** online 维度：ONLINE 含 BUSY（均视为「在线」展示）；OFFLINE 仅离线；null/其他=不过滤。 */
    private boolean matchesOnline(String online, String presence) {
        if (online == null) {
            return true;
        }
        boolean isOnline = VetPresenceStatus.ONLINE.name().equals(presence)
                || VetPresenceStatus.BUSY.name().equals(presence);
        return VetPresenceStatus.ONLINE.name().equals(online) ? isOnline : !isOnline;
    }

    /**
     * 创建兽医账号（Story 2.3）：落库（含联系手机号）→ 置待完善资质（2.1，不可接单）→ 同事务写审计 VET_CREATED
     * → 幂等导入 IM 账号。返回新建账号 id。**密码/手机号明文绝不进审计/日志/回显**（密码运营自填本就知晓）。
     *
     * @param actorAccountId 操作的后台账号 id（审计操作人）
     */
    @Transactional
    public long create(String displayName, String username, String rawPassword, String contactPhone,
            long actorAccountId) {
        VetAccount created = vetAccounts.create(displayName, username, rawPassword, contactPhone);
        // 建号后置待完善资质（Story 2.1）：可登录、暂不可接单。
        vetQualifications.ensureForVet(created.getId());
        // 强制审计（同事务）：summary 仅显示名 + 登录邮箱，绝不含密码/手机号。
        auditService.record(actorAccountId, AuditActions.VET_CREATED, "VET_ACCOUNT",
                String.valueOf(created.getId()), "创建兽医账号 " + displayName + "（" + username + "）");
        // Story 5.5 增量：建号即幂等 REST 导入 IM 账号 v_<vetId>（不计 MAU）。
        // 导入失败不阻断建号（Live 客户端内部已吞 REST 异常；此处兜底任何意外，仅记非敏感日志）。
        try {
            imClient.ensureAccount(ImAccountMapper.vetImId(created.getId()), displayName);
        } catch (RuntimeException e) {
            log.warn("兽医 IM 建号失败（不阻断开户）: {}", e.getClass().getSimpleName());
        }
        return created.getId();
    }

    /**
     * 编辑兽医资料（Story 2.4）：改显示名/登录邮箱/手机号，**不中断会话**；同事务写审计 VET_UPDATED
     * （summary 不含手机号明文）。
     */
    @Transactional
    public void updateProfile(long vetId, String displayName, String email, String contactPhone,
            long actorAccountId) {
        vetAccounts.updateProfile(vetId, displayName, email, contactPhone);
        auditService.record(actorAccountId, AuditActions.VET_UPDATED, "VET_ACCOUNT",
                String.valueOf(vetId), "编辑兽医资料 " + displayName + "（" + email + "）");
    }

    /**
     * 更换兽医头像：字节上传公开桶① → 回填 CDN URL → 同事务写审计 VET_UPDATED。
     * 运营为可信主体，头像不走内容审核（与用户头像 D-CM3 异步送审区别对待）。
     */
    @Transactional
    public void updateAvatar(long vetId, byte[] bytes, String contentType, long actorAccountId) {
        String ext = switch (contentType == null ? "" : contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
        String key = mediaProps.getOss().normalizedKeyPrefix()
                + "public/vet-avatar/" + vetId + "/" + java.util.UUID.randomUUID() + "." + ext;
        String url = ossClient.putPublicObject(key, bytes, contentType);
        vetAccounts.updateAvatar(vetId, url);
        auditService.record(actorAccountId, AuditActions.VET_UPDATED, "VET_ACCOUNT",
                String.valueOf(vetId), "更换兽医头像");
    }

    /**
     * 重置兽医密码（Story 2.4）：重算 BCrypt（旧凭证立失效）+ 同事务写审计 VET_PASSWORD_RESET
     * （summary **绝不含任何密码字符**）。新密码由运营在表单输入、本就知晓，不回显/不入审计/不落日志。
     */
    @Transactional
    public void resetPassword(long vetId, String newRawPassword, long actorAccountId) {
        vetAccounts.resetPassword(vetId, newRawPassword);
        auditService.record(actorAccountId, AuditActions.VET_PASSWORD_RESET, "VET_ACCOUNT",
                String.valueOf(vetId), "重置兽医登录密码");
    }

    /**
     * 切换封禁/解封（Story 5.1 建 + 5.7 补副作用）。
     *
     * <p>封禁（banned=true）：status=BANNED + 清在线态（踢下线）+ 批量中断进行中会话（同事务原子）。
     * 解封（banned=false）：仅 status=ACTIVE，<b>不恢复已中断会话</b>（中断不可恢复，用户须重新发起）。
     */
    @Transactional
    public void setBanned(long vetId, boolean banned, long actorAccountId) {
        vetAccounts.setStatus(vetId, banned ? VetStatus.BANNED : VetStatus.ACTIVE);
        if (banned) {
            presence.goOffline(vetId);              // 清在线态 + 移出待接单匹配
            interruptService.interruptByVetBan(vetId); // 进行中/待关闭会话迁 INTERRUPTED + 发异常事件（2.5）
        }
        // 强制审计（同事务，含操作人 + created_at 时间戳，Story 2.5 AC5）。
        auditService.record(actorAccountId, banned ? AuditActions.VET_BANNED : AuditActions.VET_UNBANNED,
                "VET_ACCOUNT", String.valueOf(vetId), banned ? "封禁兽医账号" : "解封兽医账号");
    }

    /** 资质到期统计（Story 2.8 预警徽标）：即将到期 / 已过期数量。 */
    @Transactional(readOnly = true)
    public VetQualificationService.ExpiryStats qualificationExpiryStats() {
        return vetQualifications.expiryStats();
    }

    /** 兽医历史评分 + 平均分（Story 5.6，AC4，仅运营可见）。经 consult service 聚合，不跨 repository。 */
    public VetRatingsView ratings(long vetId) {
        return ratingQuery.forVet(vetId);
    }

    /** 兽医未评问诊列表（Story 6.2，AB-6B，仅运营可见）。经 consult service，不跨 repository。 */
    public java.util.List<com.tailtopia.consult.dto.VetUnratedConsult> unratedConsults(long vetId) {
        return qualityQuery.unratedConsults(vetId);
    }

    public VetAdminView view(long vetId) {
        return VetAdminView.from(vetAccounts.getById(vetId));
    }

    /**
     * 兽医在线态只读快照（Story 2.6，AB-2F）。逐行读既有 Redis presence（{@code statusOf}），**绝不写**；
     * {@code queriedAt} 为查询时刻（快照非实时）。与 2.2 列表在线列同口径（statusOf）。
     */
    @Transactional(readOnly = true)
    public com.tailtopia.admin.dto.VetOnlineSnapshot onlineSnapshot(java.time.Instant queriedAt) {
        // 最后在线时间按运营时区（WIB）格式化；离线兽医无 lastSeen（ZSET 已移除）→ 「—」（Bug 168）。
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.of("Asia/Jakarta"));
        java.util.List<com.tailtopia.admin.dto.VetOnlineSnapshot.Row> rows = vetAccounts.listAll().stream()
                .map(v -> new com.tailtopia.admin.dto.VetOnlineSnapshot.Row(
                        v.getId(), v.getDisplayName(), presence.statusOf(v.getId()).name(),
                        presence.lastSeenAt(v.getId()).map(fmt::format).orElse("—")))
                .toList();
        return new com.tailtopia.admin.dto.VetOnlineSnapshot(rows, queriedAt);
    }

    /** 编辑表单预填（Story 2.4）：当前显示名/登录邮箱/手机号。 */
    @Transactional(readOnly = true)
    public com.tailtopia.admin.dto.EditVetForm editForm(long vetId) {
        VetAccount v = vetAccounts.getById(vetId);
        com.tailtopia.admin.dto.EditVetForm f = new com.tailtopia.admin.dto.EditVetForm();
        f.setDisplayName(v.getDisplayName());
        f.setUsername(v.getUsername());
        f.setContactPhone(v.getContactPhone());
        return f;
    }
}
