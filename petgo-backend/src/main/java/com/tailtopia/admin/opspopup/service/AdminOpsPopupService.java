package com.tailtopia.admin.opspopup.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.ops.AdminOpsTargetValidator;
import com.tailtopia.admin.opspopup.dto.OpsPopupForm;
import com.tailtopia.admin.opspopup.service.OpsPopupValidationException.FieldError;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupAudience;
import com.tailtopia.ops.popup.domain.OpsPopupFrequency;
import com.tailtopia.ops.popup.domain.OpsPopupStatus;
import com.tailtopia.ops.popup.repository.OpsPopupImpressionRepository;
import com.tailtopia.ops.popup.repository.OpsPopupRepository;
import com.tailtopia.ops.target.OpsTargetPolicy;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营弹窗后台维护（V1.3.4 AB-30 · FR-137 · D-23；按后台返工清单 §3 对齐 PRD / UI 稿 H1–H10）。
 *
 * <p>🔴 <b>校验在这里，不在模板</b>：模板的 required / maxlength 只是体验护栏，绕得过；这里绕不过。
 * 库层 CHECK（长度、{@code ends_at > starts_at}、NONE ⇔ value 为空）是最后一道，但它报出来是 500 ——
 * 所以每条都要在这里先拦下、挂上自己的码。<b>一次收集全部字段错误</b>（UI 稿 H7：全部标红 + 汇总条），
 * 以 {@link OpsPopupValidationException} 抛出。
 *
 * <p>🔴 <b>跳转目标只问 {@link OpsTargetPolicy}</b>（经 {@link AdminOpsTargetValidator}），不在后台另写一份规则：
 * App 候选过滤用的是同一个类，两份规则迟早漂移，表现为「后台保存成功、App 永远不弹」。
 *
 * <p><b>后台比表结构更严</b>（返工清单 §0-4，不加迁移）：EN 按钮文案、结束时间、优先级、跳转类型都必填；
 * 图片 EN / ID 至少一张 —— 只传 EN 时存进 {@code image_url_id}（App 回落链以 ID 为底），{@code image_url_en} 留空。
 *
 * <p>🔴 <b>删除只许「未开始」</b>（§0-2）：{@code ACTIVE} 且未到开始时间且从未有曝光记录。曝光记录对弹窗是级联删，
 * 上线过的弹窗删了频次就失忆 —— 只能停用。已停用而未到开始时间的同样不能删（只能启用）。
 * ⚠️ 游客的曝光不落库，「从未有曝光」单独靠不住；所以编辑时<b>开始时间已过的弹窗不许把开始时间改到未来</b>
 * （{@code admin.err.opsPopup.startLocked}），「未到开始时间」才真正等于「从未开始过」。
 *
 * <p>每次写操作都留审计（{@code OPS_POPUP_*}）：弹窗是冷启动第一眼看到的东西，误配时要能回答「谁、何时、改了哪条」。
 */
@Service
public class AdminOpsPopupService {

    /** 与 {@code ops_popups} 列宽一致（V20261012_1106）。 */
    static final int NAME_MAX = 100;
    static final int IMAGE_MAX = 512;
    static final int TITLE_MAX = 120;
    static final int BODY_MAX = 255;
    static final int BUTTON_MAX = 40;
    /** 新老分界默认天数（PRD 补充细则，与表默认值一致）。 */
    static final int DEFAULT_NEW_USER_DAYS = 7;
    /** 新老分界天数上限（10 年）：再大已无「新用户」含义，多半是手滑多打了几个 0。 */
    static final int NEW_USER_DAYS_MAX = 3650;
    /** 优先级取值范围：只比大小，给足余量即可；不设界时 21 亿这类手滑值也能存进去，列表里一眼看不出谁压谁。 */
    static final int PRIORITY_MIN = -100_000;
    static final int PRIORITY_MAX = 100_000;

    private final OpsPopupRepository popups;
    private final OpsPopupImpressionRepository impressions;
    private final AdminOpsTargetValidator targets;
    private final AdminAuditService audit;

    public AdminOpsPopupService(OpsPopupRepository popups, OpsPopupImpressionRepository impressions,
            AdminOpsTargetValidator targets, AdminAuditService audit) {
        this.popups = popups;
        this.impressions = impressions;
        this.targets = targets;
        this.audit = audit;
    }

