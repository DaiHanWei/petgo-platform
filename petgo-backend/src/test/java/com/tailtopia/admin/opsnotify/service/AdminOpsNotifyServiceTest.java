package com.tailtopia.admin.opsnotify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.ops.AdminOpsTargetValidator;
import com.tailtopia.admin.opsnotify.dto.OpsNotifyForm;
import com.tailtopia.auth.service.OpsAudienceQuery;
import com.tailtopia.auth.service.OpsAudienceQuery.IdStatus;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignReadyEvent;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.ops.service.OpsNotificationCampaignService;
import com.tailtopia.ops.OpsTokenGenerator;
import com.tailtopia.ops.target.OpsTargetPolicy;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.repository.ShopProductRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：后台「系统通知发送」（V1.3.4 AB-31；后台返工清单 §4 · UI 稿 I3–I8）——四步页的服务端全验（按步骤先后报错）、
 * 相对时间预设、物种单选、指定 ID 的宽松解析与按原因归类、大范围手输确认、预估闸、一次性提交键。
 *
 * <p>发送核心 {@link OpsNotificationCampaignService} 与跳转校验 {@link AdminOpsTargetValidator} 都用<b>真实的</b>
 * （只 mock 仓储 / DAO / 事件 / 人群查询）：本类要钉的正是「后台表单错误最终挂上的码」。
 */
class AdminOpsNotifyServiceTest {

    /** 固定「现在」：2026-10-15 10:00 WIB。 */
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");

    private OpsNotificationCampaignRepository campaigns;
    private OpsCampaignTargetDao targetsDao;
    private ApplicationEventPublisher events;
    private OpsAudienceQuery audienceQuery;
    private AdminAuditService audit;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> redisOps;
    private AdminOpsNotifyService service;

    @BeforeEach
    void setUp() {
        campaigns = mock(OpsNotificationCampaignRepository.class);
        targetsDao = mock(OpsCampaignTargetDao.class);
        ShopProductRepository products = mock(ShopProductRepository.class);
        events = mock(ApplicationEventPublisher.class);
        audienceQuery = mock(OpsAudienceQuery.class);
        audit = mock(AdminAuditService.class);
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        redisOps = ops;
        when(redis.opsForValue()).thenReturn(redisOps);
        when(redisOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        // 预估闸默认放行：重算 = 表单里的 previewCount（5）
        when(audienceQuery.count(any(), any())).thenReturn(5L);
        OpsTargetPolicy policy = new OpsTargetPolicy(products);
        OpsTokenGenerator tokens = mock(OpsTokenGenerator.class);
        when(tokens.generate()).thenReturn("tok22charsAbcdefghijkl");
        when(campaigns.save(any(OpsNotificationCampaign.class))).thenAnswer(inv -> {
            OpsNotificationCampaign c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", 99L);
            return c;
        });
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OpsNotificationCampaignService core = new OpsNotificationCampaignService(campaigns, targetsDao, policy,
                tokens, events, clock);
        service = new AdminOpsNotifyService(core, campaigns, audienceQuery, new AdminOpsTargetValidator(policy),
                audit, redis, clock);
    }

    /**
     * 一份合法表单：四项文案齐、按条件（有手机号）、App 内页面、立即发送；调用方覆盖某项构造违规用例。
     * ⚠️ 至少带一个条件：「按条件」一个都不选 = 全部用户，恒要手输人数（I8），会把无关用例都卡在弹层上。
     */
    private static OpsNotifyForm valid(Consumer<OpsNotifyForm> tweak) {
        OpsNotifyForm f = new OpsNotifyForm();
        f.setTitleId("  Promo akhir pekan ");
        f.setTitleEn("Weekend promo");
        f.setBodyId("Isi promo");
        f.setBodyEn("Promo body");
        f.setAudienceMode("FILTER");
        f.setHasPhone("true");
        f.setTargetType("APP_PAGE");
        f.setTargetValue("TOKO");
        f.setSendMode("NOW");
        f.setPreviewCount("5");
        f.setSubmitKey("0f8c2a4e-1b2c-4d5e-8f90-123456789abc");
        tweak.accept(f);
        return f;
    }

    private OpsNotificationCampaign saved() {
        ArgumentCaptor<OpsNotificationCampaign> c = ArgumentCaptor.forClass(OpsNotificationCampaign.class);
        verify(campaigns).save(c.capture());
        return c.getValue();
    }

    private void assertRejected(Consumer<OpsNotifyForm> tweak, String code) {
        assertThatThrownBy(() -> service.create(valid(tweak), 1L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    assertThat(((AppException) e).getMessageCode()).isEqualTo(code);
                    assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        verify(campaigns, never()).save(any());
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString());
    }

    // ---------- 新建 ----------

    @Test
    @DisplayName("立即 · 指定 ID（粘贴）：数字 ID 去重写子表、非数字行不报错、提交后发事件；estimated_count / ids_source 落库；审计不写 ID")
    void createIdsImmediate() {
        AdminOpsNotifyService.Created c = service.create(valid(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds("12\n12\nabc\n 34 \n");
            f.setIdsSource("PASTE");
        }), 7L);
        assertThat(c.token()).isEqualTo("tok22charsAbcdefghijkl");
        assertThat(c.scheduledAt()).isNull();
        OpsNotificationCampaign s = saved();
        assertThat(s.getTitleId()).isEqualTo("Promo akhir pekan");
        assertThat(s.getEstimatedCount()).isEqualTo(5);
        assertThat(s.getIdsSource()).isEqualTo("PASTE");
        assertThat(AudienceSpec.parse(s.getAudienceSpec()).mode()).isEqualTo(AudienceSpec.Mode.IDS);
        verify(targetsDao).insertAll(99L, List.of(12L, 34L));
        verify(events).publishEvent(any(OpsCampaignReadyEvent.class));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(7L), eq(AuditActions.OPS_NOTIFY_CREATED), eq("OPS_NOTIFY"),
                eq("tok22charsAbcdefghijkl"), detail.capture());
        assertThat(detail.getValue()).contains("IDS 2 个（PASTE）").contains("预计 5 人").contains("立即发送")
                .doesNotContain("12,").doesNotContain("34");
    }

