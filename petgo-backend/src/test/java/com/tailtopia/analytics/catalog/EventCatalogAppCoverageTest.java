package com.tailtopia.analytics.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.analytics.AnalyticsPipelineState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * L0：App 里每个字面量事件名都已登记（analytics-pipeline Story 1.2 AC1「全部」的机器证据）。
 *
 * <p>扫 {@code ../petgo_app/lib/**}{@code /*.dart}，正则与 App 侧守卫一致
 * （{@code petgo_app/test/analytics/v140_events_inventory_test.dart}），但按<b>整文件</b>匹配，
 * 所以 {@code Analytics.capture(} 换行后才写事件名的调用点也算进来。另外顺带覆盖两种已知的非字面量写法：
 * 三元式两侧的字面量、{@code const String k...Event = '...'} 事件名常量。
 *
 * <p>App 侧的反向比对（清单 → 代码）由 Story 2.6 做。仓库里没有 App 目录时跳过（单独检出后端时不报错）。
 */
class EventCatalogAppCoverageTest {

    private static final Path APP_LIB = Path.of("..", "petgo_app", "lib");

    private static final Pattern LITERAL = Pattern.compile("Analytics\\.capture\\(\\s*'([A-Za-z0-9_]+)'");
    private static final Pattern TERNARY = Pattern.compile(
            "Analytics\\.capture\\(\\s*[A-Za-z0-9_.!]+\\s*\\?\\s*'([A-Za-z0-9_]+)'\\s*:\\s*'([A-Za-z0-9_]+)'");
    private static final Pattern EVENT_CONST = Pattern.compile(
            "const\\s+String\\s+k[A-Za-z0-9_]*Event\\s*=\\s*'([A-Za-z0-9_]+)'");

    @Test
    void everyAppEventLiteralIsRegistered() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(APP_LIB), "petgo_app/lib 不存在，跳过");

        Map<String, String> found = new TreeMap<>(); // 事件名 -> 首次出现的文件
        try (Stream<Path> files = Files.walk(APP_LIB)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".dart"))::iterator) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                collect(LITERAL, src, f, found);
                collect(TERNARY, src, f, found);
                collect(EVENT_CONST, src, f, found);
            }
        }
        // 门面内部派生、调用点不出现字面量的事件
        found.putIfAbsent("button_tapped", "lib/core/analytics/analytics.dart（Analytics.buttonTapped）");

        EventCatalog catalog = new EventCatalog(EventCatalog.DEFAULT_LOCATION, new DefaultResourceLoader(),
                new AnalyticsPipelineState(), mock(OpsAlertService.class), "test");
        catalog.afterPropertiesSet();
        assertThat(catalog.isLoaded()).isTrue();

        Map<String, String> missing = new TreeMap<>();
        found.forEach((name, file) -> {
            if (!catalog.isRegistered(name)) {
                missing.put(name, file);
            }
        });
        // 防扫描静默失效恒绿
        assertThat(found).as("扫出的事件名过少，正则或路径可能失效").hasSizeGreaterThan(190);
        assertThat(missing)
                .as("以下 App 事件未在 event-catalog.yaml 登记（新增事件三步：登记 yaml → 加 Dart 常量 → 比对测试变绿）")
                .isEmpty();
    }

    private static void collect(Pattern p, String src, Path file, Map<String, String> out) {
        Matcher m = p.matcher(src);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                out.putIfAbsent(m.group(g), APP_LIB.relativize(file).toString());
            }
        }
    }
}