    /** 后台列表：全部弹窗，与 App 取用顺序同序（优先级降序、同值后建的在前）。 */
    @Transactional(readOnly = true)
    public List<OpsPopup> all() {
        return popups.findAllByOrderByPriorityDescCreatedAtDescIdDesc();
    }

    /**
     * 后台列表（H1）：按合一状态 + 名称（不区分大小写的包含）筛选；{@code state == null} / {@code q} 空 = 不筛。
     * 弹窗总量是两位数级别，内存筛即可，不为它加查询。
     */
    @Transactional(readOnly = true)
    public List<OpsPopup> list(OpsPopupState state, String q, Instant now) {
        String needle = norm(q) == null ? null : norm(q).toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(p -> state == null || OpsPopupState.of(p, now) == state)
                .filter(p -> needle == null || p.getName().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    /** 汇总条四格（H1）：按合一状态计数，不受筛选影响。 */
    @Transactional(readOnly = true)
    public Map<OpsPopupState, Long> counts(Instant now) {
        Map<OpsPopupState, Long> m = new EnumMap<>(OpsPopupState.class);
        for (OpsPopupState s : OpsPopupState.values()) {
            m.put(s, 0L);
        }
        for (OpsPopup p : all()) {
            m.merge(OpsPopupState.of(p, now), 1L, Long::sum);
        }
        return m;
    }

    /** 按 public_token 取单条；不存在 → 404（URL 里只用 token，自增 id 不外露）。 */
    @Transactional(readOnly = true)
    public OpsPopup require(String token) {
        return popups.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("运营弹窗不存在")
                        .code("admin.err.opsPopup.notFound"));
    }

    /**
     * 新建。🔴 直接 {@code ACTIVE}（返工清单 §0-1：PRD「新建后按时间进入未开始 / 进行中」）——
     * 领域默认仍是 DISABLED（{@link OpsPopup#create}），这里显式启用，App 候选口径不变（启用 + 时间窗内才弹）。
     */
    @Transactional
    public OpsPopup create(OpsPopupForm form, long actorAccountId) {
        Validated v = validate(form, DEFAULT_NEW_USER_DAYS, true, null, Instant.now());
        OpsPopup p = OpsPopup.create(v.name, v.startsAt, v.endsAt, v.priority, v.audience, v.newUserDays,
                v.frequency, v.imageUrlId, v.imageUrlEn, v.titleId, v.titleEn, v.bodyId, v.bodyEn,
                v.buttonTextId, v.buttonTextEn, v.targetType, v.targetValue);
        p.activate();
        popups.save(p);
        audit.record(actorAccountId, AuditActions.OPS_POPUP_CREATED, "OPS_POPUP", p.getPublicToken(),
                "新建运营弹窗「" + v.name + "」（按生效时间自动开始）；跳转 " + targetSummary(v));
        return p;
    }

    /** 编辑。🔴 不换 id / token、不改启停状态（见 {@link OpsPopup#update}）。 */
    @Transactional
    public OpsPopup update(String token, OpsPopupForm form, long actorAccountId) {
        OpsPopup p = require(token);
        // 🔴 跳转目标没改就不问可用性（只验格式）：商品临时下架时，指向它的弹窗仍要能改文案 / 时间窗 ——
        //    与 activate 同理，App 候选接口每次都现过滤，下架期间本就不弹。改了目标才要求新目标在售。
        String rawType = norm(form.getTargetType());
        boolean targetChanged = rawType == null || !rawType.equals(p.getTargetType().name())
                || !Objects.equals(norm(form.getTargetValue()), p.getTargetValue());
        // 🔴 已开始过的弹窗锁住开始时间不许挪到未来：否则它会重新变成「未开始」而可删（游客曝光不落库，挡不住）
        Validated v = validate(form, p.getNewUserDays(), targetChanged, p.getStartsAt(), Instant.now());
        p.update(v.name, v.startsAt, v.endsAt, v.priority, v.audience, v.newUserDays, v.frequency,
                v.imageUrlId, v.imageUrlEn, v.titleId, v.titleEn, v.bodyId, v.bodyEn,
                v.buttonTextId, v.buttonTextEn, v.targetType, v.targetValue);
        popups.save(p);
        audit.record(actorAccountId, AuditActions.OPS_POPUP_UPDATED, "OPS_POPUP", token,
                "编辑运营弹窗「" + v.name + "」；跳转 " + targetSummary(v));
        return p;
    }

    /**
     * 启用。
     *
     * <p>⚠️ 不在这里重验跳转目标：商品可能在保存后才下架，但 App 候选接口每次都按 {@code isAvailable} 现过滤，
     * 下架商品的弹窗自然不弹；在这里拦反而会让「商品重新上架后弹窗自动恢复」这条路断掉。
     */
    @Transactional
    public void activate(String token, long actorAccountId) {
        OpsPopup p = require(token);
        p.activate();
        popups.save(p);
        audit.record(actorAccountId, AuditActions.OPS_POPUP_ACTIVATED, "OPS_POPUP", token,
                "启用运营弹窗「" + p.getName() + "」");
    }

    @Transactional
    public void deactivate(String token, long actorAccountId) {
        OpsPopup p = require(token);
        p.disable();
        popups.save(p);
        audit.record(actorAccountId, AuditActions.OPS_POPUP_DEACTIVATED, "OPS_POPUP", token,
                "停用运营弹窗「" + p.getName() + "」");
    }

    /**
     * 能否删除（§0-2）：启用中 + 未到开始时间 + 从未有曝光。列表上「删除」链接与删除端点用同一判定。
     * 「未到开始时间」= 从未开始过，靠编辑时的开始时间锁（{@code startLocked}）保证：已开始的改不回未来。
     */
    @Transactional(readOnly = true)
    public boolean deletable(OpsPopup p, Instant now) {
        return p.getStatus() == OpsPopupStatus.ACTIVE
                && now.isBefore(p.getStartsAt())
                && !impressions.existsByPopupId(p.getId());
    }

    /** 删除「未开始」的弹窗；其余一律 409（上线过的只能停用）。审计 {@code OPS_POPUP_DELETED}。 */
    @Transactional
    public void delete(String token, long actorAccountId) {
        OpsPopup p = require(token);
        if (!deletable(p, Instant.now())) {
            throw AppException.conflict("只有未开始的弹窗可以删除")
                    .code("admin.err.opsPopup.deleteNotPending");
        }
        String name = p.getName();
        popups.delete(p);
        audit.record(actorAccountId, AuditActions.OPS_POPUP_DELETED, "OPS_POPUP", token,
                "删除未开始的运营弹窗「" + name + "」");
    }

    // ---------- 校验 ----------

    /** 校验并归一化后的字段（空白串一律 null，其余 trim）。 */
    private record Validated(String name, int priority, Instant startsAt, Instant endsAt,
            OpsPopupAudience audience, int newUserDays, OpsPopupFrequency frequency,
            String imageUrlId, String imageUrlEn, String titleId, String titleEn,
            String bodyId, String bodyEn, String buttonTextId, String buttonTextEn,
            OpsTargetType targetType, String targetValue) {
    }

    /** 收集器：同一槽可有多条（汇总条按槽计数）。 */
    private static final class Errors {
        final List<FieldError> list = new ArrayList<>();

        void add(String slot, String code, List<String> inputs, Object... args) {
            list.add(new FieldError(slot, code, args, inputs));
        }
    }

    /**
     * 逐项校验，<b>收集全部错误</b>后一次抛出（UI 稿 H7）。
     *
     * @param fallbackNewUserDays 天数未提交 / 人群为「全部」时的取值（新建 = 默认 7；编辑 = 原值）
     * @param checkAvailability 是否校验跳转目标可用性（新建恒 true；编辑仅在目标改动时 true）
     * @param currentStartsAt   编辑时库里的原开始时间（新建 = null）：已早于 {@code now} 则新开始时间不得晚于 {@code now}
     * @param now               判定「已开始」与「改到未来」的此刻
     */
    Validated validate(OpsPopupForm f, int fallbackNewUserDays, boolean checkAvailability,
            Instant currentStartsAt, Instant now) {
        Errors e = new Errors();

        String name = norm(f.getName());
        if (name == null) {
            e.add("name", "admin.err.opsPopup.nameRequired", List.of("name"));
        } else if (len(name) > NAME_MAX) {
            e.add("name", "admin.err.opsPopup.nameTooLong", List.of("name"), NAME_MAX);
        }

        // 图片：EN / ID 至少一张；只传一张时两语共用 —— 只有 EN 时挪进 ID 列（App 回落链以 ID 为底）
        String imageUrlId = norm(f.getImageUrlId());
        String imageUrlEn = norm(f.getImageUrlEn());
        if (imageUrlId == null && imageUrlEn == null) {
            e.add("image", "admin.err.opsPopup.imageRequired", List.of("image"));
        }
        if (imageUrlEn != null && !isUsableImage(imageUrlEn)) {
            e.add("image", "admin.err.opsPopup.imageEnInvalid", List.of("image"));
        }
        if (imageUrlId != null && !isUsableImage(imageUrlId)) {
            e.add("image", "admin.err.opsPopup.imageIdInvalid", List.of("image"));
        }
        if (imageUrlId == null && imageUrlEn != null) {
            imageUrlId = imageUrlEn;
            imageUrlEn = null;
        }

        String titleEn = norm(f.getTitleEn());
        if (titleEn != null && len(titleEn) > TITLE_MAX) {
            e.add("title", "admin.err.opsPopup.titleEnTooLong", List.of("titleEn"), TITLE_MAX);
        }
        String titleId = norm(f.getTitleId());
        if (titleId != null && len(titleId) > TITLE_MAX) {
            e.add("title", "admin.err.opsPopup.titleIdTooLong", List.of("titleId"), TITLE_MAX);
        }
        String bodyEn = norm(f.getBodyEn());
        if (bodyEn != null && len(bodyEn) > BODY_MAX) {
            e.add("body", "admin.err.opsPopup.bodyEnTooLong", List.of("bodyEn"), BODY_MAX);
        }
        String bodyId = norm(f.getBodyId());
        if (bodyId != null && len(bodyId) > BODY_MAX) {
            e.add("body", "admin.err.opsPopup.bodyIdTooLong", List.of("bodyId"), BODY_MAX);
        }

        // 按钮文案：EN / ID 都必填（§0-4，后台强制；表结构 EN 仍可空、App 回落不动）
        String buttonTextEn = norm(f.getButtonTextEn());
        String buttonTextId = norm(f.getButtonTextId());
        List<String> missingButtons = new ArrayList<>();
        if (buttonTextEn == null) {
            missingButtons.add("buttonTextEn");
        }
        if (buttonTextId == null) {
            missingButtons.add("buttonTextId");
        }
        if (!missingButtons.isEmpty()) {
            e.add("button", "admin.err.opsPopup.buttonRequired", missingButtons);
        }
        if (buttonTextEn != null && len(buttonTextEn) > BUTTON_MAX) {
            e.add("button", "admin.err.opsPopup.buttonEnTooLong", List.of("buttonTextEn"), BUTTON_MAX);
        }
        if (buttonTextId != null && len(buttonTextId) > BUTTON_MAX) {
            e.add("button", "admin.err.opsPopup.buttonIdTooLong", List.of("buttonTextId"), BUTTON_MAX);
        }

        // 跳转：类型必填、无默认（H4 占位「请选择跳转类型」）；值的规则全在共享校验器
        OpsTargetType targetType = null;
        String targetValue = norm(f.getTargetValue());
        String rawType = norm(f.getTargetType());
        if (rawType == null) {
            e.add("target", "admin.err.opsPopup.targetTypeRequired", List.of("targetType"));
        } else {
            try {
                targetType = AdminOpsTargetValidator.parseType(rawType);
                targets.check(targetType, targetValue, checkAvailability);
            } catch (AppException ex) {
                boolean typeErr = AdminOpsTargetValidator.ERR_TYPE.equals(ex.getMessageCode())
                        || AdminOpsTargetValidator.ERR_NONE_WITH_VALUE.equals(ex.getMessageCode());
                e.add("target", ex.getMessageCode(), List.of(typeErr ? "targetType" : "targetValue"),
                        ex.getMessageArgs());
            }
        }

        // 生效时间：开始、结束都必填（§0-4），结束须严格晚于开始（与库层 ck_ops_popups_window 同口径，左闭右开）
        Instant startsAt = null;
        Instant endsAt = null;
        String rawStart = norm(f.getStartsAt());
        String rawEnd = norm(f.getEndsAt());
        if (rawStart == null) {
            e.add("window", "admin.err.opsPopup.startsAtRequired", List.of("startsAt"));
        } else {
            startsAt = parseWib(rawStart);
            if (startsAt == null) {
                e.add("window", "admin.err.opsPopup.startsAtFormat", List.of("startsAt"));
            } else if (currentStartsAt != null && !now.isBefore(currentStartsAt) && startsAt.isAfter(now)) {
                // 已开始过（进行中 / 已结束 / 中途停用）→ 开始时间不能改到未来（改到过去或此刻仍可）
                e.add("window", "admin.err.opsPopup.startLocked", List.of("startsAt"));
            }
        }
        if (rawEnd == null) {
            e.add("window", "admin.err.opsPopup.endsAtRequired", List.of("endsAt"));
        } else {
            endsAt = parseWib(rawEnd);
            if (endsAt == null) {
                e.add("window", "admin.err.opsPopup.endsAtFormat", List.of("endsAt"));
            }
        }
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            e.add("window", "admin.err.opsPopup.window", List.of("startsAt", "endsAt"));
        }

        OpsPopupFrequency frequency = parseEnum(OpsPopupFrequency.class, f.getFrequency());
        if (norm(f.getFrequency()) == null) {
            e.add("frequency", "admin.err.opsPopup.frequencyRequired", List.of("frequency"));
        } else if (frequency == null) {
            e.add("frequency", "admin.err.opsPopup.frequency", List.of("frequency"));
        }

        // 人群：新 / 老两种都用同一个 N（§0-3）；「全部」时 N 无意义，沿用原值（编辑）/ 默认 7（新建），不校验
        OpsPopupAudience audience = parseEnum(OpsPopupAudience.class, f.getAudience());
        int newUserDays = fallbackNewUserDays;
        if (norm(f.getAudience()) == null) {
            e.add("audience", "admin.err.opsPopup.audienceRequired", List.of("audience"));
        } else if (audience == null) {
            e.add("audience", "admin.err.opsPopup.audience", List.of("audience"));
        } else if (audience != OpsPopupAudience.ALL) {
            Integer days = parseInt(norm(f.getNewUserDays()));
            if (days == null || days <= 0 || days > NEW_USER_DAYS_MAX) {
                e.add("audience", "admin.err.opsPopup.newUserDays", List.of("newUserDays"));
            } else {
                newUserDays = days;
            }
        }

        int priority = 0;
        String rawPriority = norm(f.getPriority());
        if (rawPriority == null) {
            e.add("priority", "admin.err.opsPopup.priorityRequired", List.of("priority"));
        } else {
            Integer pr = parseInt(rawPriority);
            if (pr == null || pr < PRIORITY_MIN || pr > PRIORITY_MAX) {
                e.add("priority", "admin.err.opsPopup.priority", List.of("priority"));
            } else {
                priority = pr;
            }
        }

        if (!e.list.isEmpty()) {
            throw new OpsPopupValidationException(e.list);
        }
        return new Validated(name, priority, startsAt, endsAt, audience, newUserDays, frequency,
                imageUrlId, imageUrlEn, titleId, titleEn, bodyId, bodyEn, buttonTextId, buttonTextEn,
                targetType, targetValue);
    }