    @Test
    @DisplayName("定时 · 指定 ID（CSV）：按 WIB 解释、不发事件、ids_source = CSV")
    void createScheduledCsv() {
        AdminOpsNotifyService.Created c = service.create(valid(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds("user_id\n101\n102");
            f.setIdsSource("CSV");
            f.setSendMode("SCHEDULED");
            f.setScheduledAt("2026-10-16T09:00");
        }), 1L);
        assertThat(c.scheduledAt()).isEqualTo(Instant.parse("2026-10-16T02:00:00Z"));
        OpsNotificationCampaign s = saved();
        assertThat(s.getScheduledAt()).isEqualTo(Instant.parse("2026-10-16T02:00:00Z"));
        assertThat(s.getIdsSource()).isEqualTo("CSV");
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("按条件：相对预设 → registeredWithinDays / activeWithinDays；物种单选；ids_source 不落（非 IDS）")
    void createFilterRelativePresets() {
        service.create(valid(f -> {
            f.setRegisteredWithin("7");
            f.setActiveWithin("30");
            f.setHasPet("true");
            f.setSpecies("CAT");
            f.setHasShopOrder("");
            f.setHasPhone("false");
            f.setIdsSource("CSV");
        }), 1L);
        OpsNotificationCampaign s = saved();
        AudienceSpec spec = AudienceSpec.parse(s.getAudienceSpec());
        assertThat(spec.registeredWithinDays()).isEqualTo(7);
        assertThat(spec.activeWithinDays()).isEqualTo(30);
        assertThat(spec.hasPet()).isTrue();
        assertThat(spec.species()).containsExactly(PetType.CAT);
        assertThat(spec.hasShopOrder()).isNull();
        assertThat(spec.hasPhone()).isFalse();
        assertThat(spec.registeredFrom()).isNull();
        assertThat(s.getIdsSource()).isNull();
    }

