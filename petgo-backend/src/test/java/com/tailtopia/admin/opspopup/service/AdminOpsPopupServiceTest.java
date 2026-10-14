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
    private ShopProductRepository products;
    private AdminAuditService audit;
    private AdminOpsPopupService service;

    @BeforeEach
    void setUp() {
        popups = mock(OpsPopupRepository.class);
        products = mock(ShopProductRepository.class);
        audit = mock(AdminAuditService.class);
        when(popups.save(any(OpsPopup.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AdminOpsPopupService(popups, new AdminOpsTargetValidator(new OpsTargetPolicy(products)), audit);
    }

    /** 一份必填齐全的合法表单（调用方覆盖某项构造违规用例）。 */
    private static OpsPopupForm valid(Consumer<OpsPopupForm> tweak) {
        OpsPopupForm f = new OpsPopupForm();
        f.setName("  双十一  ");
        f.setPriority("5");
        f.setStartsAt("2026-11-01T08:00");
        f.setEndsAt("");
        f.setAudience("ALL");
        f.setFrequency("ONCE");
        f.setImageUrlId("prod/public/ops-popup/a.jpg");
        f.setImageUrlEn("   ");
        f.setTitleId("Judul");
        f.setTitleEn("");
        f.setBodyId(" ");
        f.setButtonTextId("Lihat");
        f.setButtonTextEn("");
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
    @DisplayName("新建：DISABLED、WIB→UTC、空白串归一为 null、trim、记审计 CREATED（target=token）")
    void createNormalizesAndAudits() {
        OpsPopup p = service.create(valid(f -> { }), 7L);

        assertThat(p.getStatus()).isEqualTo(OpsPopupStatus.DISABLED);
        assertThat(p.getName()).isEqualTo("双十一");
        // 2026-11-01 08:00 WIB = 01:00 UTC
        assertThat(p.getStartsAt()).isEqualTo(Instant.parse("2026-11-01T01:00:00Z"));
        assertThat(p.getEndsAt()).isNull();
        assertThat(p.getPriority()).isEqualTo(5);
        assertThat(p.getNewUserDays()).isEqualTo(7);
        assertThat(p.getImageUrlEn()).isNull();
        assertThat(p.getTitleEn()).isNull();
        assertThat(p.getBodyId()).isNull();
        assertThat(p.getButtonTextEn()).isNull();
        assertThat(p.getTargetType()).isEqualTo(OpsTargetType.APP_PAGE);
        assertThat(p.getTargetValue()).isEqualTo("KNOW_YOUR_PET");
        verify(audit).record(eq(7L), eq(AuditActions.OPS_POPUP_CREATED), eq("OPS_POPUP"),
                eq(p.getPublicToken()), anyString());
    }

    @Test
    @DisplayName("编辑：id / token / 状态不变；人群为全部（天数未提交）时沿用原天数；记审计 UPDATED")
    void updateKeepsTokenStatusAndDays() {
        OpsPopup existing = OpsPopup.create("old", Instant.parse("2026-10-01T00:00:00Z"), null, 0,
                OpsPopupAudience.NEW, 30, OpsPopupFrequency.DAILY, "k.jpg", null, null, null, null, null,
                "Lihat", null, OpsTargetType.NONE, null);
        existing.activate();
        String token = existing.getPublicToken();
        when(popups.findByPublicToken(token)).thenReturn(Optional.of(existing));

        OpsPopup p = service.update(token, valid(f -> f.setNewUserDays(null)), 3L);

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
    @DisplayName("缺必填：名称 / 印尼文图 / 印尼文按钮 / 开始时间")
    void missingRequired() {
        assertRejected(f -> f.setName("   "), "admin.err.opsPopup.nameRequired");
        assertRejected(f -> f.setImageUrlId(""), "admin.err.opsPopup.imageIdRequired");
        assertRejected(f -> f.setButtonTextId(null), "admin.err.opsPopup.buttonIdRequired");
        assertRejected(f -> f.setStartsAt(""), "admin.err.opsPopup.startsAtRequired");
    }

    @Test
    @DisplayName("时间窗非法：结束 ≤ 开始、格式错")
    void badWindow() {
        assertRejected(f -> f.setEndsAt("2026-11-01T08:00"), "admin.err.opsPopup.window");
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
        OpsPopup existing = OpsPopup.create("old", Instant.parse("2026-10-01T00:00:00Z"), null, 0,
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
        OpsPopup bad = OpsPopup.create("bad", Instant.parse("2026-10-01T00:00:00Z"), null, 0,
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
}
