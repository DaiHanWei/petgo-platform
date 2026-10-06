package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.MilestoneBadgeKeys;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 里程碑徽章 H5 取图（V1.3.2 Story 5.2）：code → {@code /milestone/<语义键>.webp}，素材不在包里 → empty（模板回落 🏆）。
 *
 * <p>素材可用性**启动时扫一次** {@code classpath:static/milestone/*.webp}：jar 内资源运行期不会变，
 * 不要每次请求 {@code exists()}。放素材 = App {@code assets/milestone/} 与后端 {@code static/milestone/} 两边同名同放
 * （{@code MilestoneBadgeAssetSyncTest} 按文件名 + 哈希钉死）。
 */
@Component
public class MilestoneBadgeResolver {

    private static final Logger log = LoggerFactory.getLogger(MilestoneBadgeResolver.class);
    static final String PATTERN = "classpath:static/milestone/*.webp";

    private final Set<String> availableKeys;

    public MilestoneBadgeResolver() {
        this(new PathMatchingResourcePatternResolver());
    }

    MilestoneBadgeResolver(ResourcePatternResolver resources) {
        Set<String> keys = new HashSet<>();
        try {
            for (Resource r : resources.getResources(PATTERN)) {
                String name = r.getFilename();
                if (name != null && name.endsWith(".webp")) {
                    keys.add(name.substring(0, name.length() - ".webp".length()));
                }
            }
        } catch (IOException e) {
            // 扫不到就当一枚都没有：页面一律回落，不影响分享页本身。
            log.warn("里程碑徽章素材扫描失败（按无素材处理）cls={}", e.getClass().getSimpleName());
        }
        this.availableKeys = Set.copyOf(keys);
    }

    /** 该 code 的徽章 URL；code 不在映射表或素材未入包 → empty。 */
    public Optional<String> urlFor(String code) {
        return MilestoneBadgeKeys.keyOf(code)
                .filter(availableKeys::contains)
                .map(key -> "/milestone/" + key + ".webp");
    }
}
