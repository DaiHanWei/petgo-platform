package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * L0（V1.3.2 Story 5.2 · AC2.3）：code → {@code /milestone/<键>.webp}，按完整 code 查表、素材不在包里 → empty。
 *
 * <p>测试 classpath 里只放了一张 {@code static/milestone/first_treat.webp}（**只在 test resources**）。
 * 素材入库（2026-10-05）后 main 也有整套 {@code static/milestone/}，但 {@code classpath:}（非 {@code classpath*:}）
 * 只取第一个命中的目录，而 surefire 把 test-classes 排在 classes 前 —— 测试里看到的仍只有这一张，用例口径不变。
 */
class MilestoneBadgeResolverTest {

    private final MilestoneBadgeResolver resolver = new MilestoneBadgeResolver();

    @Test
    void existingAssetResolvesForEveryCodeSharingTheKey() {
        assertThat(resolver.urlFor("C-S8")).contains("/milestone/first_treat.webp");
        assertThat(resolver.urlFor("D-S8")).contains("/milestone/first_treat.webp");
        assertThat(resolver.urlFor("G-S6")).contains("/milestone/first_treat.webp");
    }

    @Test
    void missingAssetOrUnknownCodeIsEmpty() {
        assertThat(resolver.urlFor("G-S8")).as("点赞那枚素材不在包里（且不按后缀串到零食）").isEmpty();
        assertThat(resolver.urlFor("C-S1")).isEmpty();
        assertThat(resolver.urlFor("X-S8")).isEmpty();
        assertThat(resolver.urlFor(null)).isEmpty();
    }

    @Test
    void scanFailureMeansNoAssets() {
        ResourcePatternResolver broken = new PathMatchingResourcePatternResolver() {
            @Override
            public Resource[] getResources(String locationPattern) throws IOException {
                throw new IOException("boom");
            }
        };
        assertThat(new MilestoneBadgeResolver(broken).urlFor("C-S8")).isEmpty();
    }
}
