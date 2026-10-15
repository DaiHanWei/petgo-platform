package com.tailtopia.admin.opspopup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.ops.AdminOpsTargetValidator;
import com.tailtopia.admin.opspopup.dto.OpsPopupForm;
import com.tailtopia.ops.popup.domain.OpsPopup;
import com.tailtopia.ops.popup.domain.OpsPopupAudience;
import com.tailtopia.ops.popup.domain.OpsPopupFrequency;
import com.tailtopia.ops.popup.domain.OpsPopupStatus;
import com.tailtopia.ops.popup.repository.OpsPopupImpressionRepository;
import com.tailtopia.ops.popup.repository.OpsPopupRepository;
import com.tailtopia.ops.target.OpsTargetPolicy;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.repository.ShopProductRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * L0：运营弹窗后台校验（V1.3.4 AB-30，spec I/O 矩阵各校验行）。
 *
 * <p>跳转目标用<b>真实的</b> {@link OpsTargetPolicy}（只 mock 商品仓储）：本类要钉的正是「后台走的是同一份规则」，
 * mock 掉它就什么也没测。
 */
class AdminOpsPopupServiceTest {

    private OpsPopupRepository popups;
    private OpsPopupImpressionRepository impressions;
    private ShopProductRepository products;
    private AdminAuditService audit;
    private AdminOpsPopupService service;

