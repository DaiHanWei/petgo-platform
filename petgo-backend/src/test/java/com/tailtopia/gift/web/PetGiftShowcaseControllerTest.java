package com.tailtopia.gift.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.service.GiftShowcaseAccess;
import com.tailtopia.gift.service.GiftShowcaseQuery;
import com.tailtopia.gift.service.GiftShowcaseQuery.PetGiftSummary;
import com.tailtopia.gift.service.GiftShowcaseQuery.SenderGiftKind;
import com.tailtopia.gift.service.GiftShowcaseQuery.SupporterKey;
import com.tailtopia.gift.service.GiftShowcaseQuery.SupporterRow;
import com.tailtopia.gift.service.GiftShowcaseQuery.WallRow;
import com.tailtopia.gift.web.dto.SupporterCursor;
import com.tailtopia.shared.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * V1.4.0 gift Story 2.1 · AC1 / AC2 / AC8 · L0（{@code MockMvcBuilders.standaloneSetup}，不起 Spring 上下文）：
 * JSON 字段名与结构、墙顺序透传、名次与游标、非法游标 400 ProblemDetail、{@code ?userId=} 被忽略、不下发内部标识。
 */
class PetGiftShowcaseControllerTest {

    private static final long PET = 30L;
    private static final long OWNER = 7L;
    private static final long VIEWER = 99L;

    private GiftShowcaseAccess access;
    private GiftShowcaseQuery query;
    private AccountQueryService accounts;
    private MockMvc mvc;
    /** 当前请求的 JWT（standalone 不走 Security 过滤链，用参数解析器直接注入）。 */
    private Jwt currentJwt;

