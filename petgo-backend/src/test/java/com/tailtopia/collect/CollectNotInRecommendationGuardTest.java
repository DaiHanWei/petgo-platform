package com.tailtopia.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0：V1.4.0 ④ Story 2.1 · AC8（AD-12、PRD AC12）—— 收藏<b>不接入推荐</b>：推荐与排序代码不读两张收藏表。
 *
 * <p>扫源码（范式同 {@code TailsonalityNoQuotaSourceGuardTest}）。要改口径先回 PRD。
 */
class CollectNotInRecommendationGuardTest {

    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java/com/tailtopia/content/rank"),
            Path.of("src/main/java/com/tailtopia/profile/recommend"));

    private static final List<String> FORBIDDEN = List.of(
            "content_collections", "place_collections", "ContentCollection", "PlaceCollection",
            "com.tailtopia.collect");

    @Test
    void rankingAndRecommendationNeverTouchCollections() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path root : ROOTS) {
            assertThat(root).as("扫描目录存在（包被挪走了就改这里）").isDirectory();
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                    scanned++;
                    String src = Files.readString(p, StandardCharsets.UTF_8);
                    for (String bad : FORBIDDEN) {
                        if (src.contains(bad)) {
                            offenders.add(p + " → " + bad);
                        }
                    }
                }
            }
        }
        assertThat(scanned).isPositive();
        assertThat(offenders).as("收藏不接入推荐（PRD AC12）").isEmpty();
    }
}
