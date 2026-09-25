package com.tailtopia.profile.visitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.social.read.UserHideRelationReader;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * L0：宠物访客视图的**站内入口**（V1.3.0 batch-b1 Story 2.3 · AD-4 · B1-D1）。
 *
 * <p>三组断言：
 * <ul>
 *   <li><b>AC2</b>：主页宠物卡**不下发 cardToken** —— 钉在<b>形状</b>上（record 里装不下）；</li>
 *   <li><b>AD-4 Rule 2</b>：站内入口与分享入口**落到同一层投影**，不复制一套；</li>
 *   <li><b>AC5</b>：站内入口<b>没有</b> calendar / day 端点（访客视图没有日历，别照旧稿补）。</li>
 * </ul>
 */
class InAppVisitorEntryTest {

    private static final long VIEWER = 5L;
    private static final long OWNER = 9L;
    private static final long PET_ID = 42L;

    private VisitorProjectionService visitors;
    private UserHideRelationReader hideRelations;
    private InAppVisitorPetController inApp;

    private ProfileService profiles;
    private AccountQueryService accounts;
    private PublicProfilePetController petCard;

    @BeforeEach
    void setUp() {
        visitors = mock(VisitorProjectionService.class);
        hideRelations = mock(UserHideRelationReader.class);
        inApp = new InAppVisitorPetController(visitors, hideRelations);

        profiles = mock(ProfileService.class);
        accounts = mock(AccountQueryService.class);
        petCard = new PublicProfilePetController(profiles, visitors, accounts, hideRelations);
    }

