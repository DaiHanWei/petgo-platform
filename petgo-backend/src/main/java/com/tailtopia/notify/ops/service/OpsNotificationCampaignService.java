package com.tailtopia.notify.ops.service;

import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignDraft;
import com.tailtopia.notify.ops.domain.OpsCampaignReadyEvent;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.ops.OpsTokenGenerator;
import com.tailtopia.ops.target.OpsTargetPolicy;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营通知活动的新建 / 取消（V1.3.4 Story 6.2 · AC2）。<b>只交付服务方法</b>，供 admin 主题（AB-31）的
 * 写接口调用（D-2）；本类<b>不写后台审计</b>（{@code AdminAuditService.record} 由调用方调）。
 *
 * <p>跳转目标唯一校验口径 = Story 5.1 的 {@link OpsTargetPolicy}，本类不自写任何跳转目标规则。
 */
@Service
public class OpsNotificationCampaignService {

    private static final Logger log = LoggerFactory.getLogger(OpsNotificationCampaignService.class);

    /** 与 {@code notifications.title} 列长一致。 */
    static final int TITLE_MAX = 120;
    /** 与 {@code notifications.body} 列长一致。 */
    static final int BODY_MAX = 255;

    public static final String ERR_TEXT_BLANK = "admin.err.ops-notify.text.blank";
    public static final String ERR_TEXT_TOO_LONG = "admin.err.ops-notify.text.too-long";
    public static final String ERR_AUDIENCE_INVALID = AudienceSpec.INVALID_CODE;
    public static final String ERR_IDS_EMPTY = "admin.err.ops-notify.audience.ids-empty";
    public static final String ERR_SCHEDULE_PAST = "admin.err.ops-notify.schedule.past";
    public static final String ERR_CANCEL_NOT_PENDING = "admin.err.ops-notify.cancel.not-pending";

    private final OpsNotificationCampaignRepository campaigns;
    private final OpsCampaignTargetDao targets;
    private final OpsTargetPolicy targetPolicy;
    private final OpsTokenGenerator tokens;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public OpsNotificationCampaignService(OpsNotificationCampaignRepository campaigns, OpsCampaignTargetDao targets,
            OpsTargetPolicy targetPolicy, OpsTokenGenerator tokens, ApplicationEventPublisher events) {
        this(campaigns, targets, targetPolicy, tokens, events, Clock.systemUTC());
    }

    /** 测试用：注入固定时钟。 */
    public OpsNotificationCampaignService(OpsNotificationCampaignRepository campaigns, OpsCampaignTargetDao targets,
            OpsTargetPolicy targetPolicy, OpsTokenGenerator tokens, ApplicationEventPublisher events, Clock clock) {
        this.campaigns = campaigns;
        this.targets = targets;
        this.targetPolicy = targetPolicy;
        this.tokens = tokens;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 新建活动（{@code PENDING}）。立即发送（{@code scheduledAt == null}）在提交后发布
     * {@link OpsCampaignReadyEvent}；定时的由扫描到点发送。
     *
     * @return 活动 {@code public_token}
     */
    @Transactional
    public String create(OpsCampaignDraft draft, long adminAccountId) {
        validate(draft);
        OpsNotificationCampaign saved = campaigns.save(OpsNotificationCampaign.create(tokens.generate(),
                draft.titleId(), draft.titleEn(), draft.bodyId(), draft.bodyEn(),
                draft.targetType(), draft.targetValue(), draft.audience().toJson(),
                draft.scheduledAt(), adminAccountId));
        if (draft.audience().mode() == AudienceSpec.Mode.IDS) {
            targets.insertAll(saved.getId(), draft.targetUserIds()); // 去重由 DAO 负责
        }
        if (draft.scheduledAt() == null) {
            events.publishEvent(new OpsCampaignReadyEvent(saved.getId()));
        }
        log.info("运营通知活动已创建 campaignId={} scheduled={}", saved.getId(), draft.scheduledAt() != null);
        return saved.getPublicToken();
    }

    /** 取消：仅 {@code PENDING} 可取消；已在发送 / 已结束 → 409 {@code cancel.not-pending}。 */
    @Transactional
    public void cancel(String publicToken, long adminAccountId) {
        int n = campaigns.cancelIfPending(publicToken, clock.instant());
        if (n > 0) {
            return;
        }
        if (publicToken == null || campaigns.findByPublicToken(publicToken).isEmpty()) {
            throw AppException.notFound("运营通知活动不存在");
        }
        throw AppException.conflict("活动已在发送或已结束，不能取消").code(ERR_CANCEL_NOT_PENDING);
    }

    private void validate(OpsCampaignDraft d) {
        if (blank(d.titleId()) || blank(d.titleEn()) || blank(d.bodyId()) || blank(d.bodyEn())) {
            throw AppException.validation("标题与正文（ID / EN）均不能为空").code(ERR_TEXT_BLANK);
        }
        if (d.titleId().length() > TITLE_MAX || d.titleEn().length() > TITLE_MAX
                || d.bodyId().length() > BODY_MAX || d.bodyEn().length() > BODY_MAX) {
            throw AppException.validation("标题不超过 " + TITLE_MAX + " 字符、正文不超过 " + BODY_MAX + " 字符")
                    .code(ERR_TEXT_TOO_LONG);
        }
        if (d.audience() == null) {
            throw AppException.validation("人群规格不合法：为空").code(ERR_AUDIENCE_INVALID);
        }
        if (d.audience().mode() == AudienceSpec.Mode.IDS
                && (d.targetUserIds() == null || d.targetUserIds().stream().allMatch(id -> id == null))) {
            throw AppException.validation("指定用户模式下用户列表不能为空").code(ERR_IDS_EMPTY);
        }
        Instant scheduledAt = d.scheduledAt();
        if (scheduledAt != null && scheduledAt.isBefore(clock.instant())) {
            throw AppException.validation("定时发送时间不能早于当前时刻").code(ERR_SCHEDULE_PAST);
        }
        // 唯一口径：Story 5.1 OpsTargetPolicy（只返回布尔、未定义错误码 → 本 story 不挂码）。
        if (d.targetType() == null || !targetPolicy.isAvailable(d.targetType(), d.targetValue())) {
            throw AppException.validation("跳转目标不可用");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
