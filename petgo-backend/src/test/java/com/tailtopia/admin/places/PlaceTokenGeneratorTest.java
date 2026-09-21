package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.places.service.PlaceTokenGenerator;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** L0：场所 token 22 位 Base62、1 万次无重复（V1.3.0 Story 5.1 AC3 / T5）。字符表与长度须与 CardTokenGenerator / ShopTokenGenerator 一致。 */
class PlaceTokenGeneratorTest {

    private final PlaceTokenGenerator generator = new PlaceTokenGenerator();

    @Test
    void lengthAlphabetAndNoCollisionsIn10k() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String t = generator.generate();
            assertThat(t).hasSize(22).matches("[0-9a-zA-Z]{22}");
            assertThat(seen.add(t)).as("duplicate token at %d", i).isTrue();
        }
    }
}