    private static Jwt user(long userId) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(userId)).claim("role", "USER").build();
    }

    /** 兽医 token：{@code sub=vetId}，与 {@code users.id} 是两个会碰撞的命名空间。 */
    private static Jwt vet(long vetId) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(vetId)).claim("role", "VET").build();
    }

    private static PetProfile pet() {
        PetProfile p = PetProfile.create(OWNER, PetType.CAT, "Miu", "https://cdn/miu.jpg",
                "Ragdoll", LocalDate.of(2024, 6, 1), "Suka tidur di keyboard", "tok-secret");
        try {
            var f = PetProfile.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, PET_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PetProfile.id 字段名变了，改这里", e);
        }
        return p;
    }

    // ===== AC2：不下发分享 token =====

    /**
     * 🔴 **主页宠物卡里物理上就没有 cardToken**（AC2 / AD-4 Rule 3）。
     *
     * <p>B1-D1 否掉的方案正是「由主页下发对方宠物的分享链接码」—— 那等于把一条
     * **永久公开、可转发到站外**的链接发给每个站内访客。**站内可见 ≠ 可对外分发。**
     * <p>钉在形状上而不是"记得别填"：装不下就不可能填错。
     */
    @Test
    void theProfilePetCardCannotEvenHoldAShareToken() {
        List<String> leaked = forbiddenComponentsOf(PublicProfilePetResponse.class,
                List.of("cardtoken", "token", "sharelink", "shareurl", "ogimage", "serial"));
        assertThat(leaked)
                .as("站内可见 ≠ 可对外分发：主页宠物卡不得携带任何可转发到站外的标识")
                .isEmpty();
    }

    /** 顺带：卡片上也不该长出健康 / 问诊字段（与访客投影层同一条边界）。 */
    @Test
    void theProfilePetCardHasNoHealthFields() {
        assertThat(forbiddenComponentsOf(PublicProfilePetResponse.class,
                List.of("health", "consult", "symptom", "diagnos", "medical", "triage")))
                .isEmpty();
    }

    /** 真实取数时也不会把 token 带出去（形状之外再钉一次行为）。 */
    @Test
    void theRenderedPetCardCarriesNoTokenValue() {
        when(accounts.isActive(OWNER)).thenReturn(true);
        when(profiles.findByOwnerId(OWNER)).thenReturn(Optional.of(pet()));
        when(visitors.diaryCount(any())).thenReturn(42L);

        var resp = petCard.pet(null, OWNER);
        PublicProfilePetResponse card = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(card).isNotNull();
        assertThat(card.petId()).isEqualTo(PET_ID);
        assertThat(card.name()).isEqualTo("Miu");
        assertThat(card.diaryCount()).isEqualTo(42L);
        assertThat(card.toString()).doesNotContain("tok-secret");
        // 🔴 别为了一个数去调 stats(...)：那会连带算问诊次数、里程碑进度与两次健康表计数，
        //    五条查询换一个数，而这是个游客可达、userId 可枚举的端点。
        verify(visitors, never()).stats(any());
    }

    // ===== AD-4 Rule 2：同一层投影 =====

    /**
     * 🔴 站内入口的三个端点**全部**经 {@link VisitorProjectionService}。
     *
     * <p>AD-4 Rule 2 的原话：两个入口必须落到同一个投影，否则迟早分叉，
     * 而分叉的表现是私密数据从新入口漏出去。
     */
    @Test
    void everyInAppEndpointGoesThroughTheSharedProjection() {
        when(visitors.findVisibleProfileById(PET_ID)).thenReturn(Optional.of(pet()));
        when(visitors.stats(any())).thenReturn(new VisitorStats(1L, 0L, 0L, 30));
        when(visitors.timeline(any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of());

        inApp.profile(user(VIEWER), PET_ID);
        inApp.stats(user(VIEWER), PET_ID);
        inApp.timeline(user(VIEWER), PET_ID, 30);

        verify(visitors, org.mockito.Mockito.times(3)).findVisibleProfileById(PET_ID);
        verify(visitors).stats(any());
        verify(visitors).timeline(any(), anyInt(), org.mockito.ArgumentMatchers.eq(false));
    }

    /**
     * 🛡 档案不存在 / 主人注销 / 主人被封 → **同一个 404**。
     *
     * <p>判定收在投影层的 {@code findVisibleProfileById}（与分享入口同一句），
     * 这里只验「拿不到就 404」，不在控制器里另写一套可见性。
     */
    @Test
    void anInvisiblePetIsAPlain404() {
        when(visitors.findVisibleProfileById(PET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inApp.profile(user(VIEWER), PET_ID))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(404));
    }

    /** {@code limit} 来自请求方，必须夹紧 —— 不夹的话一个 limit=100000 就拖着整张表走。 */
    @Test
    void theTimelineLimitIsClamped() {
        when(visitors.findVisibleProfileById(PET_ID)).thenReturn(Optional.of(pet()));
        when(visitors.timeline(any(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of());

        inApp.timeline(user(VIEWER), PET_ID, 100000);
        verify(visitors).timeline(any(), eq(100), org.mockito.ArgumentMatchers.eq(false));

        inApp.timeline(user(VIEWER), PET_ID, 0);
        verify(visitors).timeline(any(), eq(1), org.mockito.ArgumentMatchers.eq(false));
    }

    // ===== 拉黑守卫 =====

    /**
     * 🔴 **主动拉黑者按 petId 直达也进不来**。
     *
     * <p>主页那条已经 403 了，但本端点按 petId 寻址 —— 只拦主页等于留了个绕过口
     * （架构 S2：纯前端拦截可被深链绕过）。
     */
    @Test
    void aBlockedOwnersPetIsUnreachableEvenByDirectPetId() {
        when(visitors.findVisibleProfileById(PET_ID)).thenReturn(Optional.of(pet()));
        when(hideRelations.isBlocked(VIEWER, OWNER)).thenReturn(true);

        assertThatThrownBy(() -> inApp.timeline(user(VIEWER), PET_ID, 30))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ex = (AppException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getType()).isEqualTo(ErrorTypes.BLOCKED_USER);
                });

        verify(visitors, never()).timeline(any(), anyInt());
    }

    /**
     * 🔴 反向：**对方拉黑了我** → 落进与「这只宠物不存在」**同一个 404 同一句文案**
     * （V1.3.0 batch-b1 Story 2.5 · FR-118.5）。
     *
     * <p>⚠️ 这里**不能**抛 403 blocked-user：那等于明白告诉他"你被拉黑了"，
     * 而整条 FR-118.5 的目的正是别让他确认这件事。
     */
    @Test
    void anOwnerWhoBlockedMeLooksExactlyLikeAPetThatDoesNotExist() {
        when(visitors.findVisibleProfileById(PET_ID)).thenReturn(Optional.of(pet()));
        when(hideRelations.isBlocked(VIEWER, OWNER)).thenReturn(false); // 我没拉黑他
        when(hideRelations.isBlocked(OWNER, VIEWER)).thenReturn(true);  // 他拉黑了我

        AppException blocked = catchAppException(() -> inApp.profile(user(VIEWER), PET_ID));

        when(visitors.findVisibleProfileById(404L)).thenReturn(Optional.empty());
        AppException missing = catchAppException(() -> inApp.profile(user(VIEWER), 404L));

        assertThat(blocked.getStatus()).isEqualTo(missing.getStatus());
        assertThat(blocked.getType()).isEqualTo(missing.getType());
        assertThat(blocked.getMessage()).isEqualTo(missing.getMessage());
        assertThat(blocked.getStatus().value()).isEqualTo(404);
    }

    /** ⚠️ **只认 BLOCK**：举报隐藏照常放行（与主页同口径）。 */
    @Test
    void aReportOnlyRelationDoesNotCloseTheDoor() {
        when(visitors.findVisibleProfileById(PET_ID)).thenReturn(Optional.of(pet()));
        when(hideRelations.isBlocked(VIEWER, OWNER)).thenReturn(false);

        assertThat(inApp.profile(user(VIEWER), PET_ID).name()).isEqualTo("Miu");
        verify(hideRelations, never()).isHidden(anyLong(), anyLong());
    }

    /** 🔴 兽医 token 不参与拉黑判定（{@code sub=vetId} 会撞上 {@code users.id}）。 */
    @Test
    void aVetTokenIsNeverUsedAsAUserIdForHideLookups() {
        when(visitors.findVisibleProfileById(PET_ID)).thenReturn(Optional.of(pet()));

        inApp.profile(vet(VIEWER), PET_ID);

        verify(hideRelations, never()).isBlocked(anyLong(), anyLong());
    }

    // ===== AC5：没有日历 =====

    /**
     * 🔴 站内入口**没有** calendar / day 两个端点。
     *
     * <p>访客视图没有日历（2026-08-18 与 08-28 两次拍板不做；UI 旧稿画错已于 2026-09-11 修订）。
     * 公开那条路径上留着的 calendar/day 是**有意保留的**（Story 2.2 建好且有测试）——
     * **看到它不等于该在这里也开一份**。
     */
    @Test
    void theInAppEntryExposesNoCalendarEndpoints() {
        List<String> paths = new java.util.ArrayList<>();
        for (Method m : InAppVisitorPetController.class.getDeclaredMethods()) {
            GetMapping g = m.getAnnotation(GetMapping.class);
            if (g != null) {
                paths.addAll(List.of(g.value()));
            }
        }
        assertThat(paths).containsExactlyInAnyOrder("/profile", "/stats", "/timeline");
    }

    // ===== 主页宠物区（AC3） =====

    /** 🛡 没建过档案 → **204（返回 null）**，不是 404 —— 那是「正常状态」，不是「查不到」。 */
    @Test
    void aUserWithoutAPetGetsNoContentRatherThan404() {
        when(accounts.isActive(OWNER)).thenReturn(true);
        when(profiles.findByOwnerId(OWNER)).thenReturn(Optional.empty());

        var resp = petCard.pet(user(VIEWER), OWNER);

        // ⚠️ 断言的是**状态码 204**，不是「body 为 null」—— `return null` 出去的是
        // 200 + 空 body，客户端靠状态码分辨「没宠物」与「有宠物但字段全空」。
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        assertThat(resp.getBody()).isNull();
    }

    /** 🛡 主人注销 / 被封 → 当作没有宠物，**不查也不发**。 */
    @Test
    void anInactiveOwnersPetIsNotRendered() {
        when(accounts.isActive(OWNER)).thenReturn(false);

        assertThat(petCard.pet(user(VIEWER), OWNER).getStatusCode().value()).isEqualTo(204);
        verify(profiles, never()).findByOwnerId(anyLong());
    }

    /** 🔴 宠物卡同样拦拉黑 —— 三个端点各拦一次，只拦一个等于留两个绕过口。 */
    @Test
    void thePetCardBlocksTheSameWayTheProfileDoes() {
        when(hideRelations.isBlocked(VIEWER, OWNER)).thenReturn(true);

        assertThatThrownBy(() -> petCard.pet(user(VIEWER), OWNER))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getType())
                        .isEqualTo(ErrorTypes.BLOCKED_USER));

        verify(profiles, never()).findByOwnerId(anyLong());
    }

    /** 游客照常看得到宠物卡（点头像看这人是谁不需要登录，看他养了只什么也一样）。 */
    @Test
    void aGuestSeesThePetCardAndNoHideLookupHappens() {
        when(accounts.isActive(OWNER)).thenReturn(true);
        when(profiles.findByOwnerId(OWNER)).thenReturn(Optional.of(pet()));
        when(visitors.diaryCount(any())).thenReturn(42L);

        assertThat(petCard.pet(null, OWNER).getBody()).isNotNull();
        verify(hideRelations, never()).isBlocked(anyLong(), anyLong());
    }

    // ===== helpers =====

    /** 取出 lambda 抛出的 {@link AppException}（两条路径的响应要逐字段比）。 */
    private static AppException catchAppException(Runnable r) {
        try {
            r.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("期望抛 AppException，但没有抛");
    }

    private static List<String> forbiddenComponentsOf(Class<?> record, List<String> hints) {
        List<String> offenders = new java.util.ArrayList<>();
        for (RecordComponent c : record.getRecordComponents()) {
            String name = c.getName().toLowerCase(Locale.ROOT);
            for (String hint : hints) {
                if (name.contains(hint)) {
                    offenders.add(c.getName());
                }
            }
        }
        return offenders;
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static int eq(int v) {
        return org.mockito.ArgumentMatchers.eq(v);
    }
}
