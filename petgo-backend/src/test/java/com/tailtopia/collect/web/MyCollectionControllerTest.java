package com.tailtopia.collect.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.collect.dto.CollectedPlaceRow;
import com.tailtopia.collect.dto.CollectedPostRow;
import com.tailtopia.collect.dto.CollectionPage;
import com.tailtopia.collect.service.MyCollectionQuery;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.shared.error.AppException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

/** L0：V1.4.0 ④ Story 2.2 · AC1 / AC3 / AC5 —— 我的收藏接口：只认 JWT 本人、JSON 形状、不外露内部 id。 */
@ExtendWith(MockitoExtension.class)
class MyCollectionControllerTest {

    @Mock MyCollectionQuery query;

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static Jwt user(long id) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(String.valueOf(id)).claim("role", "USER").build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object o) {
        return json.convertValue(o, Map.class);
    }

    @Test
    void missingJwtIsUnauthorized() {
        MyCollectionController c = new MyCollectionController(query);
        AppException e = catchThrowableOfType(AppException.class, () -> c.posts(null, null, null));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(query);
    }

    @Test
    void acceptsNoUserIdParameter() {
        for (Method m : MyCollectionController.class.getDeclaredMethods()) {
            if (m.getName().equals("places")) {
                assertThat(m.getParameterTypes()).containsExactly(Jwt.class, String.class);
            }
            // 帖子收藏多一个 X-App-Capabilities 请求头（专题线 video：旧版隐藏视频帖），仍不接受任何 userId。
            if (m.getName().equals("posts")) {
                assertThat(m.getParameterTypes()).containsExactly(Jwt.class, String.class, String.class);
                assertThat(m.getParameters()[2].getAnnotation(
                        org.springframework.web.bind.annotation.RequestHeader.class)).isNotNull();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void postPageShape() {
        Instant t = Instant.parse("2026-10-18T05:00:00Z");
        when(query.posts(7L, null, false)).thenReturn(new CollectionPage<>(List.of(
                new CollectedPostRow(1L, t, true, new CollectedPostRow.Post(ContentType.DAILY, "hi", "https://cdn/1.jpg",
                        new CollectedPostRow.Author("Rina", null))),
                new CollectedPostRow(2L, t, false, null)), null, false));

        Map<String, Object> page = wire(new MyCollectionController(query).posts(user(7L), null, null));
        assertThat(page.keySet()).containsExactlyInAnyOrder("items", "hasMore");
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items.get(0).keySet()).containsExactlyInAnyOrder("postId", "collectedAt", "available", "post");
        assertThat(((Map<String, Object>) items.get(0).get("post")).keySet())
                .containsExactlyInAnyOrder("type", "excerpt", "coverUrl", "author");
        assertThat(items.get(1).keySet()).containsExactlyInAnyOrder("postId", "collectedAt", "available");
    }

    @Test
    @SuppressWarnings("unchecked")
    void placePageShapeHasNoInternalIds() {
        Instant t = Instant.parse("2026-10-18T05:00:00Z");
        when(query.places(7L, "c")).thenReturn(new CollectionPage<>(List.of(
                new CollectedPlaceRow("tokA", t, true, "Kopi", new CollectedPlaceRow.Place(PlaceType.CAFE, null, "Jl")),
                new CollectedPlaceRow("tokB", t, false, "Gone", null)), "next", true));

        Map<String, Object> page = wire(new MyCollectionController(query).places(user(7L), "c"));
        assertThat(page).containsEntry("nextCursor", "next").containsEntry("hasMore", true);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items.get(0).keySet()).containsExactlyInAnyOrder("placeToken", "collectedAt", "available", "name",
                "place");
        assertThat(items.get(1).keySet()).containsExactlyInAnyOrder("placeToken", "collectedAt", "available", "name");
        for (Map<String, Object> it : items) {
            assertThat(it).doesNotContainKeys("id", "userId", "placeId", "authorId", "mergedIntoId");
        }
    }
}
