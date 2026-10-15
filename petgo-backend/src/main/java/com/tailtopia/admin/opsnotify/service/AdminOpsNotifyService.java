package com.tailtopia.admin.opsnotify.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.ops.AdminOpsTargetValidator;
import com.tailtopia.admin.opsnotify.dto.OpsNotifyForm;
import com.tailtopia.auth.service.OpsAudienceQuery;
import com.tailtopia.auth.service.OpsAudienceQuery.IdStatus;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignDraft;
import com.tailtopia.notify.ops.domain.OpsCampaignStatus;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.ops.service.OpsNotificationCampaignService;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台「系统通知发送」（V1.3.4 AB-31 · FR-138 · D-16 / D-20 / D-24；按后台返工清单 §4 对齐 UI 稿 I1–I11）。
 *
 * <p>本类只做<b>表单 → 草稿</b>的翻译、人数预估、指定 ID 校验、跳转目标的具体错误码与审计；真正的落库 / 状态机 / 发送
 * 全在发送核心 {@link OpsNotificationCampaignService}（Story 6.2），这里不碰分批、重试与人群口径。
 *
 * <p>🔴 <b>人数预估与发送同一份 WHERE</b>（{@link OpsAudienceQuery#count}）；指定 ID 的「有效 / 跳过」归类
 * （{@link OpsAudienceQuery#classify}）与它逐条同口径。预估与发送是两次查询，期间人数可能有出入（确认页写「发送时重新计算」）。
 *
 * <p>🔴 <b>四步页只在浏览器里分步</b>，服务端不存草稿：进入「④ 确认」时整表提交 {@link #review}（与新建同一套校验、
 * 按步骤先后报第一处错），「确认发送」再整表提交 {@link #create} 全部重验——前端的逐步校验只是体验护栏。
 *
 * <p>记录不可编辑、不可重发、不可删除；只有「待发送」可以取消。每次新建 / 取消都留审计（{@code OPS_NOTIFY_*}，
 * target = {@code public_token}，不外露自增 id）。
 */
@Service
public class AdminOpsNotifyService {

    /** 指定用户 ID 上限（PRD §3 AB-31：单次最多 5 万行）。 */
    static final int MAX_IDS = 50_000;
    /** 发送记录每页条数。 */
    public static final int PAGE_SIZE = 20;
    /** 大范围发送阈值（UI 稿 I8）：人群为「全部用户」或预计超过此数，须在弹层里手输人数确认。 */
    public static final long TYPED_CONFIRM_THRESHOLD = 10_000;
    /** 标题 / 正文上限，与核心（= notifications 列长）一致；四步页第一步就要报，不能等到核心。 */
    static final int TITLE_MAX = 120;
    static final int BODY_MAX = 255;
    /** 注册 / 活跃时间的相对预设（§0-5）：近 7 / 30 / 90 天；空 = 不限。 */
    public static final List<Integer> WITHIN_PRESETS = List.of(7, 30, 90);

    /** 发送方式。 */
    public static final String SEND_NOW = "NOW";
    public static final String SEND_SCHEDULED = "SCHEDULED";
    /** 指定用户 ID 的来源（列表「指定用户（CSV）」）。 */
    public static final String SOURCE_PASTE = "PASTE";
    public static final String SOURCE_CSV = "CSV";

    public static final String ERR_NOT_FOUND = "admin.err.ops-notify.not-found";
    public static final String ERR_IDS_TOO_MANY = "admin.err.ops-notify.audience.ids-too-many";
    public static final String ERR_SCHEDULE_FORMAT = "admin.err.ops-notify.schedule.format";
    public static final String ERR_SCHEDULE_REQUIRED = "admin.err.ops-notify.schedule.required";
    public static final String ERR_DUPLICATE_SUBMIT = "admin.err.ops-notify.duplicate-submit";
    public static final String ERR_PREVIEW_REQUIRED = "admin.err.ops-notify.preview-required";
    public static final String ERR_AUDIENCE_EMPTY = "admin.err.ops-notify.audience-empty";
    public static final String ERR_PREVIEW_STALE = "admin.err.ops-notify.preview-stale";
    public static final String ERR_TARGET_HTTPS = "admin.err.ops-notify.target.https";
    public static final String ERR_TARGET_TYPE_REQUIRED = "admin.err.opsPopup.targetTypeRequired";
    public static final String ERR_CONFIRM_COUNT = "admin.err.ops-notify.confirm-count-mismatch";

    /**
     * 一次性提交键（新建页渲染时发的 UUID）的 Redis 前缀与有效期：同一个键只能建出一条活动。
     * 有效期取 24 小时：须长于「新建页一直开着、隔很久再点确认发送」的最长间隔（后台会话闲置 8h 过期，再留足余量）——
     * 键先过期而页面还在，重放同一张表单就会再建出一条。
     */
    static final String SUBMIT_KEY_PREFIX = "admin:ops-notify:submit:";
    static final Duration SUBMIT_KEY_TTL = Duration.ofHours(24);
    /** 预估之后人数可以涨多少仍算「同一批人」：max(10 人, 预估数的 10%)，超了要求回到确认页重新计算。 */
    static final long STALE_MIN_SLACK = 10;

    /** 单个 ID：纯数字，最多 18 位（long 内不溢出）。 */
    private static final Pattern ID = Pattern.compile("\\d{1,18}");
    /** 一行里的第一列（CSV 多列时只取第一列）。 */
    private static final Pattern FIELD_SEP = Pattern.compile("[,;\\t，]");
    /** 提交键：UUID 形状（字母数字与连字符，≤ 64），防止把任意串写进 Redis 键名。 */
    private static final Pattern SUBMIT_KEY = Pattern.compile("[A-Za-z0-9-]{8,64}");
    /** 跳过清单里原样回显的一行最多多长（防一行几 MB 的垃圾进 CSV）。 */
    private static final int SKIPPED_VALUE_MAX = 64;

    private final OpsNotificationCampaignService core;
    private final OpsNotificationCampaignRepository campaigns;
    private final OpsAudienceQuery audienceQuery;
    private final AdminOpsTargetValidator targets;
    private final AdminAuditService audit;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public AdminOpsNotifyService(OpsNotificationCampaignService core, OpsNotificationCampaignRepository campaigns,
            OpsAudienceQuery audienceQuery, AdminOpsTargetValidator targets, AdminAuditService audit,
            StringRedisTemplate redis) {
        this(core, campaigns, audienceQuery, targets, audit, redis, Clock.systemUTC());
    }

    /** 测试用：注入固定时钟。 */
    public AdminOpsNotifyService(OpsNotificationCampaignService core, OpsNotificationCampaignRepository campaigns,
            OpsAudienceQuery audienceQuery, AdminOpsTargetValidator targets, AdminAuditService audit,
            StringRedisTemplate redis, Clock clock) {
        this.core = core;
        this.campaigns = campaigns;
        this.audienceQuery = audienceQuery;
        this.targets = targets;
        this.audit = audit;
        this.redis = redis;
        this.clock = clock;
    }

    // ---------- 读 ----------

    /**
     * 发送记录一页（新建的在前）。{@code status} 非五态之一 = 全部；{@code withinDays} 为空 = 不限时间，
     * 否则只要「发送时间」在近 N 天内（未来的定时一律在内）。页码越界由调用方回落。
     */
    @Transactional(readOnly = true)
    public Page<OpsNotificationCampaign> page(String status, Integer withinDays, int page) {
        OpsCampaignStatus s = parseStatus(status);
        OffsetDateTime since = withinDays == null ? null
                : OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofDays(withinDays)), ZoneOffset.UTC);
        return campaigns.search(s == null ? null : s.name(), since, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
    }

    /** 库里是否有过任何一条发送记录（区分「还没有发送过」与「被筛光」，UI 稿 I2）。 */
    @Transactional(readOnly = true)
    public boolean hasAny() {
        return campaigns.count() > 0;
    }

    /** 状态筛选值：五态之一，否则 null（= 全部）。 */
    public static OpsCampaignStatus parseStatus(String raw) {
        String v = norm(raw);
        if (v == null) {
            return null;
        }
        try {
            return OpsCampaignStatus.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 按 public_token 取单条；不存在 → 404（URL 里只用 token）。 */
    @Transactional(readOnly = true)
    public OpsNotificationCampaign require(String token) {
        return campaigns.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("系统通知不存在").code(ERR_NOT_FOUND));
    }

    /**
     * 「② 选择用户」的预计触达（I4，人群块一变 300ms 防抖自动调）。ALL / FILTER 按当前条件实时计数；
     * IDS = 有效人数（与 {@link #checkIds} 同口径）。人群条件本身不合法与新建同样报 422。
     */
    @Transactional(readOnly = true)
    public long preview(OpsNotifyForm f) {
        Audience a = audience(f);
        return count(a);
    }

    // ---------- 指定用户 ID：校验 / 跳过清单 ----------

    /** 一行被跳过的值与原因（非数字行原样回显，截到 {@value #SKIPPED_VALUE_MAX} 字）。 */
    public record Skipped(String value, IdStatus reason) {
    }

    /**
     * 指定用户 ID 的校验结果（I5）：有效人数 + 按原因的跳过数（不存在 / 已注销 / 已封号 / 虚拟号，§0-7）+ 跳过清单。
     * 重复的 ID 只算一次、不算跳过。
     */
    public record IdsCheck(int valid, Map<IdStatus, Integer> skippedByReason, List<Skipped> skipped) {

        public int skippedTotal() {
            return skipped.size();
        }
    }

    /** 粘贴 / CSV 的解析结果：去重后的数字 ID（保持首次出现顺序）+ 非数字行。 */
    record ParsedIds(List<Long> ids, List<String> invalid) {
    }

    /**
     * 校验指定用户 ID（I5）。规则：一行一个 ID；CSV 多列只取第一列；去掉首尾空白与引号；空行忽略；
     * 非空行超过 {@value #MAX_IDS} → 422 {@code ids-too-many}；非数字行<b>不报错</b>，计入「不存在」跳过。
     */
    @Transactional(readOnly = true)
    public IdsCheck checkIds(String raw) {
        ParsedIds p = parseIdLines(raw);
        Map<Long, IdStatus> classes = audienceQuery.classify(p.ids());
        int valid = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (String bad : p.invalid()) {
            skipped.add(new Skipped(bad, IdStatus.NOT_FOUND));
        }
        for (Long id : p.ids()) {
            IdStatus s = classes.getOrDefault(id, IdStatus.NOT_FOUND);
            if (s == IdStatus.OK) {
                valid++;
            } else {
                skipped.add(new Skipped(String.valueOf(id), s));
            }
        }
        Map<IdStatus, Integer> byReason = new EnumMap<>(IdStatus.class);
        for (IdStatus s : List.of(IdStatus.NOT_FOUND, IdStatus.DELETED, IdStatus.BANNED, IdStatus.VIRTUAL)) {
            byReason.put(s, 0);
        }
        for (Skipped s : skipped) {
            byReason.merge(s.reason(), 1, Integer::sum);
        }
        return new IdsCheck(valid, byReason, skipped);
    }

    /**
     * 解析粘贴 / CSV 文本（见 {@link #checkIds}）。文件开头的 UTF-8 BOM 去掉。
     */
    static ParsedIds parseIdLines(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedIds(List.of(), List.of());
        }
        String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
        Set<Long> ids = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        int lines = 0;
        for (String line : text.split("\\r?\\n|\\r")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (++lines > MAX_IDS) {
                throw AppException.validation("指定用户 ID 超过上限").code(ERR_IDS_TOO_MANY, MAX_IDS);
            }
            String first = FIELD_SEP.split(t, 2)[0].trim();
            if (first.length() >= 2 && first.startsWith("\"") && first.endsWith("\"")) {
                first = first.substring(1, first.length() - 1).trim();
            }
            if (ID.matcher(first).matches()) {
                ids.add(Long.parseLong(first));
            } else {
                invalid.add(t.length() > SKIPPED_VALUE_MAX ? t.substring(0, SKIPPED_VALUE_MAX) : t);
            }
        }
        return new ParsedIds(new ArrayList<>(ids), invalid);
    }

    // ---------- 写 ----------

    /** 通过全部校验的表单：发送核心要的东西 + 此刻按条件重算的人数。 */
    public record Prepared(String titleId, String titleEn, String bodyId, String bodyEn, OpsTargetType targetType,
            String targetValue, AudienceSpec spec, List<Long> ids, String idsSource, Instant scheduledAt,
            long count) {
    }

    /**
     * 进入「④ 确认」（I7）：与新建同一套校验，按步骤先后报第一处错（① 内容 → ② 选择用户 → ③ 发送时间），
     * 前端按错误码跳回对应步骤。通过则返回确认页要显示的内容与此刻的预计人数（确认页把它作为 {@code previewCount} 带回）。
     */
    @Transactional(readOnly = true)
    public Prepared review(OpsNotifyForm f) {
        return prepare(f);
    }

    /** 新建结果：token + 定时时刻（null = 立即）。 */
    public record Created(String token, Instant scheduledAt) {
    }

    /**
     * 新建（立即 / 定时）。核心落 {@code PENDING}：立即的在提交后异步发送，定时的由扫描到点发送。
     *
     * <p>🔴 发送不可撤回，服务端三道闸（前端的逐步校验只是体验护栏）：
     * <ul>
     *   <li><b>预估闸</b>：表单须带确认页上的 N（{@code previewCount}）；这里按同一人群重算，0 人拒发，
     *       比 N 多出 max(10, 10%) 以上视为过期、须回确认页重新计算；</li>
     *   <li><b>大范围手输确认</b>（I8）：「全部用户」/ 未选任何条件的「按条件」，或 max(N, 重算人数) &gt;
     *       {@value #TYPED_CONFIRM_THRESHOLD} 时 {@code confirmCount}
     *       必须等于 N——缺 → {@link OpsNotifyTypedConfirmRequired}（Controller 出输入确认弹层），不等 → 422；</li>
     *   <li><b>一次性提交键</b>：新建页渲染时发的 {@code submitKey}，Redis SET NX 认领，同一键第二次提交 422。
     *       校验全过才认领；认领后核心再拒或事务失败则释放，运营改完可再提交。</li>
     * </ul>
     */
    @Transactional
    public Created create(OpsNotifyForm f, long actorAccountId) {
        String submitKey = requireSubmitKey(f.getSubmitKey());
        Prepared p = prepare(f);
        long previewed = checkPreview(f.getPreviewCount(), p.count());
        if (needsTypedConfirm(p.spec(), previewed, p.count())) {
            String typed = norm(f.getConfirmCount());
            if (typed == null) {
                throw new OpsNotifyTypedConfirmRequired(previewed);
            }
            if (!typed.equals(String.valueOf(previewed))) {
                throw AppException.validation("输入的人数与预计人数不一致").code(ERR_CONFIRM_COUNT, previewed);
            }
        }
        OpsCampaignDraft draft = new OpsCampaignDraft(p.titleId(), p.titleEn(), p.bodyId(), p.bodyEn(),
                p.targetType(), p.targetValue(), p.spec(), p.ids(), p.scheduledAt(),
                (int) Math.min(Integer.MAX_VALUE, p.count()), p.idsSource());
        claimSubmitKey(submitKey);
        try {
            String token = core.create(draft, actorAccountId);
            audit.record(actorAccountId, AuditActions.OPS_NOTIFY_CREATED, "OPS_NOTIFY", token,
                    "新建系统通知「" + p.titleId() + "」；人群 " + audienceSummary(p) + "；预计 " + p.count() + " 人；"
                            + (p.scheduledAt() == null ? "立即发送"
                                    : "定时 " + ScheduleWindow.toWib(p.scheduledAt()).toString().replace('T', ' ')
                                            + " WIB")
                            + "；跳转 " + (p.targetType() == OpsTargetType.NONE ? "NONE"
                                    : p.targetType() + "/" + p.targetValue()));
            return new Created(token, p.scheduledAt());
        } catch (RuntimeException e) {
            // 没建成（核心校验 422 / 落库失败）：放掉键，运营改完同一个新建页还能再提交
            redis.delete(SUBMIT_KEY_PREFIX + submitKey);
            throw e;
        }
    }

    /**
     * 是否须手输人数确认（I8）。
     *
     * <p>🔴 阈值按 max(确认页人数, 此刻重算人数) 判：只看表单带回的 {@code previewCount} 可以被改小绕过
     * （预估闸只拦「重算比预估多出太多」，9,500 → 10,400 这类在容差内的涨幅也会漏过）。
     * 「按条件」一个条件都没选 = 全部可接收用户，与「全部用户」同等对待，恒要手输。
     * 要手输的数仍是确认页上显示的 {@code previewed}（运营看到哪个数就输哪个数）。
     */
    public static boolean needsTypedConfirm(AudienceSpec spec, long previewed, long actual) {
        if (spec.mode() == AudienceSpec.Mode.ALL
                || (spec.mode() == AudienceSpec.Mode.FILTER && spec.noFilter())) {
            return true;
        }
        return Math.max(previewed, actual) > TYPED_CONFIRM_THRESHOLD;
    }

    /**
     * 全部校验，按 ① → ② → ③ 的先后报第一处错：文案（空 / 超长）→ 跳转（未选类型 / 格式 / 外链须 https / 商品不可用）
     * → 人群（不合法 / ID 超限 / 有效 0 人）→ 发送时间（未选 / 未填 / 格式 / 已过）。
     */
    Prepared prepare(OpsNotifyForm f) {
        String titleId = trim(f.getTitleId());
        String titleEn = trim(f.getTitleEn());
        String bodyId = trim(f.getBodyId());
        String bodyEn = trim(f.getBodyEn());
        if (blank(titleId) || blank(titleEn) || blank(bodyId) || blank(bodyEn)) {
            throw AppException.validation("标题与正文（EN / ID）均不能为空")
                    .code(OpsNotificationCampaignService.ERR_TEXT_BLANK);
        }
        if (titleId.length() > TITLE_MAX || titleEn.length() > TITLE_MAX
                || bodyId.length() > BODY_MAX || bodyEn.length() > BODY_MAX) {
            throw AppException.validation("标题不超过 " + TITLE_MAX + " 字符、正文不超过 " + BODY_MAX + " 字符")
                    .code(OpsNotificationCampaignService.ERR_TEXT_TOO_LONG);
        }
        if (norm(f.getTargetType()) == null) {
            throw AppException.validation("请选择跳转类型").code(ERR_TARGET_TYPE_REQUIRED);
        }
        OpsTargetType targetType = AdminOpsTargetValidator.parseType(f.getTargetType());
        String targetValue = targetType == OpsTargetType.NONE ? null : norm(f.getTargetValue());
        // AB-31 外链只认 https（UI 稿 I3 说明：在 App 内打开网页）；AB-30 的 http/https 规则不动
        if (targetType == OpsTargetType.EXTERNAL
                && (targetValue == null || !targetValue.toLowerCase(Locale.ROOT).startsWith("https://"))) {
            throw AppException.validation("外部链接须以 https:// 开头").code(ERR_TARGET_HTTPS);
        }
        // 只拦能给出具体码的格式 / 可用性错误；核心随后按同一 OpsTargetPolicy 再兜一遍
        targets.check(targetType, targetValue, true);

        Audience a = audience(f);
        long count = count(a);
        if (count <= 0) {
            throw AppException.validation("当前人群 0 人，无法发送").code(ERR_AUDIENCE_EMPTY);
        }
        Instant scheduledAt = schedule(f.getSendMode(), f.getScheduledAt());
        return new Prepared(titleId, titleEn, bodyId, bodyEn, targetType, targetValue, a.spec(), a.ids(),
                a.idsSource(), scheduledAt, count);
    }

    /**
     * 预估闸：{@code previewCount} 缺 / 非数字 / ≤ 0 → {@code preview-required}；重算为 0 → {@code audience-empty}；
     * 重算比预估多出 max({@value #STALE_MIN_SLACK}, 10%) 以上 → {@code preview-stale}。人数变少不拦（只会少发）。
     *
     * @return 表单带回的预估人数
     */
    static long checkPreview(String rawPreview, long actual) {
        String v = norm(rawPreview);
        long previewed;
        try {
            previewed = v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            previewed = 0;
        }
        if (previewed <= 0) {
            throw AppException.validation("发送前须在确认页确认人数").code(ERR_PREVIEW_REQUIRED);
        }
        if (actual <= 0) {
            throw AppException.validation("当前人群 0 人，无法发送").code(ERR_AUDIENCE_EMPTY);
        }
        long slack = Math.max(STALE_MIN_SLACK, (previewed + 9) / 10);
        if (actual - previewed > slack) {
            throw AppException.validation("人数与预估相差较大，请重新确认").code(ERR_PREVIEW_STALE, actual);
        }
        return previewed;
    }

    /** 缺键 / 形状不对按校验错误处理（422），不认领。 */
    private static String requireSubmitKey(String raw) {
        String v = norm(raw);
        if (v == null || !SUBMIT_KEY.matcher(v).matches()) {
            throw AppException.validation("提交已失效，请重新打开新建页").code(ERR_DUPLICATE_SUBMIT);
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
                "取消待发送的系统通知「" + c.getTitleId() + "」");
    }

    // ---------- 表单 → 人群 / 发送时间 ----------

    /** 解析后的人群：规格 + （仅 IDS）去重后的数字 ID + 来源。 */
    record Audience(AudienceSpec spec, List<Long> ids, String idsSource) {
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
            case ALL -> new Audience(AudienceSpec.all(), null, null);
            case IDS -> {
                ParsedIds p = parseIdLines(f.getUserIds());
                if (p.ids().isEmpty() && p.invalid().isEmpty()) {
                    throw AppException.validation("请填写用户 ID").code(OpsNotificationCampaignService.ERR_IDS_EMPTY);
                }
                yield new Audience(AudienceSpec.ids(), p.ids(),
                        SOURCE_CSV.equals(norm(f.getIdsSource())) ? SOURCE_CSV : SOURCE_PASTE);
            }
            case FILTER -> new Audience(new AudienceSpec(AudienceSpec.Mode.FILTER,
                    null, null, null, null,
                    bool(f.getHasPet()), species(f.getSpecies()),
                    bool(f.getHasShopOrder()), bool(f.getHasPhone()),
                    within(f.getRegisteredWithin()), within(f.getActiveWithin())), null, null);
        };
    }

    /** IDS 模式：有效人数（数字 ID 里满足基础过滤的）；非数字行天然不计。其余模式按条件计数。 */
    private long count(Audience a) {
        if (a.spec().mode() == AudienceSpec.Mode.IDS && (a.ids() == null || a.ids().isEmpty())) {
            return 0;
        }
        return audienceQuery.count(a.spec(), a.ids());
    }

    /**
     * 发送时间（I6）：{@code sendMode} = NOW → 立即（忽略定时框）；SCHEDULED → 定时框必填、按 WIB 解释、须晚于现在。
     * {@code sendMode} 缺省时按定时框是否有值推断（兼容旧调用方）。
     */
    Instant schedule(String rawMode, String rawAt) {
        String mode = norm(rawMode);
        String at = norm(rawAt);
        if (mode == null) {
            mode = at == null ? SEND_NOW : SEND_SCHEDULED;
        }
        if (SEND_NOW.equals(mode)) {
            return null;
        }
        if (!SEND_SCHEDULED.equals(mode)) {
            throw AppException.validation("发送方式不合法").code(ERR_SCHEDULE_FORMAT);
        }
        if (at == null) {
            throw AppException.validation("请填写定时发送时间").code(ERR_SCHEDULE_REQUIRED);
        }
        Instant t;
        try {
            t = ScheduleWindow.fromWib(LocalDateTime.parse(at));
        } catch (RuntimeException e) {
            throw AppException.validation("定时发送时间格式不正确").code(ERR_SCHEDULE_FORMAT);
        }
        if (!t.isAfter(clock.instant())) {
            throw AppException.validation("定时发送时间不能早于当前时刻")
                    .code(OpsNotificationCampaignService.ERR_SCHEDULE_PAST);
        }
        return t;
    }

    /** 相对预设：空 = 不限；只认 7 / 30 / 90，其余 → 422。 */
    private static Integer within(String raw) {
        String v = norm(raw);
        if (v == null) {
            return null;
        }
        try {
            int n = Integer.parseInt(v);
            if (WITHIN_PRESETS.contains(n)) {
                return n;
            }
        } catch (NumberFormatException e) {
            // 落到下面统一报
        }
        throw invalidAudience();
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

    /** 单选（§0-6）：空 = 不限；CAT / DOG / OTHER。 */
    private static Set<PetType> species(String raw) {
        String v = norm(raw);
        if (v == null) {
            return null;
        }
        try {
            return Set.of(PetType.valueOf(v));
        } catch (IllegalArgumentException e) {
            throw invalidAudience();
        }
    }

    private static AppException invalidAudience() {
        return AppException.validation("人群条件不合法").code(OpsNotificationCampaignService.ERR_AUDIENCE_INVALID);
    }

    /** 审计摘要：模式 + 条件 JSON（无 PII）/ ID 个数与来源（🔒 不写 ID 列表本身）。 */
    private static String audienceSummary(Prepared p) {
        return switch (p.spec().mode()) {
            case ALL -> "ALL";
            case IDS -> "IDS " + (p.ids() == null ? 0 : p.ids().size()) + " 个（" + p.idsSource() + "）";
            case FILTER -> p.spec().toJson();
        };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 文案只去首尾空白。 */
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
