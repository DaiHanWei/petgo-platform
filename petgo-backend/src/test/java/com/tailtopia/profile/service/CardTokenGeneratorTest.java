package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：名片 token 不可枚举——长度足够、base62、批量无碰撞。 */
class CardTokenGeneratorTest {

    private final CardTokenGenerator generator = new CardTokenGenerator();

    @Test
    void tokenIsBase62AndLongEnough() {
        String t = generator.generate();
        assertThat(t).hasSize(22);
        assertThat(t).matches("[0-9a-zA-Z]+");
    }

    @Test
    void tokensAreUniqueAcrossManyDraws() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(generator.generate())).isTrue();
        }
    }
}