    @BeforeEach
    void setUp() {
        popups = mock(OpsPopupRepository.class);
        impressions = mock(OpsPopupImpressionRepository.class);
        products = mock(ShopProductRepository.class);
        audit = mock(AdminAuditService.class);
        when(popups.save(any(OpsPopup.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AdminOpsPopupService(popups, impressions, new AdminOpsTargetValidator(new OpsTargetPolicy(products)), audit);
    }

    /** 一份必填齐全的合法表单（调用方覆盖某项构造违规用例）。 */
    private static OpsPopupForm valid(Consumer<OpsPopupForm> tweak) {
        OpsPopupForm f = new OpsPopupForm();
        f.setName("  双十一  ");
        f.setPriority("5");
        f.setStartsAt("2026-11-01T08:00");
        f.setEndsAt("2026-11-12T00:00");
        f.setAudience("ALL");
        f.setFrequency("ONCE");
        f.setImageUrlId("prod/public/ops-popup/a.jpg");
        f.setImageUrlEn("   ");
        f.setTitleId("Judul");
        f.setTitleEn("");
        f.setBodyId(" ");
        f.setButtonTextId("Lihat");
        f.setButtonTextEn("See");
        f.setTargetType("APP_PAGE");
        f.setTargetValue("KNOW_YOUR_PET");
        tweak.accept(f);
        return f;
    }

    private void assertRejected(Consumer<OpsPopupForm> tweak, String code) {
        assertThatThrownBy(() -> service.create(valid(tweak), 1L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    assertThat(((AppException) e).getMessageCode()).isEqualTo(code);
                    assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        verify(popups, never()).save(any());
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("新建：直接 ACTIVE（§0-1）、WIB→UTC、空白串归一为 null、trim、记审计 CREATED（target=token）")
    void createNormalizesAndAudits() {
        OpsPopup p = service.create(valid(f -> { }), 7L);

        assertThat(p.getStatus()).as("新建直接生效，按时间进入未开始 / 进行中").isEqualTo(OpsPopupStatus.ACTIVE);
        assertThat(p.getName()).isEqualTo("双十一");
        // 2026-11-01 08:00 WIB = 01:00 UTC
        assertThat(p.getStartsAt()).isEqualTo(Instant.parse("2026-11-01T01:00:00Z"));
        assertThat(p.getEndsAt()).isEqualTo(Instant.parse("2026-11-11T17:00:00Z"));
        assertThat(p.getPriority()).isEqualTo(5);
        assertThat(p.getNewUserDays()).isEqualTo(7);
        assertThat(p.getImageUrlEn()).isNull();
        assertThat(p.getTitleEn()).isNull();
        assertThat(p.getBodyId()).isNull();
        assertThat(p.getButtonTextEn()).isEqualTo("See");
        assertThat(p.getTargetType()).isEqualTo(OpsTargetType.APP_PAGE);
        assertThat(p.getTargetValue()).isEqualTo("KNOW_YOUR_PET");
        verify(audit).record(eq(7L), eq(AuditActions.OPS_POPUP_CREATED), eq("OPS_POPUP"),
                eq(p.getPublicToken()), anyString());
    }

    @Test
    @DisplayName("编辑：id / token / 状态不变；人群为全部时天数不校验、沿用原值；记审计 UPDATED")
    void updateKeepsTokenStatusAndDays() {
        // 原开始时间在未来（未开始）：已开始的弹窗开始时间改不到未来，见 startLockedOnceStarted
        OpsPopup existing = OpsPopup.create("old", Instant.now().plusSeconds(86_400), null, 0,
                OpsPopupAudience.NEW, 30, OpsPopupFrequency.DAILY, "k.jpg", null, null, null, null, null,
                "Lihat", null, OpsTargetType.NONE, null);
        existing.activate();
        String token = existing.getPublicToken();
        when(popups.findByPublicToken(token)).thenReturn(Optional.of(existing));

        // 人群改成「全部」：N 无意义，不校验、沿用原值（不冲成默认 7）
        OpsPopup p = service.update(token, valid(f -> f.setNewUserDays("abc")), 3L);

        assertThat(p).isSameAs(existing);
        assertThat(p.getPublicToken()).isEqualTo(token);
        assertThat(p.getStatus()).as("编辑不改启停").isEqualTo(OpsPopupStatus.ACTIVE);
        assertThat(p.getNewUserDays()).isEqualTo(30);
        assertThat(p.getName()).isEqualTo("双十一");
        verify(audit).record(eq(3L), eq(AuditActions.OPS_POPUP_UPDATED), eq("OPS_POPUP"), eq(token), anyString());
    }

    @Test
    @DisplayName("启用 / 停用：状态切换 + 审计；不存在的 token → 404")
    void activateDeactivateAndNotFound() {
        OpsPopup p = OpsPopup.create("n", Instant.now(), null, 0, OpsPopupAudience.ALL, 7,
                OpsPopupFrequency.ONCE, "k.jpg", null, null, null, null, null, "B", null, OpsTargetType.NONE, null);
        when(popups.findByPublicToken(p.getPublicToken())).thenReturn(Optional.of(p));

        service.activate(p.getPublicToken(), 1L);
        assertThat(p.getStatus()).isEqualTo(OpsPopupStatus.ACTIVE);
        verify(audit).record(eq(1L), eq(AuditActions.OPS_POPUP_ACTIVATED), eq("OPS_POPUP"),
                eq(p.getPublicToken()), anyString());

        service.deactivate(p.getPublicToken(), 1L);
        assertThat(p.getStatus()).isEqualTo(OpsPopupStatus.DISABLED);
        verify(audit).record(eq(1L), eq(AuditActions.OPS_POPUP_DEACTIVATED), eq("OPS_POPUP"),
                eq(p.getPublicToken()), anyString());

        when(popups.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activate("nope", 1L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("缺必填（除标题正文外全必填，§3）：名称 / 图片（两张都没有）/ EN 或 ID 按钮 / 开始 / 结束 / 跳转类型 / 频次 / 人群 / 优先级")
    void missingRequired() {
        assertRejected(f -> f.setName("   "), "admin.err.opsPopup.nameRequired");
        assertRejected(f -> { f.setImageUrlId(""); f.setImageUrlEn(null); }, "admin.err.opsPopup.imageRequired");
        assertRejected(f -> f.setButtonTextId(null), "admin.err.opsPopup.buttonRequired");
        assertRejected(f -> f.setButtonTextEn(" "), "admin.err.opsPopup.buttonRequired");
        assertRejected(f -> f.setStartsAt(""), "admin.err.opsPopup.startsAtRequired");
        assertRejected(f -> f.setEndsAt(""), "admin.err.opsPopup.endsAtRequired");
        assertRejected(f -> { f.setTargetType(""); f.setTargetValue(null); }, "admin.err.opsPopup.targetTypeRequired");
        assertRejected(f -> f.setFrequency(null), "admin.err.opsPopup.frequencyRequired");
        assertRejected(f -> f.setAudience(""), "admin.err.opsPopup.audienceRequired");
        assertRejected(f -> f.setPriority(""), "admin.err.opsPopup.priorityRequired");
    }

    @Test
    @DisplayName("H7：全部字段错误一次收集（按表单顺序），汇总按槽计数；标红名单只含缺的那个按钮")
    void collectsAllErrorsAtOnce() {
        OpsPopupForm f = valid(x -> {
            x.setButtonTextEn("");
            x.setTargetType("");
            x.setTargetValue(null);
            x.setEndsAt("2026-11-01T07:00");
            x.setTitleEn("t".repeat(121));
            x.setTitleId("t".repeat(121));
        });
        assertThatThrownBy(() -> service.create(f, 1L))
                .isInstanceOfSatisfying(OpsPopupValidationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.errors()).extracting(OpsPopupValidationException.FieldError::code).containsExactly(
                            "admin.err.opsPopup.titleEnTooLong", "admin.err.opsPopup.titleIdTooLong",
                            "admin.err.opsPopup.buttonRequired", "admin.err.opsPopup.targetTypeRequired",
                            "admin.err.opsPopup.window");
                    assertThat(e.getMessageCode()).as("单码调用方拿到第一条").isEqualTo("admin.err.opsPopup.titleEnTooLong");
                    assertThat(e.slotCount()).as("标题两条算一项").isEqualTo(4);
                    assertThat(e.errors().get(2).inputs()).containsExactly("buttonTextEn");
                    assertThat(e.errors().get(4).inputs()).containsExactly("startsAt", "endsAt");
                });
        verify(popups, never()).save(any());

        // 空表单：必填全报（标题 / 正文选填不报；开始、结束各一条）
        assertThatThrownBy(() -> service.create(new OpsPopupForm(), 1L))
                .isInstanceOfSatisfying(OpsPopupValidationException.class, e -> assertThat(e.errors())
                        .extracting(OpsPopupValidationException.FieldError::slot).containsExactly(
                                "name", "image", "button", "target", "window", "window", "frequency", "audience",
                                "priority"));
    }

    @Test
    @DisplayName("图片：只传 EN → 存进 image_url_id、image_url_en 留空（两语共用）；只传 ID / 两张都传原样")
    void enOnlyImageGoesToIdColumn() {
        OpsPopup en = service.create(valid(f -> { f.setImageUrlId(""); f.setImageUrlEn(" https://cdn.example/en.jpg "); }), 1L);
        assertThat(en.getImageUrlId()).isEqualTo("https://cdn.example/en.jpg");
        assertThat(en.getImageUrlEn()).isNull();

        OpsPopup both = service.create(valid(f -> f.setImageUrlEn("k/en.jpg")), 1L);
        assertThat(both.getImageUrlId()).isEqualTo("prod/public/ops-popup/a.jpg");
        assertThat(both.getImageUrlEn()).isEqualTo("k/en.jpg");
    }

    @Test
    @DisplayName("时间窗非法：结束 ≤ 开始、格式错")
    void badWindow() {
        assertRejected(f -> f.setEndsAt("2026-11-01T08:00"), "admin.err.opsPopup.window");
        assertRejected(f -> { f.setStartsAt("bad"); f.setEndsAt("bad"); }, "admin.err.opsPopup.startsAtFormat");
        assertRejected(f -> f.setEndsAt("2026-10-31T08:00"), "admin.err.opsPopup.window");
        assertRejected(f -> f.setStartsAt("2026/11/01 08:00"), "admin.err.opsPopup.startsAtFormat");
        assertRejected(f -> f.setEndsAt("2026-13-01T08:00"), "admin.err.opsPopup.endsAtFormat");
    }

    @Test
    @DisplayName("超长：名称 101、标题 121、正文 256、按钮 41（按字符数，emoji 算 1 个）")
    void tooLong() {
        assertRejected(f -> f.setName("n".repeat(101)), "admin.err.opsPopup.nameTooLong");
        assertRejected(f -> f.setTitleId("t".repeat(121)), "admin.err.opsPopup.titleIdTooLong");
        assertRejected(f -> f.setTitleEn("t".repeat(121)), "admin.err.opsPopup.titleEnTooLong");
        assertRejected(f -> f.setBodyId("b".repeat(256)), "admin.err.opsPopup.bodyIdTooLong");
        assertRejected(f -> f.setBodyEn("b".repeat(256)), "admin.err.opsPopup.bodyEnTooLong");
        assertRejected(f -> f.setButtonTextId("x".repeat(41)), "admin.err.opsPopup.buttonIdTooLong");
        assertRejected(f -> f.setButtonTextEn("x".repeat(41)), "admin.err.opsPopup.buttonEnTooLong");
        // 边界上的合法值：120 个 emoji = 120 字符（UTF-16 是 240 个 char，按 length() 判会误拒）
        OpsPopup ok = service.create(valid(f -> f.setTitleId("🐶".repeat(120))), 1L);
        assertThat(ok.getTitleId()).hasSize(240);
    }

    @Test
    @DisplayName("枚举 / 数字字段：优先级非整数、天数 ≤0、人群 / 频次 / 跳转类型非法")
    void badEnumsAndNumbers() {
        assertRejected(f -> f.setPriority("1.5"), "admin.err.opsPopup.priority");
        assertRejected(f -> f.setPriority("100001"), "admin.err.opsPopup.priority");
        assertRejected(f -> f.setPriority("-100001"), "admin.err.opsPopup.priority");
        assertRejected(f -> f.setPriority("99999999999"), "admin.err.opsPopup.priority");
        assertRejected(f -> { f.setAudience("NEW"); f.setNewUserDays("3651"); }, "admin.err.opsPopup.newUserDays");
        assertRejected(f -> { f.setAudience("NEW"); f.setNewUserDays("0"); }, "admin.err.opsPopup.newUserDays");
        assertRejected(f -> { f.setAudience("OLD"); f.setNewUserDays("abc"); }, "admin.err.opsPopup.newUserDays");
        assertRejected(f -> f.setAudience("VIP"), "admin.err.opsPopup.audience");
        assertRejected(f -> f.setFrequency("WEEKLY"), "admin.err.opsPopup.frequency");
        assertRejected(f -> f.setTargetType("DEEPLINK"), "admin.err.opsPopup.targetType");
        assertRejected(f -> f.setPriority("abc"), "admin.err.opsPopup.priority");
    }

    @Test
    @DisplayName("图片：http 明文拒、https 合法、objectKey 合法、超 512 拒")
    void images() {
        assertRejected(f -> f.setImageUrlId("http://cdn.example/a.jpg"), "admin.err.opsPopup.imageIdInvalid");
        assertRejected(f -> f.setImageUrlEn("http://cdn.example/a.jpg"), "admin.err.opsPopup.imageEnInvalid");
        assertRejected(f -> f.setImageUrlId("k/" + "a".repeat(511)), "admin.err.opsPopup.imageIdInvalid");
        assertThat(service.create(valid(f -> f.setImageUrlId("https://cdn.example/a.jpg")), 1L).getImageUrlId())
                .isEqualTo("https://cdn.example/a.jpg");
    }

    @Test
    @DisplayName("跳转值：EXTERNAL 非 http(s)、PRODUCT 不存在或已下架、NONE 带值、CATEGORY / APP_PAGE 非枚举")
    void targets() {
        assertRejected(f -> { f.setTargetType("EXTERNAL"); f.setTargetValue("javascript:alert(1)"); },
                "admin.err.opsPopup.targetExternal");
        assertRejected(f -> { f.setTargetType("EXTERNAL"); f.setTargetValue(""); },
                "admin.err.opsPopup.targetExternal");
        assertRejected(f -> { f.setTargetType("NONE"); f.setTargetValue("x"); },
                "admin.err.opsPopup.targetNoneWithValue");
        assertRejected(f -> { f.setTargetType("CATEGORY"); f.setTargetValue("makanan"); },
                "admin.err.opsPopup.targetCategory");
        assertRejected(f -> { f.setTargetType("APP_PAGE"); f.setTargetValue("SETTINGS"); },
                "admin.err.opsPopup.targetAppPage");
        assertRejected(f -> { f.setTargetType("PRODUCT"); f.setTargetValue("bad token!"); },
                "admin.err.opsPopup.targetProduct");

        when(products.findByPublicTokenAndActiveTrue("gone")).thenReturn(Optional.empty());
        assertRejected(f -> { f.setTargetType("PRODUCT"); f.setTargetValue("gone"); },
                "admin.err.opsPopup.productUnavailable");

        when(products.findByPublicTokenAndActiveTrue("live1")).thenReturn(Optional.of(mock(ShopProduct.class)));
        OpsPopup p = service.create(valid(f -> { f.setTargetType("PRODUCT"); f.setTargetValue(" live1 "); }), 1L);
        assertThat(p.getTargetValue()).isEqualTo("live1");

        // NONE 且无值（前端隐藏 + 禁用不提交）→ 合法，value 落 null
        OpsPopup none = service.create(valid(f -> { f.setTargetType("NONE"); f.setTargetValue(null); }), 1L);
        assertThat(none.getTargetValue()).isNull();
    }

    @Test
    @DisplayName("数字上下界：优先级 ±100000、天数 3650 恰好合法")
    void numberBoundsInclusive() {
        assertThat(service.create(valid(f -> f.setPriority("100000")), 1L).getPriority()).isEqualTo(100_000);
        assertThat(service.create(valid(f -> f.setPriority("-100000")), 1L).getPriority()).isEqualTo(-100_000);
        assertThat(service.create(valid(f -> { f.setAudience("NEW"); f.setNewUserDays("3650"); }), 1L)
                .getNewUserDays()).isEqualTo(3650);
    }

    @Test
    @DisplayName("编辑：跳转目标未改时不问在售（商品临时下架仍可改文案）；改了目标才要求新商品在售")
    void updateSkipsAvailabilityWhenTargetUnchanged() {
        OpsPopup existing = OpsPopup.create("old", Instant.now().plusSeconds(86_400), null, 0,
                OpsPopupAudience.ALL, 7, OpsPopupFrequency.ONCE, "k.jpg", null, null, null, null, null,
                "Lihat", null, OpsTargetType.PRODUCT, "offsale1");
        String token = existing.getPublicToken();
        when(popups.findByPublicToken(token)).thenReturn(Optional.of(existing));
        when(products.findByPublicTokenAndActiveTrue(anyString())).thenReturn(Optional.empty());

        // 目标不变（含首尾空白，归一后相同）→ 保存成功，且根本不查商品
        OpsPopup p = service.update(token, valid(f -> {
            f.setName("新文案");
            f.setTargetType("PRODUCT");
            f.setTargetValue(" offsale1 ");
        }), 1L);
        assertThat(p.getName()).isEqualTo("新文案");
        assertThat(p.getTargetValue()).isEqualTo("offsale1");
        verify(products, never()).findByPublicTokenAndActiveTrue(anyString());

        // 换成另一个不在售的商品 → 拒
        assertThatThrownBy(() -> service.update(token, valid(f -> {
            f.setTargetType("PRODUCT");
            f.setTargetValue("offsale2");
        }), 1L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode())
                        .isEqualTo("admin.err.opsPopup.productUnavailable"));

        // 格式校验仍恒做：目标没改也照样拦坏 token（库里不该有，但不能因「没改」放行）
        OpsPopup bad = OpsPopup.create("bad", Instant.now().plusSeconds(86_400), null, 0,
                OpsPopupAudience.ALL, 7, OpsPopupFrequency.ONCE, "k.jpg", null, null, null, null, null,
                "Lihat", null, OpsTargetType.PRODUCT, "bad token!");
        when(popups.findByPublicToken(bad.getPublicToken())).thenReturn(Optional.of(bad));
        assertThatThrownBy(() -> service.update(bad.getPublicToken(), valid(f -> {
            f.setTargetType("PRODUCT");
            f.setTargetValue("bad token!");
        }), 1L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode())
                        .isEqualTo("admin.err.opsPopup.targetProduct"));
    }

    /** 已存在的弹窗（反射补 id：mock 仓储不会替我们落库生成）。 */
    private OpsPopup stored(long id, String name, Instant start, Instant end, boolean active) {
        OpsPopup p = OpsPopup.create(name, start, end, 0, OpsPopupAudience.ALL, 7, OpsPopupFrequency.ONCE,
                "k.jpg", null, null, null, null, null, "B", "B", OpsTargetType.NONE, null);
        if (active) {
            p.activate();
        }
        try {
            java.lang.reflect.Field f = OpsPopup.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        when(popups.findByPublicToken(p.getPublicToken())).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("删除（§0-2）：只有 启用 + 未到开始 + 无曝光 可删并记审计 DELETED；有曝光 / 已停用未开始 / 已开始 → 409")
    void deleteOnlyPending() {
        Instant future = Instant.now().plusSeconds(86_400);
        Instant past = Instant.now().minusSeconds(86_400);

        OpsPopup pending = stored(1L, "p", future, future.plusSeconds(3600), true);
        when(impressions.existsByPopupId(1L)).thenReturn(false);
        service.delete(pending.getPublicToken(), 9L);
        verify(popups).delete(pending);
        verify(audit).record(eq(9L), eq(AuditActions.OPS_POPUP_DELETED), eq("OPS_POPUP"),
                eq(pending.getPublicToken()), anyString());

        OpsPopup seen = stored(2L, "seen", future, future.plusSeconds(3600), true);
        when(impressions.existsByPopupId(2L)).thenReturn(true);
        OpsPopup disabled = stored(3L, "off", future, future.plusSeconds(3600), false);
        OpsPopup started = stored(4L, "live", past, future, true);
        for (OpsPopup p : java.util.List.of(seen, disabled, started)) {
            assertThatThrownBy(() -> service.delete(p.getPublicToken(), 9L))
                    .isInstanceOf(AppException.class)
                    .satisfies(e -> {
                        assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(((AppException) e).getMessageCode()).isEqualTo("admin.err.opsPopup.deleteNotPending");
                    });
            verify(popups, never()).delete(p);
        }
        verify(audit, org.mockito.Mockito.times(1)).record(any(), eq(AuditActions.OPS_POPUP_DELETED), anyString(),
                anyString(), anyString());
    }

    /** Instant → 表单 datetime-local（WIB，分钟精度）。 */
    private static String wib(Instant t) {
        return com.tailtopia.shared.schedule.ScheduleWindow.toWib(t)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    }

    @Test
    @DisplayName("开始时间锁：已开始过的弹窗不能把开始时间改到未来（startLocked，挂 startsAt）；改到过去 / 原值可以；未开始的照常可改；删除仍只许未开始")
    void startLockedOnceStarted() {
        Instant past = Instant.now().minusSeconds(3 * 86_400);
        Instant future = Instant.now().plusSeconds(2 * 86_400);
        Instant end = Instant.now().plusSeconds(30 * 86_400);

        OpsPopup live = stored(5L, "live", past, end, true);
        assertThatThrownBy(() -> service.update(live.getPublicToken(), valid(f -> {
            f.setStartsAt(wib(future));
            f.setEndsAt(wib(end));
        }), 1L)).isInstanceOf(OpsPopupValidationException.class)
                .satisfies(e -> {
                    OpsPopupValidationException ve = (OpsPopupValidationException) e;
                    assertThat(ve.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ve.errors()).singleElement().satisfies(fe -> {
                        assertThat(fe.code()).isEqualTo("admin.err.opsPopup.startLocked");
                        assertThat(fe.inputs()).containsExactly("startsAt");
                    });
                });
        verify(popups, never()).save(live);
        assertThat(live.getStartsAt()).isEqualTo(past);
        // 删除仍拒：开始时间没被挪走，仍是「已开始」
        when(impressions.existsByPopupId(5L)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(live.getPublicToken(), 9L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode())
                        .isEqualTo("admin.err.opsPopup.deleteNotPending"));

        // 已开始 → 改文案、开始时间改到更早的过去：照常保存
        Instant earlier = past.minusSeconds(86_400);
        service.update(live.getPublicToken(), valid(f -> {
            f.setName("改文案");
            f.setStartsAt(wib(earlier));
            f.setEndsAt(wib(end));
        }), 1L);
        assertThat(live.getName()).isEqualTo("改文案");
        assertThat(live.getStartsAt()).isBefore(Instant.now());

        // 已停用但开始时间已过（开始过）→ 同样锁
        OpsPopup stopped = stored(6L, "stopped", past, end, false);
        assertThatThrownBy(() -> service.update(stopped.getPublicToken(), valid(f -> {
            f.setStartsAt(wib(future));
            f.setEndsAt(wib(end));
        }), 1L)).isInstanceOf(OpsPopupValidationException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode())
                        .isEqualTo("admin.err.opsPopup.startLocked"));

        // 未开始 → 开始时间可改到另一个未来时刻，且仍可删
        OpsPopup pending = stored(7L, "pending", future, end, true);
        Instant later = future.plusSeconds(86_400);
        service.update(pending.getPublicToken(), valid(f -> {
            f.setStartsAt(wib(later));
            f.setEndsAt(wib(end));
        }), 1L);
        assertThat(pending.getStartsAt()).isAfter(Instant.now());
        when(impressions.existsByPopupId(7L)).thenReturn(false);
        service.delete(pending.getPublicToken(), 9L);
        verify(popups).delete(pending);
    }

    @Test
    @DisplayName("列表（H1）：合一状态（停用 → 已停用，否则按时间窗）、状态筛选、名称搜索（不区分大小写）、四格计数不受筛选影响")
    void listFilterSearchAndCounts() {
        Instant now = Instant.now();
        OpsPopup running = stored(1L, "Weekend Cat 8折", now.minusSeconds(60), now.plusSeconds(3600), true);
        OpsPopup pending = stored(2L, "新人发帖引导", now.plusSeconds(3600), now.plusSeconds(7200), true);
        OpsPopup ended = stored(3L, "KTP 新卡面", now.minusSeconds(7200), now.minusSeconds(3600), true);
        OpsPopup off = stored(4L, "测试弹窗 cat", now.minusSeconds(60), now.plusSeconds(3600), false);
        when(popups.findAllByOrderByPriorityDescCreatedAtDescIdDesc()).thenReturn(java.util.List.of(running, pending, ended, off));

        assertThat(OpsPopupState.of(running, now)).isEqualTo(OpsPopupState.RUNNING);
        assertThat(OpsPopupState.of(pending, now)).isEqualTo(OpsPopupState.PENDING);
        assertThat(OpsPopupState.of(ended, now)).isEqualTo(OpsPopupState.ENDED);
        assertThat(OpsPopupState.of(off, now)).as("停用优先于时间阶段").isEqualTo(OpsPopupState.DISABLED);

        assertThat(service.list(null, null, now)).hasSize(4);
        assertThat(service.list(OpsPopupState.PENDING, " ", now)).containsExactly(pending);
        assertThat(service.list(null, "CAT", now)).containsExactly(running, off);
        assertThat(service.list(OpsPopupState.DISABLED, "cat", now)).containsExactly(off);
        assertThat(service.list(OpsPopupState.parseOrNull("BOGUS"), null, now)).as("非法筛选值 = 不筛").hasSize(4);
        assertThat(service.counts(now)).containsEntry(OpsPopupState.RUNNING, 1L).containsEntry(OpsPopupState.PENDING, 1L)
                .containsEntry(OpsPopupState.ENDED, 1L).containsEntry(OpsPopupState.DISABLED, 1L);
    }
}