    /**
     * 图片值：公开桶 objectKey，或 https 完整地址。
     * 🔴 {@code http://} 一律拒：App 读接口不下发明文图（{@code OpsPopupCandidateService#resolveImage}），
     * 存进去就是一条「后台看着有图、App 永远不弹」的弹窗。
     */
    private static boolean isUsableImage(String v) {
        if (len(v) > IMAGE_MAX) {
            return false;
        }
        if (v.regionMatches(true, 0, "https://", 0, 8)) {
            return OpsTargetPolicy.isHttpsUrl(v);
        }
        return !v.contains("://");
    }

    /** {@code datetime-local}（无时区）按 WIB 解释（同 {@code AdminContentPinController#toInstant}）；格式错 → null。 */
    private static Instant parseWib(String raw) {
        try {
            return ScheduleWindow.fromWib(LocalDateTime.parse(raw));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 枚举名 → 值；空或非法 → null（由调用方区分「未选」与「非法」）。 */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        String v = norm(raw);
        if (v == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer parseInt(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String targetSummary(Validated v) {
        return v.targetType == OpsTargetType.NONE ? "NONE" : v.targetType + "/" + v.targetValue;
    }

    /** trim；空白串归一为 null（可空列存 null 而不是空串，App 侧回落逻辑才判得准）。 */
    static String norm(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 按 code point 计长：与 PostgreSQL VARCHAR(n) 的字符数口径一致（emoji 算 1 个，不是 2 个）。 */
    private static int len(String s) {
        return s.codePointCount(0, s.length());
    }
}
