package com.tailtopia.namemoderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.namemoderation.domain.NameTargetType;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * L0（AC-B4）：默认编码名生成 —— 前缀 / 小写 hex 后缀 / 不由 id 或时间派生 / 唯一性冲突重试。无 DB。
 */
class DefaultNameGeneratorTest {

    private static final Predicate<String> NEVER = name -> false;

    private final DefaultNameGenerator generator = new DefaultNameGenerator();

    @Test
    void nickname_prefixAndLowerHexSuffix() {
        String name = generator.generate(NameTargetType.NICKNAME, NEVER);
        assertThat(name).startsWith("user_");
        String suffix = name.substring("user_".length());
        assertThat(suffix).matches("[0-9a-f]{6,8}"); // 6~8 位小写 hex
    }

    @Test
    void petName_prefixAndLowerHexSuffix() {
        String name = generator.generate(NameTargetType.PET_NAME, NEVER);
        assertThat(name).startsWith("Pet_");
        assertThat(name.substring("Pet_".length())).matches("[0-9a-f]{6,8}");
    }

    @Test
    void notDerivedFromIdOrTime_repeatedCallsDiffer() {
        // 生成器不接收 id/时间 → 多次生成后缀随机不同（抽样 50 次几乎必然全异）。
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            seen.add(generator.generate(NameTargetType.NICKNAME, NEVER));
        }
        assertThat(seen).hasSizeGreaterThan(1);
    }

    @Test
    void collision_retriesUntilUnique() {
        // 前 2 个候选视为已占用 → 生成器重试，最终返回第 3 个未占用候选。
        int[] calls = {0};
        Predicate<String> existsFirstTwo = name -> calls[0]++ < 2;
        String name = generator.generate(NameTargetType.PET_NAME, existsFirstTwo);
        assertThat(name).startsWith("Pet_");
        assertThat(calls[0]).isGreaterThanOrEqualTo(3); // 至少判到第 3 次才通过
    }

    @Test
    void nickname_fitsColumnLength() {
        // 昵称列 length=20；"user_" (5) + 最多 8 hex = 13，安全。
        for (int i = 0; i < 20; i++) {
            assertThat(generator.generate(NameTargetType.NICKNAME, NEVER).length()).isLessThanOrEqualTo(20);
        }
    }
}