    @Test
    @DisplayName("按步骤先后报第一处错：① 文案 → ① 跳转 → ② 人群 → ③ 发送时间")
    void errorsInStepOrder() {
        // 文案和人群、时间同时错：先报文案
        assertRejected(f -> {
            f.setTitleEn(" ");
            f.setAudienceMode("");
            f.setSendMode("SCHEDULED");
        }, OpsNotificationCampaignService.ERR_TEXT_BLANK);
        // 跳转和时间同时错：先报跳转
        assertRejected(f -> {
            f.setTargetType("");
            f.setSendMode("SCHEDULED");
        }, AdminOpsNotifyService.ERR_TARGET_TYPE_REQUIRED);
        // 人群和时间同时错：先报人群
        assertRejected(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds("");
            f.setSendMode("SCHEDULED");
        }, OpsNotificationCampaignService.ERR_IDS_EMPTY);
    }

    @Test
    @DisplayName("① 内容：超长 / 外链非 https（AB-31 专属）/ 商品 token 格式")
    void contentErrors() {
        assertRejected(f -> f.setBodyEn("x".repeat(256)), OpsNotificationCampaignService.ERR_TEXT_TOO_LONG);
        assertRejected(f -> f.setTitleId("x".repeat(121)), OpsNotificationCampaignService.ERR_TEXT_TOO_LONG);
        assertRejected(f -> {
            f.setTargetType("EXTERNAL");
            f.setTargetValue("http://promo.example.com");
        }, AdminOpsNotifyService.ERR_TARGET_HTTPS);
        assertRejected(f -> {
            f.setTargetType("EXTERNAL");
            f.setTargetValue("");
        }, AdminOpsNotifyService.ERR_TARGET_HTTPS);
        assertRejected(f -> {
            f.setTargetType("PRODUCT");
            f.setTargetValue("");
        }, AdminOpsTargetValidator.ERR_PRODUCT);
    }

    @Test
    @DisplayName("① 外链 https 通过；「无跳转」时忽略残留的目标值")
    void httpsAndNoneAccepted() {
        service.create(valid(f -> {
            f.setTargetType("EXTERNAL");
            f.setTargetValue("https://promo.mitra-petshop.co.id/kucing");
        }), 1L);
        assertThat(saved().getTargetValue()).isEqualTo("https://promo.mitra-petshop.co.id/kucing");
    }

    @Test
    @DisplayName("② 人群：未选 / 非法预设 / 非法物种 → audience.invalid；有效 0 人 → audience-empty")
    void audienceErrors() {
        assertRejected(f -> f.setAudienceMode(""), OpsNotificationCampaignService.ERR_AUDIENCE_INVALID);
        assertRejected(f -> f.setRegisteredWithin("14"), OpsNotificationCampaignService.ERR_AUDIENCE_INVALID);
        assertRejected(f -> f.setActiveWithin("abc"), OpsNotificationCampaignService.ERR_AUDIENCE_INVALID);
        assertRejected(f -> f.setSpecies("BIRD"), OpsNotificationCampaignService.ERR_AUDIENCE_INVALID);
        assertRejected(f -> f.setHasPet("maybe"), OpsNotificationCampaignService.ERR_AUDIENCE_INVALID);
        // 全是非数字行：不报「非数字」，按有效 0 人拒发
        assertRejected(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds("abc\nxyz");
        }, AdminOpsNotifyService.ERR_AUDIENCE_EMPTY);
        when(audienceQuery.count(any(), any())).thenReturn(0L);
        assertRejected(f -> { }, AdminOpsNotifyService.ERR_AUDIENCE_EMPTY);
    }

    @Test
    @DisplayName("③ 发送时间：定时未填 → schedule.required；格式错 → schedule.format；不晚于现在 → schedule.past；立即忽略定时框")
    void scheduleErrors() {
        assertRejected(f -> f.setSendMode("SCHEDULED"), AdminOpsNotifyService.ERR_SCHEDULE_REQUIRED);
        assertRejected(f -> {
            f.setSendMode("SCHEDULED");
            f.setScheduledAt("2026/10/16 09:00");
        }, AdminOpsNotifyService.ERR_SCHEDULE_FORMAT);
        assertRejected(f -> {
            f.setSendMode("SCHEDULED");
            f.setScheduledAt("2026-10-15T10:00"); // = 现在（WIB），不算「晚于」
        }, OpsNotificationCampaignService.ERR_SCHEDULE_PAST);
        assertRejected(f -> f.setSendMode("LATER"), AdminOpsNotifyService.ERR_SCHEDULE_FORMAT);
        service.create(valid(f -> f.setScheduledAt("2020-01-01T00:00")), 1L);
        assertThat(saved().getScheduledAt()).isNull();
    }

    @Test
    @DisplayName("I8：全部用户 / 预计 > 1 万 → 缺 confirmCount 抛 TypedConfirmRequired（不认领提交键、不落库）；不等 → 422；相等 → 建成")
    void typedConfirmForLargeSends() {
        assertThatThrownBy(() -> service.create(valid(f -> f.setAudienceMode("ALL")), 1L))
                .isInstanceOf(OpsNotifyTypedConfirmRequired.class)
                .satisfies(e -> assertThat(((OpsNotifyTypedConfirmRequired) e).count()).isEqualTo(5L));
        verify(redisOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        assertRejected(f -> {
            f.setAudienceMode("ALL");
            f.setConfirmCount("4");
        }, AdminOpsNotifyService.ERR_CONFIRM_COUNT);

        // 按条件、预计 10,001 人 → 同样要手输；10,000 人不要
        when(audienceQuery.count(any(), any())).thenReturn(10_001L);
        assertThatThrownBy(() -> service.create(valid(f -> f.setPreviewCount("10001")), 1L))
                .isInstanceOf(OpsNotifyTypedConfirmRequired.class);
        when(audienceQuery.count(any(), any())).thenReturn(10_000L);
        service.create(valid(f -> f.setPreviewCount("10000")), 1L);
        assertThat(saved().getEstimatedCount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("I8：全部用户 + confirmCount 与确认页人数一致 → 建成（定时，不发事件）")
    void typedConfirmMatches() {
        service.create(valid(f -> {
            f.setAudienceMode("ALL");
            f.setConfirmCount("5");
            f.setSendMode("SCHEDULED");
            f.setScheduledAt("2026-10-16T09:00");
        }), 1L);
        assertThat(AudienceSpec.parse(saved().getAudienceSpec()).mode()).isEqualTo(AudienceSpec.Mode.ALL);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("needsTypedConfirm：ALL / 未选条件的 FILTER 恒要；其余 max(确认页, 重算) > 10,000 才要")
    void needsTypedConfirmThreshold() {
        AudienceSpec withPhone = new AudienceSpec(AudienceSpec.Mode.FILTER, null, null, null, null, null, null,
                null, true);
        AudienceSpec noFilter = new AudienceSpec(AudienceSpec.Mode.FILTER, null, null, null, null, null, null,
                null, null);
        assertThat(AdminOpsNotifyService.needsTypedConfirm(AudienceSpec.all(), 1, 1)).isTrue();
        assertThat(AdminOpsNotifyService.needsTypedConfirm(noFilter, 1, 1)).isTrue();
        assertThat(AdminOpsNotifyService.needsTypedConfirm(withPhone, 10_000, 10_000)).isFalse();
        assertThat(AdminOpsNotifyService.needsTypedConfirm(AudienceSpec.ids(), 10_001, 10_001)).isTrue();
        // 确认页人数被压低、重算超阈值 → 仍要
        assertThat(AdminOpsNotifyService.needsTypedConfirm(withPhone, 9_500, 10_400)).isTrue();
        assertThat(AdminOpsNotifyService.needsTypedConfirm(AudienceSpec.ids(), 10_001, 9_000)).isTrue();
    }

    @Test
    @DisplayName("I8：确认页 9,500、重算 10,400（预估闸容差内）→ 仍须手输；须输的是确认页上的 9,500")
    void typedConfirmUsesServerRecount() {
        when(audienceQuery.count(any(), any())).thenReturn(10_400L);
        assertThatThrownBy(() -> service.create(valid(f -> f.setPreviewCount("9500")), 1L))
                .isInstanceOf(OpsNotifyTypedConfirmRequired.class)
                .satisfies(e -> assertThat(((OpsNotifyTypedConfirmRequired) e).count()).isEqualTo(9_500L));
        verify(campaigns, never()).save(any());
        assertRejected(f -> {
            f.setPreviewCount("9500");
            f.setConfirmCount("10400");
        }, AdminOpsNotifyService.ERR_CONFIRM_COUNT);
        service.create(valid(f -> {
            f.setPreviewCount("9500");
            f.setConfirmCount("9500");
        }), 1L);
        assertThat(saved().getEstimatedCount()).isEqualTo(10_400);
    }

    @Test
    @DisplayName("I8：按条件但一个条件都没选（= 全部用户）→ 即使人数很少也须手输")
    void typedConfirmForEmptyFilter() {
        assertThatThrownBy(() -> service.create(valid(f -> f.setHasPhone(null)), 1L))
                .isInstanceOf(OpsNotifyTypedConfirmRequired.class)
                .satisfies(e -> assertThat(((OpsNotifyTypedConfirmRequired) e).count()).isEqualTo(5L));
        verify(campaigns, never()).save(any());
        service.create(valid(f -> {
            f.setHasPhone(null);
            f.setConfirmCount("5");
        }), 1L);
        assertThat(AudienceSpec.parse(saved().getAudienceSpec()).noFilter()).isTrue();
    }

    @Test
    @DisplayName("预估闸：缺 / 非数字 → preview-required；实际远超确认页 → preview-stale；变少放行")
    void previewGate() {
        assertRejected(f -> f.setPreviewCount(""), AdminOpsNotifyService.ERR_PREVIEW_REQUIRED);
        assertRejected(f -> f.setPreviewCount("abc"), AdminOpsNotifyService.ERR_PREVIEW_REQUIRED);
        when(audienceQuery.count(any(), any())).thenReturn(200L);
        assertRejected(f -> f.setPreviewCount("100"), AdminOpsNotifyService.ERR_PREVIEW_STALE);
        assertThat(AdminOpsNotifyService.checkPreview("100", 110)).isEqualTo(100);
        assertThat(AdminOpsNotifyService.checkPreview("100", 3)).isEqualTo(100);
    }

    @Test
    @DisplayName("一次性提交键：缺键 / 形状不对 / 已被认领 → duplicate-submit；核心拒绝时释放键")
    void submitKey() {
        assertRejected(f -> f.setSubmitKey(""), AdminOpsNotifyService.ERR_DUPLICATE_SUBMIT);
        assertRejected(f -> f.setSubmitKey("bad key!"), AdminOpsNotifyService.ERR_DUPLICATE_SUBMIT);
        when(redisOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertRejected(f -> { }, AdminOpsNotifyService.ERR_DUPLICATE_SUBMIT);

        when(redisOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(campaigns.save(any(OpsNotificationCampaign.class))).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> service.create(valid(f -> { }), 1L)).isInstanceOf(IllegalStateException.class);
        verify(redis).delete(AdminOpsNotifyService.SUBMIT_KEY_PREFIX + "0f8c2a4e-1b2c-4d5e-8f90-123456789abc");
    }

    // ---------- 进入 ④ 确认 ----------

    @Test
    @DisplayName("review：与新建同一套校验，但不认领提交键、不落库；返回此刻人数")
    void reviewDoesNotPersist() {
        AdminOpsNotifyService.Prepared p = service.review(valid(f -> f.setPreviewCount("")));
        assertThat(p.count()).isEqualTo(5);
        assertThat(p.titleId()).isEqualTo("Promo akhir pekan");
        verify(campaigns, never()).save(any());
        verify(redisOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        assertThatThrownBy(() -> service.review(valid(f -> f.setTitleId(""))))
                .isInstanceOf(AppException.class);
    }

    // ---------- 指定 ID ----------

    @Test
    @DisplayName("ID 行解析：BOM / CRLF / CSV 首列 / 引号 / 空行；重复只算一次；非数字原样记下")
    void parseIdLines() {
        AdminOpsNotifyService.ParsedIds p = AdminOpsNotifyService.parseIdLines(
                "﻿user_id,name\r\n\"12\",Budi\r\n\r\n12\n 34 ;x\n-5\n1.5\n");
        assertThat(p.ids()).containsExactly(12L, 34L);
        // 首行纯字母表头不算跳过（验收 2026-10-15）；非首行的同样文本照常算无效
        assertThat(p.invalid()).containsExactly("-5", "1.5");
        assertThat(AdminOpsNotifyService.parseIdLines("12\nuser_id\n").invalid()).containsExactly("user_id");
        assertThat(AdminOpsNotifyService.parseIdLines("id1\n12\n").invalid())
                .as("非常见列名的首行不当表头").containsExactly("id1");
        assertThat(AdminOpsNotifyService.parseIdLines("UserId\n12\n").invalid()).isEmpty();
        assertThat(AdminOpsNotifyService.parseIdLines(null).ids()).isEmpty();
    }

    @Test
    @DisplayName("ID 上限按非空行计：50,000 行通过，50,001 行 → ids-too-many")
    void idLineLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50_000; i++) {
            sb.append(i).append('\n');
        }
        assertThat(AdminOpsNotifyService.parseIdLines(sb + "\n\n").ids()).hasSize(50_000);
        assertThatThrownBy(() -> AdminOpsNotifyService.parseIdLines(sb + "50001"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode())
                        .isEqualTo(AdminOpsNotifyService.ERR_IDS_TOO_MANY));
    }

    @Test
    @DisplayName("checkIds：有效人数 + 四类原因计数（非数字 / 库里没有 → 不存在）+ 跳过清单")
    @SuppressWarnings("unchecked")
    void checkIdsClassifies() {
        when(audienceQuery.classify(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            Map<Long, IdStatus> m = new HashMap<>();
            for (Long id : ids) {
                m.put(id, switch (id.intValue()) {
                    case 2 -> IdStatus.DELETED;
                    case 3 -> IdStatus.BANNED;
                    case 4 -> IdStatus.VIRTUAL;
                    case 5 -> IdStatus.NOT_FOUND;
                    default -> IdStatus.OK;
                });
            }
            return m;
        });
        AdminOpsNotifyService.IdsCheck r = service.checkIds("1\n1\n2\n3\n4\n5\n6\nfoo\n");
        assertThat(r.valid()).isEqualTo(2);
        assertThat(r.skippedTotal()).isEqualTo(5);
        assertThat(r.skippedByReason()).containsEntry(IdStatus.NOT_FOUND, 2).containsEntry(IdStatus.DELETED, 1)
                .containsEntry(IdStatus.BANNED, 1).containsEntry(IdStatus.VIRTUAL, 1);
        assertThat(r.skipped()).extracting(AdminOpsNotifyService.Skipped::value)
                .containsExactly("foo", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("preview：IDS 模式按有效人数（全是非数字 → 0，不查库）")
    void previewIds() {
        assertThat(service.preview(valid(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds("abc");
        }))).isZero();
        verify(audienceQuery, never()).count(any(), any());
        assertThat(service.preview(valid(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds("1\n2");
        }))).isEqualTo(5);
    }

    // ---------- 列表 / 取消 ----------

    @Test
    @DisplayName("列表筛选：状态非五态 → 全部；近 N 天 → since = now − N 天；全部 → since 为空")
    void pageFilters() {
        service.page("PENDING", 30, 0);
        verify(campaigns).search(eq("PENDING"), eq(OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(30)),
                ZoneOffset.UTC)), any(Pageable.class));
        service.page("BOGUS", null, 2);
        verify(campaigns).search(isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("取消：不存在 → 404 带码；待发送 → 审计 CANCELED")
    void cancel() {
        assertThatThrownBy(() -> service.cancel("nope", 1L)).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode())
                        .isEqualTo(AdminOpsNotifyService.ERR_NOT_FOUND));
        OpsNotificationCampaign c = OpsNotificationCampaign.create("tokA", "Judul", "Title", "Isi", "Body",
                com.tailtopia.ops.target.OpsTargetType.NONE, null, AudienceSpec.all().toJson(),
                NOW.plusSeconds(3600), 1L);
        when(campaigns.findByPublicToken("tokA")).thenReturn(java.util.Optional.of(c));
        when(campaigns.cancelIfPending(eq("tokA"), any())).thenReturn(1);
        service.cancel("tokA", 3L);
        verify(audit).record(eq(3L), eq(AuditActions.OPS_NOTIFY_CANCELED), eq("OPS_NOTIFY"), eq("tokA"),
                anyString());
    }

    @Test
    @DisplayName("parseStatus：五态之一，否则 null")
    void parseStatus() {
        assertThat(AdminOpsNotifyService.parseStatus("DONE")).isNotNull();
        assertThat(AdminOpsNotifyService.parseStatus("done")).isNull();
        assertThat(AdminOpsNotifyService.parseStatus(" ")).isNull();
        assertThat(Set.of(AdminOpsNotifyService.WITHIN_PRESETS.toArray())).containsExactlyInAnyOrder(7, 30, 90);
    }
}
