package com.tailtopia.admin.opsnotify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.ops.AdminOpsTargetValidator;
import com.tailtopia.admin.opsnotify.dto.OpsNotifyForm;
import com.tailtopia.auth.service.OpsAudienceQuery;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignReadyEvent;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.ops.service.OpsNotificationCampaignService;
import com.tailtopia.ops.OpsTokenGenerator;
import com.tailtopia.ops.target.OpsTargetPolicy;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.repository.ShopProductRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：后台「运营通知」发送页（V1.3.4 AB-31，spec I/O 矩阵各校验行 + ID 解析 + 条件转换）。
 *
 * <p>发送核心 {@link OpsNotificationCampaignService} 与跳转校验 {@link AdminOpsTargetValidator} 都用<b>真实的</b>
 * （只 mock 仓储 / DAO / 事件）：本类要钉的正是「后台表单错误最终挂上的码」，其中一半是核心给的，mock 掉就什么也没测。
 */
class AdminOpsNotifyServiceTest {

    private OpsNotificationCampaignRepository campaigns;
    private OpsCampaignTargetDao targetsDao;
    private ShopProductRepository products;
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
        products = mock(ShopProductRepository.class);
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
        OpsNotificationCampaignService core = new OpsNotificationCampaignService(campaigns, targetsDao, policy,
                tokens, events);
        service = new AdminOpsNotifyService(core, campaigns, audienceQuery, new AdminOpsTargetValidator(policy),
                audit, mock(AdminAccountRepository.class), redis);
    }

    /** 一份合法表单：四项文案齐、全部用户、不跳转、立即发送（调用方覆盖某项构造违规用例）。 */
    private static OpsNotifyForm valid(Consumer<OpsNotifyForm> tweak) {
        OpsNotifyForm f = new OpsNotifyForm();
        f.setTitleId("  Promo akhir pekan ");
        f.setTitleEn("Weekend promo");
        f.setBodyId("Isi promo");
        f.setBodyEn("Promo body");
        f.setAudienceMode("ALL");
        f.setTargetType("NONE");
        f.setScheduledAt("");
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
    @DisplayName("立即发送 · 指定 ID：去重后写子表、提交后发事件、文案 trim、审计 CREATED（target = token，不写 ID 列表）")
    void createIdsImmediate() {
        String token = service.create(valid(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds("12, 34\n12\t56，78");
        }), 7L);

        assertThat(token).isEqualTo("tok22charsAbcdefghijkl");
        OpsNotificationCampaign c = saved();
        assertThat(c.getTitleId()).isEqualTo("Promo akhir pekan");
        assertThat(c.getScheduledAt()).isNull();
        assertThat(c.getCreatedBy()).isEqualTo(7L);
        assertThat(AudienceSpec.parse(c.getAudienceSpec()).mode()).isEqualTo(AudienceSpec.Mode.IDS);
        verify(targetsDao).insertAll(99L, List.of(12L, 34L, 56L, 78L));
        verify(events).publishEvent(new OpsCampaignReadyEvent(99L));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(7L), eq(AuditActions.OPS_NOTIFY_CREATED), eq("OPS_NOTIFY"), eq(token),
                detail.capture());
        assertThat(detail.getValue()).contains("IDS 4 个").doesNotContain("34");
    }

    @Test
    @DisplayName("定时：WIB → UTC 落 scheduled_at，不发立即事件")
    void createScheduledConvertsWib() {
        service.create(valid(f -> f.setScheduledAt("2099-01-01T10:00")), 1L);
        assertThat(saved().getScheduledAt()).isEqualTo(Instant.parse("2099-01-01T03:00:00Z"));
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("按条件：表单字段转成 AudienceSpec（日期 / 三态布尔 / 物种多选），空 = 不限")
    void filterFormToSpec() {
        service.create(valid(f -> {
            f.setAudienceMode("FILTER");
            f.setRegisteredFrom("2026-10-01");
            f.setRegisteredTo("2026-10-12");
            f.setActiveFrom("");
            f.setHasPet("true");
            f.setSpecies(List.of("CAT", "DOG"));
            f.setHasShopOrder("false");
            f.setHasPhone("");
        }), 1L);
        AudienceSpec s = AudienceSpec.parse(saved().getAudienceSpec());
        assertThat(s.mode()).isEqualTo(AudienceSpec.Mode.FILTER);
        assertThat(s.registeredFrom()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(s.registeredTo()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(s.activeFrom()).isNull();
        assertThat(s.hasPet()).isTrue();
        assertThat(s.species()).containsExactlyInAnyOrder(PetType.CAT, PetType.DOG);
        assertThat(s.hasShopOrder()).isFalse();
        assertThat(s.hasPhone()).isNull();
        verify(targetsDao, never()).insertAll(anyLong(), any());
    }

    @Test
    @DisplayName("全部用户：IDS 文本框残留的值不进子表（只读当前模式用得到的字段）")
    void allIgnoresIdsText() {
        service.create(valid(f -> f.setUserIds("1,2,3")), 1L);
        assertThat(AudienceSpec.parse(saved().getAudienceSpec()).mode()).isEqualTo(AudienceSpec.Mode.ALL);
        verify(targetsDao, never()).insertAll(anyLong(), any());
    }

    // ---------- I/O 矩阵：校验行 ----------

    @Test
    @DisplayName("缺文案（任一语言标题或正文空 / 纯空白）→ text.blank")
    void textBlank() {
        assertRejected(f -> f.setTitleEn(""), OpsNotificationCampaignService.ERR_TEXT_BLANK);
        assertRejected(f -> f.setBodyId("   "), OpsNotificationCampaignService.ERR_TEXT_BLANK);
    }

    @Test
    @DisplayName("超长（标题 121 / 正文 256）→ text.too-long；恰好 120 / 255 通过")
    void textTooLong() {
        assertRejected(f -> f.setTitleId("x".repeat(121)), OpsNotificationCampaignService.ERR_TEXT_TOO_LONG);
        assertRejected(f -> f.setBodyEn("x".repeat(256)), OpsNotificationCampaignService.ERR_TEXT_TOO_LONG);
        service.create(valid(f -> {
            f.setTitleId("x".repeat(120));
            f.setBodyEn("y".repeat(255));
        }), 1L);
        verify(campaigns).save(any());
    }

    @Test
    @DisplayName("定时早于现在 → schedule.past；格式不对 → schedule.format")
    void schedule() {
        assertRejected(f -> f.setScheduledAt("2020-01-01T00:00"), OpsNotificationCampaignService.ERR_SCHEDULE_PAST);
        assertRejected(f -> f.setScheduledAt("明天十点"), AdminOpsNotifyService.ERR_SCHEDULE_FORMAT);
    }

    @Test
    @DisplayName("指定 ID：空 → ids-empty；`12,abc` → ids-not-numeric；50,001 个 → ids-too-many")
    void idsErrors() {
        assertRejected(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds(" ,\n ");
        }, OpsNotificationCampaignService.ERR_IDS_EMPTY);
        assertRejected(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds("12,abc");
        }, AdminOpsNotifyService.ERR_IDS_NOT_NUMERIC);
        String tooMany = LongStream.rangeClosed(1, 50_001).mapToObj(Long::toString).collect(Collectors.joining(","));
        assertRejected(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds(tooMany);
        }, AdminOpsNotifyService.ERR_IDS_TOO_MANY);
    }

    @Test
    @DisplayName("条件区间倒置 / 人群未选 / 三态取值非法 / 物种非法 / 日期格式错 → audience.invalid")
    void audienceInvalid() {
        String code = OpsNotificationCampaignService.ERR_AUDIENCE_INVALID;
        assertRejected(f -> {
            f.setAudienceMode("FILTER");
            f.setRegisteredFrom("2026-10-12");
            f.setRegisteredTo("2026-10-01");
        }, code);
        assertRejected(f -> {
            f.setAudienceMode("FILTER");
            f.setActiveFrom("2026-10-12");
            f.setActiveTo("2026-10-01");
        }, code);
        assertRejected(f -> f.setAudienceMode(""), code);
        assertRejected(f -> f.setAudienceMode("EVERYONE"), code);
        assertRejected(f -> {
            f.setAudienceMode("FILTER");
            f.setHasPet("maybe");
        }, code);
        assertRejected(f -> {
            f.setAudienceMode("FILTER");
            f.setSpecies(List.of("FISH"));
        }, code);
        assertRejected(f -> {
            f.setAudienceMode("FILTER");
            f.setRegisteredFrom("2026/10/01");
        }, code);
    }

    @Test
    @DisplayName("跳转目标：与运营弹窗共用的具体码（外链非 http(s) / 商品不在售 / 类型非法）")
    void targetCodes() {
        assertRejected(f -> {
            f.setTargetType("EXTERNAL");
            f.setTargetValue("ftp://x.example/a");
        }, AdminOpsTargetValidator.ERR_EXTERNAL);
        assertRejected(f -> {
            f.setTargetType("PRODUCT");
            f.setTargetValue("nosuchprod");
        }, AdminOpsTargetValidator.ERR_PRODUCT_UNAVAILABLE);
        assertRejected(f -> f.setTargetType("NOPE"), AdminOpsTargetValidator.ERR_TYPE);
        assertRejected(f -> f.setTargetValue("stray"), AdminOpsTargetValidator.ERR_NONE_WITH_VALUE);
    }

    // ---------- ID 解析 ----------

    @Test
    @DisplayName("ID 解析：逗号（含全角）/ 空格 / 换行混用、去重保序；负号与小数算非数字；恰好 5 万个通过")
    void parseIds() {
        assertThat(AdminOpsNotifyService.parseIds("3, 1\n\n2 3，1")).containsExactly(3L, 1L, 2L);
        assertThat(AdminOpsNotifyService.parseIds("")).isEmpty();
        assertThat(AdminOpsNotifyService.parseIds(null)).isEmpty();
        for (String bad : List.of("-1", "1.5", "1e3", "１２")) {
            assertThatThrownBy(() -> AdminOpsNotifyService.parseIds(bad))
                    .extracting(e -> ((AppException) e).getMessageCode())
                    .isEqualTo(AdminOpsNotifyService.ERR_IDS_NOT_NUMERIC);
        }
        String max = LongStream.rangeClosed(1, 50_000).mapToObj(Long::toString).collect(Collectors.joining(" "));
        assertThat(AdminOpsNotifyService.parseIds(max)).hasSize(50_000);
        // 重复不占名额：5 万个不同 ID + 重复项仍通过
        assertThat(AdminOpsNotifyService.parseIds(max + " 1 2 3")).hasSize(50_000);
    }

    // ---------- 预估 ----------

    @Test
    @DisplayName("预估：IDS 把解析后的 ID 交给计数；FILTER / ALL 不传 ID；条件不合法同样 422")
    void preview() {
        when(audienceQuery.count(any(), any())).thenReturn(2L);
        assertThat(service.preview(valid(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds("5 6 5");
        }))).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<AudienceSpec> spec = ArgumentCaptor.forClass(AudienceSpec.class);
        verify(audienceQuery).count(spec.capture(), ids.capture());
        assertThat(spec.getValue().mode()).isEqualTo(AudienceSpec.Mode.IDS);
        assertThat(new ArrayList<>(ids.getValue())).containsExactly(5L, 6L);

        service.preview(valid(f -> {
            f.setAudienceMode("FILTER");
            f.setHasPhone("true");
        }));
        verify(audienceQuery).count(eq(new AudienceSpec(AudienceSpec.Mode.FILTER, null, null, null, null, null,
                null, null, true)), isNull());

        assertThatThrownBy(() -> service.preview(valid(f -> {
            f.setAudienceMode("IDS");
            f.setUserIds("");
        }))).extracting(e -> ((AppException) e).getMessageCode())
                .isEqualTo(OpsNotificationCampaignService.ERR_IDS_EMPTY);
        verify(campaigns, never()).save(any());
    }

    // ---------- 防重复提交 / 预估闸（复审补丁） ----------

    @Test
    @DisplayName("一次性 submitKey：首次 SET NX 认领（30 分钟 TTL）；同一键再提交 → 422 duplicate-submit，不建第二条")
    void duplicateSubmitKey() {
        service.create(valid(f -> { }), 1L);
        verify(redisOps).setIfAbsent(AdminOpsNotifyService.SUBMIT_KEY_PREFIX + "0f8c2a4e-1b2c-4d5e-8f90-123456789abc",
                "1", AdminOpsNotifyService.SUBMIT_KEY_TTL);
        verify(campaigns).save(any());

        when(redisOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThatThrownBy(() -> service.create(valid(f -> { }), 1L))
                .satisfies(e -> {
                    assertThat(((AppException) e).getMessageCode()).isEqualTo(AdminOpsNotifyService.ERR_DUPLICATE_SUBMIT);
                    assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        verify(campaigns).save(any()); // 仍只有第一次那一条
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("submitKey 缺失 / 形状不对 → 422，不认领")
    void missingSubmitKey() {
        assertRejected(f -> f.setSubmitKey(null), AdminOpsNotifyService.ERR_DUPLICATE_SUBMIT);
        assertRejected(f -> f.setSubmitKey("a b:c"), AdminOpsNotifyService.ERR_DUPLICATE_SUBMIT);
        verify(redisOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("校验失败不锁表单：后台层校验错在认领前；核心校验错（文案空）认领后释放键")
    void validationErrorReleasesKey() {
        assertRejected(f -> f.setScheduledAt("明天十点"), AdminOpsNotifyService.ERR_SCHEDULE_FORMAT);
        verify(redisOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));

        assertRejected(f -> f.setTitleEn(""), OpsNotificationCampaignService.ERR_TEXT_BLANK);
        verify(redis).delete(AdminOpsNotifyService.SUBMIT_KEY_PREFIX + "0f8c2a4e-1b2c-4d5e-8f90-123456789abc");
    }

    @Test
    @DisplayName("预估闸：缺 / 非数字 / 0 → preview-required；重算 0 人 → audience-empty；均不认领键")
    void previewGateRequiredAndEmpty() {
        assertRejected(f -> f.setPreviewCount(null), AdminOpsNotifyService.ERR_PREVIEW_REQUIRED);
        assertRejected(f -> f.setPreviewCount(""), AdminOpsNotifyService.ERR_PREVIEW_REQUIRED);
        assertRejected(f -> f.setPreviewCount("abc"), AdminOpsNotifyService.ERR_PREVIEW_REQUIRED);
        assertRejected(f -> f.setPreviewCount("0"), AdminOpsNotifyService.ERR_PREVIEW_REQUIRED);
        when(audienceQuery.count(any(), any())).thenReturn(0L);
        assertRejected(f -> { }, AdminOpsNotifyService.ERR_AUDIENCE_EMPTY);
        verify(redisOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("预估闸：重算比预估多出 max(10, 10%) 以上 → preview-stale；恰在容差内 / 人数变少放行")
    void previewGateStale() {
        when(audienceQuery.count(any(), any())).thenReturn(16L);
        assertRejected(f -> f.setPreviewCount("5"), AdminOpsNotifyService.ERR_PREVIEW_STALE);
        // 小数取 10 人容差：5 → 15 放行
        AdminOpsNotifyService.checkPreview("5", 15);
        // 大数取 10%：1000 → 1100 放行，1101 拒
        AdminOpsNotifyService.checkPreview("1000", 1100);
        assertThatThrownBy(() -> AdminOpsNotifyService.checkPreview("1000", 1101))
                .extracting(e -> ((AppException) e).getMessageCode())
                .isEqualTo(AdminOpsNotifyService.ERR_PREVIEW_STALE);
        // 变少不拦（只会少发）
        AdminOpsNotifyService.checkPreview("1000", 3);
    }

    // ---------- 取消 ----------

    @Test
    @DisplayName("取消待发送：调核心条件更新、审计 CANCELED")
    void cancelPending() {
        OpsNotificationCampaign c = campaign();
        when(campaigns.findByPublicToken("tokA")).thenReturn(Optional.of(c));
        when(campaigns.cancelIfPending(eq("tokA"), any())).thenReturn(1);

        service.cancel("tokA", 5L);

        verify(audit).record(eq(5L), eq(AuditActions.OPS_NOTIFY_CANCELED), eq("OPS_NOTIFY"), eq("tokA"), anyString());
    }

    @Test
    @DisplayName("取消：已在发送 / 已结束 → 409 cancel.not-pending；不存在 → 404 not-found；都不留审计")
    void cancelRejected() {
        when(campaigns.findByPublicToken("tokB")).thenReturn(Optional.of(campaign()));
        when(campaigns.cancelIfPending(eq("tokB"), any())).thenReturn(0);
        assertThatThrownBy(() -> service.cancel("tokB", 5L))
                .satisfies(e -> {
                    assertThat(((AppException) e).getMessageCode())
                            .isEqualTo(OpsNotificationCampaignService.ERR_CANCEL_NOT_PENDING);
                    assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        when(campaigns.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel("nope", 5L))
                .satisfies(e -> {
                    assertThat(((AppException) e).getMessageCode()).isEqualTo(AdminOpsNotifyService.ERR_NOT_FOUND);
                    assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(campaigns, never()).cancelIfPending(eq("nope"), any());
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString());
    }

    private static OpsNotificationCampaign campaign() {
        return OpsNotificationCampaign.create("tok", "Judul", "Title", "Isi", "Body", OpsTargetType.NONE, null,
                AudienceSpec.all().toJson(), null, 1L);
    }
}
