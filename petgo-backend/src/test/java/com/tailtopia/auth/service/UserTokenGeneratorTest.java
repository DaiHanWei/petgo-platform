package com.tailtopia.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：用户对外 token（V1.3.4 follow · Story 1.1 · AC2）—— 32 位 base62、不重复。 */
class UserTokenGeneratorTest {

    @Test
    void generates32CharBase62() {
        assertThat(UserTokenGenerator.generate()).hasSize(32).matches("[0-9a-zA-Z]{32}");
    }

    @Test
    void tokensAreDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(UserTokenGenerator.generate());
        }
        assertThat(seen).hasSize(1000);
    }
}
