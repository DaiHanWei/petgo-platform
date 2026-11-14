package com.tailtopia.profile.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 onboarding-recall Story 1.1 · AC7 / OR-5 —— 游客 10 分钟进程内快照。 */
class PublicPetRecommendationSnapshotTest {

    private static final Instant T0 = Instant.parse("2026-11-14T10:00:00Z");

    private PetRecommendationService service;
    private MutableClock clock;
    private PublicPetRecommendationSnapshot snapshot;

    @BeforeEach
    void setUp() {
        service = mock(PetRecommendationService.class);
        clock = new MutableClock(T0);
        snapshot = new PublicPetRecommendationSnapshot(service, Duration.ofMinutes(10), clock);
    }

    private static List<RecommendedPetResponse> cards(long firstId, int n) {
        List<RecommendedPetResponse> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new RecommendedPetResponse(firstId + i, "P", "a", com.tailtopia.profile.domain.PetType.CAT,
                    null, null, 1L, null, null, 0L, false, RecommendMatch.GLOBAL));
        }
        return out;
    }

    @Test
    void 首个请求按固定10张构建并按limit截断() {
        when(service.publicFirstPage(eq(10), any())).thenReturn(cards(1, 10));
        assertThat(snapshot.firstPage(6)).hasSize(6).extracting(RecommendedPetResponse::petId)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        verify(service).publicFirstPage(eq(10), any());
    }

    @Test
    void limit上限10() {
        when(service.publicFirstPage(anyInt(), any())).thenReturn(cards(1, 10));
        assertThat(snapshot.firstPage(50)).hasSize(10);
        assertThat(snapshot.firstPage(0)).hasSize(1);
    }

    @Test
    void TTL内复用快照不重查() {
        when(service.publicFirstPage(anyInt(), any())).thenReturn(cards(1, 10));
        snapshot.firstPage(6);
        clock.set(T0.plus(Duration.ofMinutes(9)).plusSeconds(59));
        snapshot.firstPage(6);
        verify(service, times(1)).publicFirstPage(anyInt(), any());
    }

    @Test
    void 过期后下一个请求重建() {
        when(service.publicFirstPage(anyInt(), any())).thenReturn(cards(1, 10), cards(100, 10));
        assertThat(snapshot.firstPage(1).getFirst().petId()).isEqualTo(1L);
        clock.set(T0.plus(Duration.ofMinutes(10)));
        assertThat(snapshot.firstPage(1).getFirst().petId()).isEqualTo(100L);
        verify(service, times(2)).publicFirstPage(anyInt(), any());
    }

    @Test
    void 重建失败时返回旧值() {
        when(service.publicFirstPage(anyInt(), any()))
                .thenReturn(cards(1, 10))
                .thenThrow(new IllegalStateException("db down"));
        snapshot.firstPage(3);
        clock.set(T0.plus(Duration.ofMinutes(11)));
        assertThat(snapshot.firstPage(3)).extracting(RecommendedPetResponse::petId).containsExactly(1L, 2L, 3L);
    }

    @Test
    void 冷启动构建失败原样抛出() {
        when(service.publicFirstPage(anyInt(), any())).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> snapshot.firstPage(6)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 不引入任何缓存依赖() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/tailtopia/profile/recommend/PublicPetRecommendationSnapshot.java"));
        String code = src.lines()
                .filter(l -> !l.stripLeading().startsWith("*") && !l.stripLeading().startsWith("//")
                        && !l.stripLeading().startsWith("/*"))
                .reduce("", (a, b) -> a + "\n" + b);
        for (String forbidden : List.of("@Cacheable", "Caffeine", "RedisTemplate", "CacheManager")) {
            assertThat(code).doesNotContain(forbidden);
        }
    }

    /** 可拨的测试时钟。 */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void set(Instant at) {
            now.set(at);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
