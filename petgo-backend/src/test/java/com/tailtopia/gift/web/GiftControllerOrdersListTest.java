package com.tailtopia.gift.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import com.tailtopia.gift.service.GiftCatalogService;
import com.tailtopia.gift.service.GiftOrderQueryService;
import com.tailtopia.gift.service.GiftSendService;
import com.tailtopia.gift.service.MyGiftOrderQuery;
import com.tailtopia.gift.web.dto.GiftOrderPage;
import com.tailtopia.gift.web.dto.GiftOrderRow;
import com.tailtopia.shared.error.GlobalExceptionHandler;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * V1.4.0 gift Story 2.3 · AC1 · L0（{@code MockMvcBuilders.standaloneSetup}，不起 Spring 上下文）：
 * 无 JWT → 401 ProblemDetail；{@code ?userId=} 被忽略；非法游标 400；JSON 字段名与结构；列表与单笔路径不冲突。
 */
class GiftControllerOrdersListTest {

    private static final long ME = 42L;

    private MyGiftOrderQuery myOrders;
    private GiftOrderQueryService orderQuery;
    private MockMvc mvc;
    private Jwt currentJwt;

    private static Jwt userJwt(long sub) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(String.valueOf(sub)).claim("role", "USER")
                .build();
    }

    @BeforeEach
    void setUp() {
        myOrders = mock(MyGiftOrderQuery.class);
        orderQuery = mock(GiftOrderQueryService.class);
        currentJwt = userJwt(ME);
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
        GiftController controller = new GiftController(mock(GiftCatalogService.class), mock(GiftSendService.class),
                orderQuery, mock(RedisRateLimiter.class), myOrders);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(jwtResolver)
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void noJwtIs401ProblemDetail() throws Exception {
        currentJwt = null;
        mvc.perform(get("/api/v1/me/gift-orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.trace").doesNotExist());
        verify(myOrders, never()).page(anyLong(), any());
    }

    @Test
    void userIdParamIsIgnoredAndJsonShapeMatches() throws Exception {
        Instant at = Instant.parse("2026-10-18T10:00:00Z");
        GiftOrderPage page = new GiftOrderPage(List.of(
                new GiftOrderRow("tokA", at, "DELIVERED", "PAWCOIN", 3, 30_000L,
                        new GiftOrderRow.GiftView("Bone", "Tulang", "https://cdn/bone.png", 10_000L),
                        new GiftOrderRow.PetView(30L, "Mochi", "https://cdn/p.jpg"), false),
                new GiftOrderRow("tokB", at.minusSeconds(60), "UNDELIVERED_REFUNDED", "QRIS", 1, 50_000L,
                        new GiftOrderRow.GiftView("Cake", "Kue", "https://cdn/cake.png", 50_000L), null, true)),
                "next-cursor");
        when(myOrders.page(eq(ME), isNull())).thenReturn(page);

        String body = mvc.perform(get("/api/v1/me/gift-orders").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].orderToken").value("tokA"))
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.items[0].channel").value("PAWCOIN"))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].total").value(30_000))
                .andExpect(jsonPath("$.items[0].gift.nameEn").value("Bone"))
                .andExpect(jsonPath("$.items[0].gift.nameId").value("Tulang"))
                .andExpect(jsonPath("$.items[0].gift.iconUrl").value("https://cdn/bone.png"))
                .andExpect(jsonPath("$.items[0].gift.unitPrice").value(10_000))
                .andExpect(jsonPath("$.items[0].pet.petId").value(30))
                .andExpect(jsonPath("$.items[0].pet.name").value("Mochi"))
                .andExpect(jsonPath("$.items[0].pet.avatarUrl").value("https://cdn/p.jpg"))
                .andExpect(jsonPath("$.items[0].petGone").value(false))
                .andExpect(jsonPath("$.items[1].petGone").value(true))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("senderId").doesNotContain("ownerId").doesNotContain("paymentIntentId")
                .doesNotContain("idemKey").doesNotContain("giftId").doesNotContain("petName");
        verify(myOrders).page(eq(ME), isNull());
    }

    @Test
    void validCursorIsDecodedAndPassed() throws Exception {
        KeysetCursor c = new KeysetCursor(Instant.parse("2026-10-18T10:00:00.123456Z"), 77L);
        when(myOrders.page(eq(ME), eq(c))).thenReturn(new GiftOrderPage(List.of(), null));
        mvc.perform(get("/api/v1/me/gift-orders").param("cursor", c.encode()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        verify(myOrders).page(ME, c);
    }

    @Test
    void invalidCursorIs400ProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/me/gift-orders").param("cursor", "%%%not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.traceId").exists());
        verify(myOrders, never()).page(anyLong(), any());
    }

    @Test
    void blankCursorIsFirstPage() throws Exception {
        when(myOrders.page(eq(ME), isNull())).thenReturn(new GiftOrderPage(List.of(), null));
        mvc.perform(get("/api/v1/me/gift-orders").param("cursor", ""))
                .andExpect(status().isOk());
        verify(myOrders).page(eq(ME), isNull());
    }

    @Test
    void singleOrderStatusPathStillRoutesToOrderStatus() throws Exception {
        mvc.perform(get("/api/v1/me/gift-orders/{orderToken}", "tokA")).andExpect(status().isOk());
        verify(orderQuery).orderStatus(ME, "tokA");
        verify(myOrders, never()).page(anyLong(), any());
    }
}
