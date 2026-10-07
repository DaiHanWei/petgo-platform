package com.tailtopia.passport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.passport.service.PlaceSetHash;
import com.tailtopia.place.service.PlaceIdentityQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** V1.3.2 Story 3.4 · AC2（L0）：章集合 hash 的唯一定义。 */
class PlaceSetHashTest {

    private final PlaceIdentityQuery places = mock(PlaceIdentityQuery.class);
    private final PlaceSetHash hash = new PlaceSetHash(places);
    /** 模拟合并：id → 最终 id。 */
    private final Map<Long, Long> merged = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(places.resolveFinal(anyCollection())).thenAnswer(inv -> {
            Map<Long, Long> out = new HashMap<>();
            for (Object o : (java.util.Collection<?>) inv.getArgument(0)) {
                long id = (Long) o;
                out.put(id, merged.getOrDefault(id, id));
            }
            return out;
        });
    }

    @Test
    void orderIndependentAndDeduplicated() {
        assertThat(hash.of(List.of(3L, 1L, 2L))).isEqualTo(hash.of(List.of(1L, 2L, 3L)))
                .isEqualTo(hash.of(List.of(2L, 2L, 1L, 3L, 3L)));
    }

    @Test
    void isSha256LowerHexOfSortedCommaJoinedIds() throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest("1,2,10".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        // 十进制数值升序（不是字典序：10 在 2 之后）。
        assertThat(hash.of(List.of(10L, 2L, 1L))).isEqualTo(java.util.HexFormat.of().formatHex(d))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void mergedPlaceHashesSameAsItsKeeper() {
        String before = hash.of(List.of(10L, 30L)); // {A, C}
        merged.put(10L, 20L); // A 并入 B
        assertThat(hash.of(List.of(10L, 30L))).isEqualTo(hash.of(List.of(20L, 30L)));
        assertThat(hash.of(List.of(10L, 30L))).isNotEqualTo(before);
    }

    @Test
    void newStampChangesTheSet() {
        assertThat(hash.of(List.of(1L, 2L))).isNotEqualTo(hash.of(List.of(1L, 2L, 3L)));
    }

    @Test
    void emptyThrows() {
        assertThatThrownBy(() -> hash.of(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hash.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
