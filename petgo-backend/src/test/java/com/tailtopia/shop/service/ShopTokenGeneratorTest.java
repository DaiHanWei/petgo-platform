package com.tailtopia.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：电商对外标识生成器（Story 1.1 AC3，🔒 安全攸关）。
 *
 * <p>验的不是「能生成字符串」，而是<b>不可枚举</b>——CLAUDE.md 强制护栏。
 * 日期 + 自增序列同样可枚举，故这里必须断言「无公共前缀、无顺序规律」，而不只是「无碰撞」。
 */
class ShopTokenGeneratorTest {

    private final ShopTokenGenerator generator = new ShopTokenGenerator();

    @Test
    @DisplayName("长度与字符表：22 位 Base62，与 CardTokenGenerator 完全一致")
    void lengthAndAlphabet() {
        for (int i = 0; i < 200; i++) {
            String t = generator.generate();
            assertThat(t).hasSize(22);
            assertThat(t).matches("[0-9a-zA-Z]{22}");
        }
    }

    @Test
    @DisplayName("1000 次生成无碰撞")
    void noCollisionIn1000() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(generator.generate())).isTrue();
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    @DisplayName("不可枚举：无公共前缀 —— 排除「固定前缀 + 序列」这类可枚举格式")
    void noSharedPrefix() {
        String first = generator.generate();
        int maxSharedPrefix = 0;
        for (int i = 0; i < 1000; i++) {
            String t = generator.generate();
            int shared = 0;
            while (shared < first.length() && first.charAt(shared) == t.charAt(shared)) {
                shared++;
            }
            maxSharedPrefix = Math.max(maxSharedPrefix, shared);
        }
        // 随机 Base62 下连续 4 位相同的概率约 1000/62^4 ≈ 6.8e-5，实际恒为 0~2
        assertThat(maxSharedPrefix).isLessThan(4);
    }

    @Test
    @DisplayName("不可枚举：相邻两次生成不单调递增 —— 排除自增派生")
    void notMonotonic() {
        int ascending = 0;
        String prev = generator.generate();
        for (int i = 0; i < 500; i++) {
            String cur = generator.generate();
            if (cur.compareTo(prev) > 0) {
                ascending++;
            }
            prev = cur;
        }
        // 自增派生会让这个数接近 500；随机应在 250 上下
        assertThat(ascending).isBetween(180, 320);
    }

    @Test
    @DisplayName("字符分布无明显偏斜 —— 每个 Base62 字符都应出现过")
    void alphabetIsFullyUsed() {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            all.append(generator.generate());
        }
        Set<Character> used = new HashSet<>();
        for (char c : all.toString().toCharArray()) {
            used.add(c);
        }
        assertThat(used).hasSize(62);
    }
}