    private static Jwt userJwt(long sub) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(String.valueOf(sub)).claim("role", "USER")
                .build();
    }

    @BeforeEach
    void setUp() {
        access = mock(GiftShowcaseAccess.class);
        query = mock(GiftShowcaseQuery.class);
        accounts = mock(AccountQueryService.class);
        currentJwt = userJwt(VIEWER);
        HandlerMethodArgumentResolver jwtResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return Jwt.class.equals(parameter.getParameterType());
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                    NativeWebRequest request, WebDataBinderFactory binderFactory) {
                return currentJwt;
            }
        };
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new com.tailtopia.shared.i18n.Messages(new com.tailtopia.shared.i18n.AdminLocaleConfig().messageSource()),
                org.springframework.util.unit.DataSize.ofMegabytes(10));
        mvc = MockMvcBuilders.standaloneSetup(new PetGiftShowcaseController(access, query, accounts))
                .setCustomArgumentResolvers(jwtResolver)
                .setControllerAdvice(handler)
                .build();
    }

    private void visibleAs(boolean owner) {
        GiftPet pet = new GiftPet(PET, OWNER, "Mochi", "https://cdn/p.jpg", "CAT", "Persia", true, false);
        when(access.requireVisible(anyLong(), eq(PET))).thenReturn(new GiftShowcaseAccess.Visible(pet, owner));
    }

    @Test
    void showcaseJsonShapeAndWallOrderArePassedThrough() throws Exception {
        visibleAs(false);
        when(query.summariesOf(List.of(PET))).thenReturn(Map.of(PET, new PetGiftSummary(53_000L, 4L, 2)));
        when(query.supporterCount(PET, null)).thenReturn(2L);
        when(query.wall(PET)).thenReturn(List.of(
                new WallRow(2L, "Cake", "Kue", "https://cdn/c.png", 50_000L, 1L),
                new WallRow(1L, "Bone", "Tulang", "https://cdn/b.png", 1_000L, 3L)));

        String body = mvc.perform(get("/api/v1/pets/{petId}/gifts", PET).param("userId", String.valueOf(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pet.petId").value(PET))
                .andExpect(jsonPath("$.pet.name").value("Mochi"))
                .andExpect(jsonPath("$.pet.petType").value("CAT"))
                .andExpect(jsonPath("$.viewerIsOwner").value(false))
                .andExpect(jsonPath("$.totalValue").value(53_000))
                .andExpect(jsonPath("$.giftCount").value(4))
                .andExpect(jsonPath("$.supporterCount").value(2))
                .andExpect(jsonPath("$.wall.length()").value(2))
                .andExpect(jsonPath("$.wall[0].nameEn").value("Cake"))
                .andExpect(jsonPath("$.wall[0].nameId").value("Kue"))
                .andExpect(jsonPath("$.wall[0].iconUrl").value("https://cdn/c.png"))
                .andExpect(jsonPath("$.wall[0].unitPrice").value(50_000))
                .andExpect(jsonPath("$.wall[0].count").value(1))
                .andExpect(jsonPath("$.wall[1].nameEn").value("Bone"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("x-oss-process=image/"); // 宠物头像去 EXIF（与访客视图同口径）
        assertThat(body).doesNotContain("ownerId").doesNotContain("senderId").doesNotContain("publicToken")
                .doesNotContain("giftId").doesNotContain("breed");
        // ?userId= 被忽略：查看者只取 JWT sub。
        verify(access).requireVisible(VIEWER, PET);
    }

    @Test
    void ownerViewExcludesHiddenSupportersFromCountOnly() throws Exception {
        visibleAs(true);
        currentJwt = userJwt(OWNER);
        when(query.summariesOf(List.of(PET))).thenReturn(Map.of());
        when(query.supporterCount(PET, OWNER)).thenReturn(1L);
        when(query.wall(PET)).thenReturn(List.of());

        mvc.perform(get("/api/v1/pets/{petId}/gifts", PET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerIsOwner").value(true))
                .andExpect(jsonPath("$.totalValue").value(0))
                .andExpect(jsonPath("$.giftCount").value(0))
                .andExpect(jsonPath("$.supporterCount").value(1))
                .andExpect(jsonPath("$.wall.length()").value(0));
        verify(query).supporterCount(PET, OWNER);
    }

    private static SupporterRow row(long sender, long amount) {
        return new SupporterRow(sender, amount, amount / 1_000, Instant.parse("2026-10-18T08:00:00Z")
                .plusSeconds(sender));
    }

    @Test
    void supporterRowsCarryRanksGiftsAndNoInternalIds() throws Exception {
        visibleAs(false);
        when(query.supporters(eq(PET), isNull(), isNull(), eq(21)))
                .thenReturn(List.of(row(501L, 9_000L), row(502L, 3_000L)));
        when(accounts.findAuthorViewsWithoutTags(List.of(501L, 502L))).thenReturn(Map.of(
                501L, new AuthorView(501L, "Andi", "https://cdn/a.jpg", false, List.of()),
                502L, AuthorView.anonymized(502L)));
        List<SenderGiftKind> four = List.of(
                new SenderGiftKind(4L, "https://cdn/4.png", "D", "D-id", 5_000L, 1L),
                new SenderGiftKind(3L, "https://cdn/3.png", "C", "C-id", 2_000L, 1L),
                new SenderGiftKind(2L, "https://cdn/2.png", "B", "B-id", 1_000L, 1L),
                new SenderGiftKind(1L, "https://cdn/1.png", "A", "A-id", 1_000L, 1L));
        when(query.giftKindsBySender(PET, List.of(501L, 502L))).thenReturn(Map.of(501L, four,
                502L, List.of(new SenderGiftKind(1L, "https://cdn/1.png", "A", "A-id", 1_000L, 3L))));

        String body = mvc.perform(get("/api/v1/pets/{petId}/gifts/supporters", PET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].nickname").value("Andi"))
                .andExpect(jsonPath("$.items[0].avatarUrl").value("https://cdn/a.jpg"))
                .andExpect(jsonPath("$.items[0].deleted").value(false))
                .andExpect(jsonPath("$.items[0].totalAmount").value(9_000))
                .andExpect(jsonPath("$.items[0].giftCount").value(9))
                .andExpect(jsonPath("$.items[0].gifts.length()").value(3))
                .andExpect(jsonPath("$.items[0].gifts[0].nameEn").value("D"))
                .andExpect(jsonPath("$.items[0].gifts[0].nameId").value("D-id"))
                .andExpect(jsonPath("$.items[0].gifts[0].iconUrl").value("https://cdn/4.png"))
                .andExpect(jsonPath("$.items[0].gifts[0].count").value(1))
                .andExpect(jsonPath("$.items[0].moreKinds").value(1))
                .andExpect(jsonPath("$.items[1].rank").value(2))
                .andExpect(jsonPath("$.items[1].deleted").value(true))
                .andExpect(jsonPath("$.items[1].moreKinds").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("senderId").doesNotContain("501").doesNotContain("502")
                .doesNotContain("publicToken").doesNotContain("ownerId").doesNotContain("userId");
        // 访客视角不排除任何人
        verify(query).supporters(eq(PET), isNull(), isNull(), eq(21));
    }

    @Test
    void fullPageEmitsCursorAndNextPageContinuesRanks() throws Exception {
        visibleAs(true);
        currentJwt = userJwt(OWNER);
        List<SupporterRow> page = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            page.add(row(1_000L + i, 100_000L - i * 1_000L));
        }
        when(query.supporters(eq(PET), eq(OWNER), isNull(), eq(21))).thenReturn(page);
        when(accounts.findAuthorViewsWithoutTags(any())).thenReturn(Map.of());
        when(query.giftKindsBySender(eq(PET), any())).thenReturn(Map.of());

        String body = mvc.perform(get("/api/v1/pets/{petId}/gifts/supporters", PET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[19].rank").value(20))
                .andReturn().getResponse().getContentAsString();
        String cursor = new tools.jackson.databind.ObjectMapper().readTree(body).get("nextCursor").asText();
        SupporterCursor decoded = SupporterCursor.decodeOrNull(cursor);
        assertThat(decoded).isNotNull();
        assertThat(decoded.nextRank()).isEqualTo(21);
        assertThat(decoded.senderId()).isEqualTo(1_019L); // 第 20 行（不是多取的第 21 行）
        assertThat(decoded.totalAmount()).isEqualTo(100_000L - 19 * 1_000L);

        when(query.supporters(eq(PET), eq(OWNER), any(SupporterKey.class), eq(21)))
                .thenReturn(List.of(row(2_000L, 1_000L)));
        mvc.perform(get("/api/v1/pets/{petId}/gifts/supporters", PET).param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].rank").value(21))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        // 第二页的 keyset 就是第一页最后一行（第 20 行）的排序键。
        ArgumentCaptor<SupporterKey> key = ArgumentCaptor.forClass(SupporterKey.class);
        verify(query, org.mockito.Mockito.times(2)).supporters(eq(PET), eq(OWNER), key.capture(), eq(21));
        assertThat(key.getAllValues().get(0)).isNull();
        assertThat(key.getAllValues().get(1)).isEqualTo(new SupporterKey(100_000L - 19 * 1_000L,
                Instant.parse("2026-10-18T08:00:00Z").plusSeconds(1_019L), 1_019L));
    }

    @Test
    void invalidCursorIs400ProblemDetail() throws Exception {
        visibleAs(false);
        mvc.perform(get("/api/v1/pets/{petId}/gifts/supporters", PET).param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.trace").doesNotExist());
        verify(query, never()).supporters(anyLong(), any(), any(), anyInt());
    }

    @Test
    void emptyBoardIsOkWithoutCursor() throws Exception {
        visibleAs(false);
        when(query.supporters(eq(PET), isNull(), isNull(), eq(21))).thenReturn(List.of());
        mvc.perform(get("/api/v1/pets/{petId}/gifts/supporters", PET).param("cursor", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        verify(accounts, never()).findAuthorViewsWithoutTags(any());
    }

    @Test
    void visibilityErrorsPassThroughAsProblemDetail() throws Exception {
        when(access.requireVisible(anyLong(), eq(PET)))
                .thenThrow(com.tailtopia.shared.error.AppException.notFound("链接已失效"));
        mvc.perform(get("/api/v1/pets/{petId}/gifts", PET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").exists());
        mvc.perform(get("/api/v1/pets/{petId}/gifts/supporters", PET))
                .andExpect(status().isNotFound());
    }
}
