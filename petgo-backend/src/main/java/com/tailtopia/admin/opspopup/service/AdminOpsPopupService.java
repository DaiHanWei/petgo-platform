package com.tailtopia.admin.opspopup.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.opspopup.dto.OpsPopupForm;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupAudience;
import com.tailtopia.ops.popup.domain.OpsPopupFrequency;
import com.tailtopia.ops.popup.repository.OpsPopupRepository;
import com.tailtopia.ops.target.OpsTargetPolicy;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营弹窗后台维护（V1.3.4 AB-30 · FR-137 · D-23）。
 *
 * <p>🔴 <b>校验在这里，不在模板</b>：模板的 required / maxlength 只是体验护栏，绕得过；这里绕不过。
 * 库层 CHECK（长度、{@code ends_at > starts_at}、NONE ⇔ value 为空）是最后一道，但它报出来是 500 ——
 * 运营看到的是「服务器出错」而不是「结束时间要晚于开始时间」，所以每条都要在这里先拦下、挂上自己的码。
 *
 * <p>🔴 <b>跳转目标只问 {@link OpsTargetPolicy}</b>（先 {@code isWellFormed} 再 {@code isAvailable}），
 * 不在后台另写一份规则：App 候选过滤用的是同一个类，两份规则迟早漂移，表现为「后台保存成功、App 永远不弹」。
 *
 * <p>🔴 <b>不提供删除</b>：曝光记录 {@code ops_popup_impressions} 对弹窗是级联删，删了频次就失忆，
 * 同一用户会再看到一次「每人一次」的弹窗。停用即可。
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
    private final OpsTargetPolicy targets;
    private final AdminAuditService audit;

    public AdminOpsPopupService(OpsPopupRepository popups, OpsTargetPolicy targets, AdminAuditService audit) {
        this.popups = popups;
        this.targets = targets;
        this.audit = audit;
    }

    /** 后台列表：全部弹窗，与 App 取用顺序同序（优先级降序、同值后建的在前）。 */
    @Transactional(readOnly = true)
    public List<OpsPopup> all() {
        return popups.findAllByOrderByPriorityDescCreatedAtDescIdDesc();
    }

    /** 按 public_token 取单条；不存在 → 404（URL 里只用 token，自增 id 不外露）。 */
    @Transactional(readOnly = true)
    public OpsPopup require(String token) {
        return popups.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("运营弹窗不存在")
                        .code("admin.err.opsPopup.notFound"));
    }

    /** 新建。🔴 一律 DISABLED（{@link OpsPopup#create} 的默认）：运营检查过图和文案再手动启用。 */
    @Transactional
    public OpsPopup create(OpsPopupForm form, long actorAccountId) {
        Validated v = validate(form, DEFAULT_NEW_USER_DAYS, true);
        OpsPopup p = OpsPopup.create(v.name, v.startsAt, v.endsAt, v.priority, v.audience, v.newUserDays,
                v.frequency, v.imageUrlId, v.imageUrlEn, v.titleId, v.titleEn, v.bodyId, v.bodyEn,
                v.buttonTextId, v.buttonTextEn, v.targetType, v.targetValue);
        popups.save(p);
        audit.record(actorAccountId, AuditActions.OPS_POPUP_CREATED, "OPS_POPUP", p.getPublicToken(),
                "新建运营弹窗「" + v.name + "」（默认停用）；跳转 " + targetSummary(v));
        return p;
    }

    /** 编辑。🔴 不换 id / token、不改启停状态（见 {@link OpsPopup#update}）。 */
    @Transactional
    public OpsPopup update(String token, OpsPopupForm form, long actorAccountId) {
        OpsPopup p = require(token);
        // 新老分界天数只在 NEW / OLD 时显示；人群选「全部」时输入框被禁用不提交 —— 沿用原值，不冲成默认 7。
        // 🔴 跳转目标没改就不问可用性（只验格式）：商品临时下架时，指向它的弹窗仍要能改文案 / 时间窗 ——
        //    与 activate 同理，App 候选接口每次都现过滤，下架期间本就不弹。改了目标才要求新目标在售。
        OpsTargetType newType = parseEnum(OpsTargetType.class, form.getTargetType(), OpsTargetType.NONE,
                "admin.err.opsPopup.targetType");
        boolean targetChanged = newType != p.getTargetType()
                || !Objects.equals(norm(form.getTargetValue()), p.getTargetValue());
        Validated v = validate(form, p.getNewUserDays(), targetChanged);
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

    // ---------- 校验 ----------

    /** 校验并归一化后的字段（空白串一律 null，其余 trim）。 */
    private record Validated(String name, int priority, Instant startsAt, Instant endsAt,
            OpsPopupAudience audience, int newUserDays, OpsPopupFrequency frequency,
            String imageUrlId, String imageUrlEn, String titleId, String titleEn,
            String bodyId, String bodyEn, String buttonTextId, String buttonTextEn,
            OpsTargetType targetType, String targetValue) {
    }

    /**
     * 逐项校验，遇到第一处错误即抛（抽屉里一次只显示一条红字 + 标红对应字段）。
     *
     * @param fallbackNewUserDays 天数未提交时的取值（新建 = 默认 7；编辑 = 原值）
     * @param checkAvailability 是否校验跳转目标可用性（新建恒 true；编辑仅在目标改动时 true）
     */
    Validated validate(OpsPopupForm f, int fallbackNewUserDays, boolean checkAvailability) {
        String name = norm(f.getName());
        if (name == null) {
            throw AppException.validation("请填写名称").code("admin.err.opsPopup.nameRequired");
        }
        if (len(name) > NAME_MAX) {
            throw AppException.validation("名称过长").code("admin.err.opsPopup.nameTooLong", NAME_MAX);
        }

        int priority = 0;
        String rawPriority = norm(f.getPriority());
        if (rawPriority != null) {
            try {
                priority = Integer.parseInt(rawPriority);
            } catch (NumberFormatException e) {
                throw AppException.validation("优先级须为整数").code("admin.err.opsPopup.priority");
            }
            if (priority < PRIORITY_MIN || priority > PRIORITY_MAX) {
                throw AppException.validation("优先级超出范围").code("admin.err.opsPopup.priority");
            }
        }

        String rawStart = norm(f.getStartsAt());
        if (rawStart == null) {
            throw AppException.validation("请填写开始时间").code("admin.err.opsPopup.startsAtRequired");
        }
        Instant startsAt = parseWib(rawStart, "admin.err.opsPopup.startsAtFormat");
        String rawEnd = norm(f.getEndsAt());
        Instant endsAt = rawEnd == null ? null : parseWib(rawEnd, "admin.err.opsPopup.endsAtFormat");
        // 与库层 ck_ops_popups_window 同口径：结束必须严格晚于开始（左闭右开，相等 = 一秒都不生效）
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            throw AppException.validation("结束时间须晚于开始时间").code("admin.err.opsPopup.window");
        }

        OpsPopupAudience audience = parseEnum(OpsPopupAudience.class, f.getAudience(), OpsPopupAudience.ALL,
                "admin.err.opsPopup.audience");
        int newUserDays = fallbackNewUserDays;
        String rawDays = norm(f.getNewUserDays());
        if (rawDays != null) {
            try {
                newUserDays = Integer.parseInt(rawDays);
            } catch (NumberFormatException e) {
                newUserDays = 0;
            }
            if (newUserDays <= 0 || newUserDays > NEW_USER_DAYS_MAX) {
                throw AppException.validation("新老用户分界天数须为正整数").code("admin.err.opsPopup.newUserDays");
            }
        }
        OpsPopupFrequency frequency = parseEnum(OpsPopupFrequency.class, f.getFrequency(), OpsPopupFrequency.ONCE,
                "admin.err.opsPopup.frequency");

        String imageUrlId = norm(f.getImageUrlId());
        if (imageUrlId == null) {
            throw AppException.validation("请上传印尼文图片").code("admin.err.opsPopup.imageIdRequired");
        }
        if (!isUsableImage(imageUrlId)) {
            throw AppException.validation("印尼文图片地址不可用").code("admin.err.opsPopup.imageIdInvalid");
        }
        String imageUrlEn = norm(f.getImageUrlEn());
        if (imageUrlEn != null && !isUsableImage(imageUrlEn)) {
            throw AppException.validation("英文图片地址不可用").code("admin.err.opsPopup.imageEnInvalid");
        }

        String titleId = norm(f.getTitleId());
        if (titleId != null && len(titleId) > TITLE_MAX) {
            throw AppException.validation("印尼文标题过长").code("admin.err.opsPopup.titleIdTooLong", TITLE_MAX);
        }
        String titleEn = norm(f.getTitleEn());
        if (titleEn != null && len(titleEn) > TITLE_MAX) {
            throw AppException.validation("英文标题过长").code("admin.err.opsPopup.titleEnTooLong", TITLE_MAX);
        }
        String bodyId = norm(f.getBodyId());
        if (bodyId != null && len(bodyId) > BODY_MAX) {
            throw AppException.validation("印尼文正文过长").code("admin.err.opsPopup.bodyIdTooLong", BODY_MAX);
        }
        String bodyEn = norm(f.getBodyEn());
        if (bodyEn != null && len(bodyEn) > BODY_MAX) {
            throw AppException.validation("英文正文过长").code("admin.err.opsPopup.bodyEnTooLong", BODY_MAX);
        }
        String buttonTextId = norm(f.getButtonTextId());
        if (buttonTextId == null) {
            throw AppException.validation("请填写印尼文按钮文案").code("admin.err.opsPopup.buttonIdRequired");
        }
        if (len(buttonTextId) > BUTTON_MAX) {
            throw AppException.validation("印尼文按钮文案过长").code("admin.err.opsPopup.buttonIdTooLong", BUTTON_MAX);
        }
        String buttonTextEn = norm(f.getButtonTextEn());
        if (buttonTextEn != null && len(buttonTextEn) > BUTTON_MAX) {
            throw AppException.validation("英文按钮文案过长").code("admin.err.opsPopup.buttonEnTooLong", BUTTON_MAX);
        }

        OpsTargetType targetType = parseEnum(OpsTargetType.class, f.getTargetType(), OpsTargetType.NONE,
                "admin.err.opsPopup.targetType");
        String targetValue = norm(f.getTargetValue());
        checkTarget(targetType, targetValue, checkAvailability);

        return new Validated(name, priority, startsAt, endsAt, audience, newUserDays, frequency,
                imageUrlId, imageUrlEn, titleId, titleEn, bodyId, bodyEn, buttonTextId, buttonTextEn,
                targetType, targetValue);
    }

    /**
     * 跳转目标：先格式（{@link OpsTargetPolicy#isWellFormed}），再可用性（{@link OpsTargetPolicy#isAvailable}，
     * 商品须存在且在售）。格式错按类型给各自的话 —— 「跳转目标不合法」五种类型共用一句，运营不知道该改哪。
     */
    private void checkTarget(OpsTargetType type, String value, boolean checkAvailability) {
        if (!OpsTargetPolicy.isWellFormed(type, value)) {
            switch (type) {
                case NONE -> throw AppException.validation("「不跳转」不应带跳转值")
                        .code("admin.err.opsPopup.targetNoneWithValue");
                case PRODUCT -> throw AppException.validation("请填写正确的商品 token")
                        .code("admin.err.opsPopup.targetProduct");
                case CATEGORY -> throw AppException.validation("请选择商品分类")
                        .code("admin.err.opsPopup.targetCategory");
                case APP_PAGE -> throw AppException.validation("请选择 App 页面")
                        .code("admin.err.opsPopup.targetAppPage");
                default -> throw AppException.validation("外部链接须以 http:// 或 https:// 开头")
                        .code("admin.err.opsPopup.targetExternal");
            }
        }
        if (checkAvailability && !targets.isAvailable(type, value)) {
            // 能走到这里的只有 PRODUCT：格式对，但商品不存在或已下架
            throw AppException.validation("商品不存在或已下架").code("admin.err.opsPopup.productUnavailable");
        }
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

    /** {@code datetime-local}（无时区）按 WIB 解释（同 {@code AdminContentPinController#toInstant}）。 */
    private static Instant parseWib(String raw, String code) {
        try {
            return ScheduleWindow.fromWib(LocalDateTime.parse(raw));
        } catch (RuntimeException e) {
            throw AppException.validation("时间格式不正确").code(code);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, String code) {
        String v = norm(raw);
        if (v == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            throw AppException.validation("取值不合法").code(code);
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
