package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0：里程碑印尼语标题（V1.1.6 Story 1.2 · AC3）。
 *
 * <p>H5 分享页是<b>服务端渲染</b>的 Thymeleaf，拿不到 App 那套客户端本地化，所以后端必须自带一份印尼语。
 * 于是同一套文案出现在两个代码库里，<b>走散只是时间问题</b> —— 表现是同一个里程碑在 App 里叫一个名、
 * 在分享页叫另一个名，而用户完全可能同时看到这两处（App 里看，然后把名片分享出去）。
 *
 * <p>本测试把两边逐条钉死。<b>它是那条"三处必须同步"书面约定的执行者</b>：
 * 光靠 Javadoc 里写一句约定，漏改时没有任何东西会响。
 *
 * <p>⚠️ 本测试<b>跨子工程读文件</b>（后端读 App 的 .dart 源码）。项目已有同类先例：
 * 埋点的 {@code distinctIdFor} 与后端 {@code AnalyticsDistinctId} 就靠一个"同一个已知向量"的
 * 跨语言契约测试守着。找不到文件时<b>明确失败</b>，绝不静默跳过 —— 静默跳过等于这条防线不存在。
 */
class MilestoneCatalogI18nTest {

    /** App 侧那张表，相对于后端模块根目录（Maven 的工作目录即 petgo-backend/）。 */
    private static final Path APP_TITLES =
            Path.of("..", "petgo_app", "lib", "features", "profile", "domain", "milestone_titles.dart");

    /** 匹配 {@code 'C-S1': (en: 'Profile created', id: 'Profil dibuat'),} */
    private static final Pattern DART_ENTRY = Pattern.compile(
            "'([A-Z]-[SML]\\d+)'\\s*:\\s*\\(\\s*en:\\s*'((?:[^'\\\\]|\\\\.)*)'\\s*,\\s*id:\\s*'((?:[^'\\\\]|\\\\.)*)'\\s*\\)");

    private static List<MilestoneDefinition> all() {
        return Stream.of(PetType.values())
                .flatMap(t -> MilestoneCatalog.forType(t).stream())
                .toList();
    }

    // ===== ① 自身完整性：每条都有印尼语，且不含中文 =====

    @Test
    void everyMilestoneHasIndonesianTitle() {
        assertThat(all()).isNotEmpty();
        for (MilestoneDefinition d : all()) {
            assertThat(d.titleId())
                    .as("里程碑 %s 缺印尼语标题", d.code())
                    .isNotNull()
                    .isNotBlank();
        }
    }

    /**
     * 印尼语标题里<b>不得混进中日韩字符</b>。
     *
     * <p>这条防的是「复制了中文那一列忘了改」——最典型的漏改形态。
     * emoji 是允许的（🎂 / 🎓 本就在文案里）。
     */
    @Test
    void indonesianTitlesContainNoCjk() {
        for (MilestoneDefinition d : all()) {
            assertThat(d.titleId().codePoints().noneMatch(MilestoneCatalogI18nTest::isCjk))
                    .as("里程碑 %s 的印尼语标题里混进了中日韩字符：%s", d.code(), d.titleId())
                    .isTrue();
        }
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)     // CJK 统一表意
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 扩展 A
                || (cp >= 0x3000 && cp <= 0x303F)  // CJK 标点（、。「」等）
                || (cp >= 0xFF00 && cp <= 0xFFEF); // 全角字符
    }

    // ===== ② 跨代码库契约：与 App 那份表逐条相同 =====

    /**
     * 🛡 后端 {@code titleId} 必须与 App {@code kMilestoneTitles} 的 {@code id} <b>逐条完全相同</b>。
     *
     * <p>失败时怎么办：<b>以 App 那份为准</b>（它是先有的、且 App 内一切显示都走它），
     * 把后端这边改成一致；若确实要改文案，<b>两处一起改</b>。
     */
    @Test
    void backendIndonesianTitlesMatchAppSource() throws IOException {
        Map<String, String> app = readAppTitles();

        for (MilestoneDefinition d : all()) {
            assertThat(app)
                    .as("App 的 milestone_titles.dart 里没有 %s —— 后端新增了里程碑却没同步 App", d.code())
                    .containsKey(d.code());
            assertThat(d.titleId())
                    .as("里程碑 %s 的印尼语两边不一致：后端=「%s」App=「%s」。"
                            + "以 App 那份为准改后端；若确实要改文案，两处一起改。",
                            d.code(), d.titleId(), app.get(d.code()))
                    .isEqualTo(app.get(d.code()));
        }
    }

    /** 反向：App 有而后端没有的 code —— 说明 App 那份表有了孤儿项。 */
    @Test
    void appHasNoOrphanCodes() throws IOException {
        List<String> backendCodes = all().stream().map(MilestoneDefinition::code).toList();
        assertThat(readAppTitles().keySet())
                .as("App 的 milestone_titles.dart 里有后端目录中不存在的 code（孤儿项）")
                .allMatch(backendCodes::contains);
    }

    /** 两边条目数一致（猫 31 + 狗 31 + 通用 16 = 78）。 */
    @Test
    void bothSidesHaveSameEntryCount() throws IOException {
        assertThat(readAppTitles()).hasSameSizeAs(all());
        assertThat(all()).hasSize(78);
    }

    // ===== 读取 App 源码 =====

    private static Map<String, String> readAppTitles() throws IOException {
        // ⚠️ 找不到就失败，不跳过 —— 静默跳过等于这条防线不存在。
        assertThat(Files.exists(APP_TITLES))
                .as("找不到 App 的里程碑文案表：%s（后端测试的工作目录应为 petgo-backend/）。"
                        + "若该文件被移动或改名，请同步修正本测试的路径常量，不要删掉这条契约测试。",
                        APP_TITLES.toAbsolutePath().normalize())
                .isTrue();

        String src = Files.readString(APP_TITLES, StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = DART_ENTRY.matcher(src);
        while (m.find()) {
            out.put(m.group(1), m.group(3)); // group(3) = id
        }
        // 正则没匹配到任何东西 = 那份表的写法变了，本测试已失效（比"通过"更危险）。
        assertThat(out)
                .as("从 %s 里一条都没解析出来 —— 该文件的写法可能变了，本测试的正则需同步更新",
                        APP_TITLES.getFileName())
                .isNotEmpty();
        return out;
    }
}
