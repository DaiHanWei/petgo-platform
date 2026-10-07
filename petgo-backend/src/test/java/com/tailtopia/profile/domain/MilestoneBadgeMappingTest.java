package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0 跨库：App 的里程碑徽章映射表（code → 语义键）与 {@link MilestoneCatalog} 一致（V1.3.2 Story 5.1 · AC1.3）。
 *
 * <p>映射按<b>完整 code</b> 寻址（通用套编号与猫狗不对齐：{@code G-S6} 是零食、{@code G-S8} 是点赞），
 * 所以它必须覆盖目录的每一条、不多不少。后端增删里程碑而 App 映射没跟上时，这里先红。
 *
 * <p>⚠️ 跨子工程读 App 源码，找不到文件<b>明确失败</b>，不静默跳过（照 {@link MilestoneCatalogI18nTest}）。
 */
class MilestoneBadgeMappingTest {

    private static final Path APP_BADGES = Path.of("..", "petgo_app", "lib", "features", "profile", "domain",
            "milestone_badge_assets.dart");

    /** 匹配 {@code 'C-S8': 'first_treat',} */
    private static final Pattern DART_ENTRY = Pattern.compile("'([A-Z]-[SML]\\d+)'\\s*:\\s*'([^']*)'");

    private static Map<String, String> appMapping() throws IOException {
        assertThat(Files.exists(APP_BADGES)).as("找不到 App 映射表 %s（跨库测试不允许跳过）", APP_BADGES).isTrue();
        String src = Files.readString(APP_BADGES, StandardCharsets.UTF_8);
        Map<String, String> m = new LinkedHashMap<>();
        Matcher x = DART_ENTRY.matcher(src);
        while (x.find()) {
            assertThat(m.put(x.group(1), x.group(2))).as("映射表重复 code %s", x.group(1)).isNull();
        }
        return m;
    }

    private static Set<String> catalogCodes() {
        return Stream.of(PetType.values())
                .flatMap(t -> MilestoneCatalog.forType(t).stream())
                .map(MilestoneDefinition::code)
                .collect(Collectors.toSet());
    }

    @Test
    void mappingCoversExactlyEveryCatalogCode() throws IOException {
        Set<String> app = appMapping().keySet();
        Set<String> catalog = catalogCodes();
        assertThat(catalog).hasSize(78);
        assertThat(new HashSet<>(app)).as("App 映射与 MilestoneCatalog 的 code 集合必须互为子集").isEqualTo(catalog);
    }

    @Test
    void valuesAreSnakeCaseAndExactlyFortyDistinct() throws IOException {
        Map<String, String> m = appMapping();
        for (String v : m.values()) {
            assertThat(v).matches("^[a-z0-9_]+$");
        }
        assertThat(new HashSet<>(m.values())).hasSize(40);
    }

    /** 🔴 共用语义按语义而非编号对齐：三组抽查。 */
    @Test
    void crossSpeciesSharedKeysAlignBySemanticsNotSuffix() throws IOException {
        Map<String, String> m = appMapping();
        for (String[] g : new String[][] {{"C-S8", "D-S8", "G-S6"}, {"C-M5", "D-M5", "G-M1"},
            {"C-S16", "D-S16", "G-S9"}}) {
            assertThat(Stream.of(g).map(m::get).collect(Collectors.toSet())).as("%s 应同键", String.join("/", g))
                    .hasSize(1);
        }
        assertThat(m.get("G-S8")).isNotEqualTo(m.get("C-S8"));
    }

    /** V1.3.2 Story 5.2：后端 {@link MilestoneBadgeKeys} 与 App 表逐条一致（H5 与 App 同一枚徽章）。 */
    @Test
    void javaTableEqualsDartTable() throws IOException {
        assertThat(MilestoneBadgeKeys.KEYS).isEqualTo(appMapping());
        assertThat(MilestoneBadgeKeys.keyOf("G-S6")).contains("first_treat");
        assertThat(MilestoneBadgeKeys.keyOf("G-S8")).contains("first_like");
        assertThat(MilestoneBadgeKeys.keyOf("X-S8")).isEmpty();
        assertThat(MilestoneBadgeKeys.keyOf(null)).isEmpty();
    }
}
