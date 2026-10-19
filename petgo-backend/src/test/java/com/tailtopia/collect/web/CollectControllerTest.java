package com.tailtopia.collect.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.collect.dto.CollectResponse;
import com.tailtopia.collect.service.CollectService;
import com.tailtopia.collect.service.PlaceCollectService;
import com.tailtopia.shared.error.AppException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import tools.jackson.databind.json.JsonMapper;

/** L0：V1.4.0 ④ Story 2.1 · AC2 / AC5 —— {@link CollectController}（直接 new + mock）。 */
@ExtendWith(MockitoExtension.class)
class CollectControllerTest {

    @Mock CollectService posts;
    @Mock PlaceCollectService places;

    private CollectController controller() {
        return new CollectController(posts, places);
    }

    private static Jwt user(long id) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(String.valueOf(id)).claim("role", "USER").build();
    }

    @Test
    void missingJwtIsUnauthorized() {
        AppException e = catchThrowableOfType(AppException.class, () -> controller().collectPost(null, 55L));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(posts, places);
    }

    @Test
    void delegatesWithJwtSubject() {
        when(posts.collect(55L, 7L)).thenReturn(true);
        when(places.uncollect("tok", 7L)).thenReturn(false);
        assertThat(controller().collectPost(user(7L), 55L).collected()).isTrue();
        assertThat(controller().uncollectPlace(user(7L), "tok").collected()).isFalse();
        controller().uncollectPost(user(7L), 55L);
        controller().collectPlace(user(7L), "tok");
        verify(posts).uncollect(55L, 7L);
        verify(places).collect("tok", 7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void responseHasOnlyCollectedKey() {
        Map<String, Object> wire = JsonMapper.builder().build().convertValue(new CollectResponse(true), Map.class);
        assertThat(wire).isEqualTo(Map.of("collected", true));
    }

    @Test
    void mappingPaths() throws NoSuchMethodException {
        Class<?> c = CollectController.class;
        assertThat(c.getMethod("collectPost", Jwt.class, long.class).getAnnotation(PostMapping.class).value())
                .containsExactly("/api/v1/content-posts/{id}/collect");
        assertThat(c.getMethod("uncollectPost", Jwt.class, long.class).getAnnotation(DeleteMapping.class).value())
                .containsExactly("/api/v1/content-posts/{id}/collect");
        assertThat(c.getMethod("collectPlace", Jwt.class, String.class).getAnnotation(PostMapping.class).value())
                .containsExactly("/api/v1/places/{token}/collect");
        assertThat(c.getMethod("uncollectPlace", Jwt.class, String.class).getAnnotation(DeleteMapping.class).value())
                .containsExactly("/api/v1/places/{token}/collect");
    }
}
