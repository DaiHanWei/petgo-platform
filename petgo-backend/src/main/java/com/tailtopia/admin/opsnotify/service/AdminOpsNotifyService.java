package com.tailtopia.admin.opsnotify.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.ops.AdminOpsTargetValidator;
import com.tailtopia.admin.opsnotify.dto.OpsNotifyForm;
import com.tailtopia.auth.service.OpsAudienceQuery;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignDraft;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.ops.service.OpsNotificationCampaignService;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台「运营通知」发送页（V1.3.4 AB-31 · FR-138 · D-16 / D-20 / D-24）。
 *
 * <p>本类只做<b>表单 → 草稿</b>的翻译、人数预估、跳转目标的具体错误码与审计；真正的落库 / 状态机 / 发送全在
 * 发送核心 {@link OpsNotificationCampaignService}（Story 6.2），这里不碰分批、重试与人群口径。
 *
 * <p>🔴 <b>人数预估与发送同一份 WHERE</b>（{@link OpsAudienceQuery#count}）：两边各写一份就会出现
 * 「确认框说 300 人、实际发了 3 万人」。预估与发送是两次查询，期间人数可能有出入，确认框写「约 N 人」。
 *
 * <p>🔴 <b>跳转目标先过 {@link AdminOpsTargetValidator}</b>：核心的校验只有无码兜底（「跳转目标不可用」），
 * 运营看不出该改哪；这里按类型给码，与运营弹窗（AB-30）同一套文案。
 *
 * <p>记录不可编辑、不可重发、不可删除；只有「待发送」可以取消（核心的条件更新保证并发下只有一方生效）。
 * 每次新建 / 取消都留审计（{@code OPS_NOTIFY_*}，target = {@code public_token}，不外露自增 id）。
 */
@Service
public class AdminOpsNotifyService {

    /** 指定用户 ID 上限（PRD §3.7）：再多请改用「按条件」。 */
    static final int MAX_IDS = 50_000;
    /** 发送记录每页条数。 */
    public static final int PAGE_SIZE = 20;

    public static final String ERR_NOT_FOUND = "admin.err.ops-notify.not-found";
    public static final String ERR_IDS_NOT_NUMERIC = "admin.err.ops-notify.audience.ids-not-numeric";
    public static final String ERR_IDS_TOO_MANY = "admin.err.ops-notify.audience.ids-too-many";
    public static final String ERR_SCHEDULE_FORMAT = "admin.err.ops-notify.schedule.format";
    public static final String ERR_DUPLICATE_SUBMIT = "admin.err.ops-notify.duplicate-submit";
    public static final String ERR_PREVIEW_REQUIRED = "admin.err.ops-notify.preview-required";
    public static final String ERR_AUDIENCE_EMPTY = "admin.err.ops-notify.audience-empty";
    public static final String ERR_PREVIEW_STALE = "admin.err.ops-notify.preview-stale";

    /** 一次性提交键（抽屉渲染时发的 UUID）的 Redis 前缀与有效期：同一个键只能建出一条活动。 */
    static final String SUBMIT_KEY_PREFIX = "admin:ops-notify:submit:";
    static final Duration SUBMIT_KEY_TTL = Duration.ofMinutes(30);
    /** 预估之后人数可以涨多少仍算「同一批人」：max(10 人, 预估数的 10%)，超了要求重新预估。 */
    static final long STALE_MIN_SLACK = 10;

    /** 单个 ID：纯数字，最多 18 位（long 内不溢出）。负号、小数、字母一律算「非数字」。 */
    private static final Pattern ID = Pattern.compile("\\d{1,18}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** 提交键：UUID 形状（字母数字与连字符，≤ 64），防止把任意串写进 Redis 键名。 */
    private static final Pattern SUBMIT_KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final OpsNotificationCampaignService core;
    private final OpsNotificationCampaignRepository campaigns;
    private final OpsAudienceQuery audienceQuery;
    private final AdminOpsTargetValidator targets;
    private final AdminAuditService audit;
    private final AdminAccountRepository adminAccounts;
    private final StringRedisTemplate redis;

    public AdminOpsNotifyService(OpsNotificationCampaignService core, OpsNotificationCampaignRepository campaigns,
            OpsAudienceQuery audienceQuery, AdminOpsTargetValidator targets, AdminAuditService audit,
            AdminAccountRepository adminAccounts, StringRedisTemplate redis) {
        this.core = core;
        this.campaigns = campaigns;
        this.audienceQuery = audienceQuery;
        this.targets = targets;
        this.audit = audit;
        this.adminAccounts = adminAccounts;
        this.redis = redis;
    }

    // ---------- 读 ----------

    /** 发送记录一页（新建的在前）。页码越界由调用方截到 0。 */
    @Transactional(readOnly = true)
    public Page<OpsNotificationCampaign> page(int page) {
        return campaigns.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    /** 按 public_token 取单条；不存在 → 404（URL 里只用 token）。 */
    @Transactional(readOnly = true)
    public OpsNotificationCampaign require(String token) {
        return campaigns.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("运营通知不存在").code(ERR_NOT_FOUND));
    }

    /** 创建人显示名：一页一次批量回查（不 N+1）。账号已删 / 系统创建（null）的不在结果里，模板显示「—」。 */
    @Transactional(readOnly = true)
    public Map<Long, String> creatorNames(Collection<OpsNotificationCampaign> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (OpsNotificationCampaign c : rows) {
            if (c.getCreatedBy() != null) {
                ids.add(c.getCreatedBy());
            }
        }
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (AdminAccount a : adminAccounts.findAllById(ids)) {
            out.put(a.getId(), a.getDisplayName());
        }
        return out;
    }

    /**
     * 人数预估：按<b>当前</b>人群条件实时计数。IDS 模式 = 去重后有效（存在且可接收）的人数，无效 id 不计。
     * 人群条件本身不合法（区间倒置、ID 非数字 / 为空 / 超上限）与新建同样报 422。
     */
    @Transactional(readOnly = true)
    public long preview(OpsNotifyForm f) {
        Audience a = audience(f);
        return audienceQuery.count(a.spec(), a.ids());
    }

    // ---------- 写 ----------

    /**
     * 新建（立即 / 定时）。核心落 {@code PENDING}：立即的在提交后异步发送，定时的由扫描到点发送。
     *
     * <p>🔴 发送不可撤回，服务端两道闸（前端门槛只是体验护栏）：
     * <ul>
     *   <li><b>预估闸</b>：表单须带确认框里的 N（{@code previewCount}）；这里按同一人群重算，0 人拒发，
     *       比 N 多出 max(10, 10%) 以上视为预估已过期、须重新预估（确认框里的数不能和实际差太多）；</li>
     *   <li><b>一次性提交键</b>：抽屉渲染时发的 {@code submitKey}，Redis SET NX 认领，同一键第二次提交 422，
     *       连点 / 重放都只建一条。校验全过才认领；认领后核心再拒（文案 / 定时等）或事务失败则释放，运营改完可再提交。</li>
     * </ul>
     *
     * @return 活动 public_token
     */
    @Transactional
    public String create(OpsNotifyForm f, long actorAccountId) {
        String submitKey = requireSubmitKey(f.getSubmitKey());
        Audience a = audience(f);
        Instant scheduledAt = parseSchedule(f.getScheduledAt());
        OpsTargetType targetType = AdminOpsTargetValidator.parseType(f.getTargetType());
        String targetValue = norm(f.getTargetValue());
        // 只拦能给出具体码的格式 / 可用性错误；核心随后按同一 OpsTargetPolicy 再兜一遍
        targets.check(targetType, targetValue, true);
        checkPreview(f.getPreviewCount(), audienceQuery.count(a.spec(), a.ids()));
        OpsCampaignDraft draft = new OpsCampaignDraft(trim(f.getTitleId()), trim(f.getTitleEn()),
                trim(f.getBodyId()), trim(f.getBodyEn()), targetType, targetValue, a.spec(), a.ids(), scheduledAt);
        claimSubmitKey(submitKey);
        try {
            String token = core.create(draft, actorAccountId);
            audit.record(actorAccountId, AuditActions.OPS_NOTIFY_CREATED, "OPS_NOTIFY", token,
                    "新建运营通知「" + draft.titleId() + "」；人群 " + audienceSummary(a)
                            + "；" + (scheduledAt == null ? "立即发送" : "定时 " + norm(f.getScheduledAt()) + " WIB")
                            + "；跳转 " + (targetType == OpsTargetType.NONE ? "NONE" : targetType + "/" + targetValue));
            return token;
        } catch (RuntimeException e) {
            // 没建成（核心校验 422 / 落库失败）：放掉键，运营改完同一个抽屉还能再提交
            redis.delete(SUBMIT_KEY_PREFIX + submitKey);
            throw e;
        }
    }

    /**
     * 预估闸：{@code previewCount} 缺 / 非数字 / ≤ 0 → {@code preview-required}；重算为 0 → {@code audience-empty}；
     * 重算比预估多出 max({@value #STALE_MIN_SLACK}, 10%) 以上 → {@code preview-stale}。人数变少不拦（只会少发）。
     */
    static void checkPreview(String rawPreview, long actual) {
        String v = norm(rawPreview);
        long previewed;
        try {
            previewed = v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            previewed = 0;
        }
        if (previewed <= 0) {
            throw AppException.validation("发送前请先预估人数").code(ERR_PREVIEW_REQUIRED);
        }
        if (actual <= 0) {
            throw AppException.validation("当前人群 0 人，无法发送").code(ERR_AUDIENCE_EMPTY);
        }
        long slack = Math.max(STALE_MIN_SLACK, (previewed + 9) / 10);
        if (actual - previewed > slack) {
            throw AppException.validation("人数与预估相差较大，请重新预估").code(ERR_PREVIEW_STALE, actual);
        }
    }

    /** 缺键 / 形状不对按校验错误处理（422），不认领。 */
    private static String requireSubmitKey(String raw) {
        String v = norm(raw);
        if (v == null || !SUBMIT_KEY.matcher(v).matches()) {
            throw AppException.validation("提交已失效，请关闭后重新打开新建").code(ERR_DUPLICATE_SUBMIT);
        }
        return v;
    }

    /**
     * SET NX 认领；已被认领（连点 / 重放 / 已发出）→ 422，不建活动。
     * 用 422 不用 409：admin-core.js 只放行 422 / 403 / 404 回填错误槽，409 运营会看不到任何提示。
     */
    private void claimSubmitKey(String submitKey) {
        Boolean fresh = redis.opsForValue().setIfAbsent(SUBMIT_KEY_PREFIX + submitKey, "1", SUBMIT_KEY_TTL);
        if (!Boolean.TRUE.equals(fresh)) {
            throw AppException.validation("这次提交已处理，请勿重复发送").code(ERR_DUPLICATE_SUBMIT);
        }
    }

    /** 取消：仅「待发送」可取消；已在发送 / 已结束 → 409 {@code cancel.not-pending}（核心给码）。 */
    @Transactional
    public void cancel(String token, long actorAccountId) {
        OpsNotificationCampaign c = require(token); // 先给带码的 404（核心的 404 不挂码）
        core.cancel(token, actorAccountId);
        audit.record(actorAccountId, AuditActions.OPS_NOTIFY_CANCELED, "OPS_NOTIFY", token,
                "取消待发送的运营通知「" + c.getTitleId() + "」");
    }

    // ---------- 表单 → 人群 ----------

    /** 解析后的人群：规格 + （仅 IDS）去重后的 ID 列表。 */
    record Audience(AudienceSpec spec, List<Long> ids) {
    }

    Audience audience(OpsNotifyForm f) {
        String rawMode = norm(f.getAudienceMode());
        AudienceSpec.Mode mode;
        try {
            mode = rawMode == null ? null : AudienceSpec.Mode.valueOf(rawMode);
        } catch (IllegalArgumentException e) {
            mode = null;
        }
        if (mode == null) {
            throw AppException.validation("请选择发送人群").code(OpsNotificationCampaignService.ERR_AUDIENCE_INVALID);
        }
        return switch (mode) {
            case ALL -> new Audience(AudienceSpec.all(), null);
            case IDS -> {
                List<Long> ids = parseIds(f.getUserIds());
                if (ids.isEmpty()) {
                    throw AppException.validation("请填写用户 ID").code(OpsNotificationCampaignService.ERR_IDS_EMPTY);
                }
                yield new Audience(AudienceSpec.ids(), ids);
            }
            // 区间倒置由 AudienceSpec 紧凑构造器抛 audience.invalid（与解析 JSON 同一处判定）
            case FILTER -> new Audience(new AudienceSpec(AudienceSpec.Mode.FILTER,
                    date(f.getRegisteredFrom()), date(f.getRegisteredTo()),
                    date(f.getActiveFrom()), date(f.getActiveTo()),
                    bool(f.getHasPet()), species(f.getSpecies()),
                    bool(f.getHasShopOrder()), bool(f.getHasPhone())), null);
        };
    }

    /**
     * 粘贴的用户 ID：逗号（含全角）/ 空白 / 换行分隔；空段忽略；非数字 → 422；去重后超过 {@value #MAX_IDS} → 422。
     * 不判 ID 是否存在：无效 ID 允许，发送时跳过并计数（D-16）。
     */
    static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<Long> out = new LinkedHashSet<>();
        for (String part : raw.split("[,，\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!ID.matcher(part).matches()) {
                throw AppException.validation("用户 ID 只能是数字，用逗号、空格或换行分隔").code(ERR_IDS_NOT_NUMERIC);
            }
            out.add(Long.parseLong(part));
            if (out.size() > MAX_IDS) {
                throw AppException.validation("指定用户 ID 超过上限").code(ERR_IDS_TOO_MANY, MAX_IDS);
            }
        }
        return new ArrayList<>(out);
    }

    /** {@code datetime-local}（无时区）按 WIB 解释（同 AB-30）；空 = 立即发送。早于当前由核心判 {@code schedule.past}。 */
    static Instant parseSchedule(String raw) {
        String v = norm(raw);
        if (v == null) {
            return null;
        }
        try {
            return ScheduleWindow.fromWib(LocalDateTime.parse(v));
        } catch (RuntimeException e) {
            throw AppException.validation("定时发送时间格式不正确").code(ERR_SCHEDULE_FORMAT);
        }
    }

    private static LocalDate date(String raw) {
        String v = norm(raw);
        if (v == null) {
            return null;
        }
        if (!DATE.matcher(v).matches()) {
            throw invalidAudience();
        }
        try {
            return LocalDate.parse(v);
        } catch (RuntimeException e) {
            throw invalidAudience();
        }
    }

    /** 三态：空 = 不限；true / false；其余 → 422。 */
    private static Boolean bool(String raw) {
        String v = norm(raw);
        if (v == null) {
            return null;
        }
        return switch (v) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw invalidAudience();
        };
    }

    private static Set<PetType> species(List<String> raw) {
        if (raw == null) {
            return null;
        }
        Set<PetType> out = EnumSet.noneOf(PetType.class);
        for (String s : raw) {
            String v = norm(s);
            if (v == null) {
                continue;
            }
            try {
                out.add(PetType.valueOf(v));
            } catch (IllegalArgumentException e) {
                throw invalidAudience();
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static AppException invalidAudience() {
        return AppException.validation("人群条件不合法").code(OpsNotificationCampaignService.ERR_AUDIENCE_INVALID);
    }

    /** 审计摘要：模式 + 条件 JSON（无 PII）/ ID 个数（🔒 不写 ID 列表本身）。 */
    private static String audienceSummary(Audience a) {
        return switch (a.spec().mode()) {
            case ALL -> "ALL";
            case IDS -> "IDS " + Objects.requireNonNullElse(a.ids(), List.of()).size() + " 个";
            case FILTER -> a.spec().toJson();
        };
    }

    /** 文案只去首尾空白，空白串保留为空串交给核心判 {@code text.blank}。 */
    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    static String norm(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
