package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * V1.3.0 批次 A · Story 1.2（L0）：<b>「禁止打卡的健康类里程碑」两侧集合逐字等长</b>（AD-A4.6 ④ ↔ ①）。
 *
 * <p>这条规则天生要两半才成立：**后端显式拒绝**（{@code MilestoneCheckInService}，NFR-11）+
 * **前端隐藏入口**（{@code isAutoOnlyHealthMilestone}）。只改一侧的后果不是"少了一半保护"，
 * 而是两边对「这条能不能打卡」给出不同答案 —— 用户看得到按钮、点下去被拒，或者反过来，
 * 按钮不见了却仍能绕过 UI 打卡。
 *
 * <p>本 story 修的正是这类走散：FR-86 按后缀 {@code M3/M4/M5/M9} 取消打卡，通用清单把
 * 「看兽医」放在 {@code G-M1}、「健康检查 / 疫苗」放在 {@code G-M2}，两个后缀都不命中，
 * 于是这两条**从 V1.1.2 至今一直是打卡类**。没有任何测试会因此变红 —— 所以补上这一条。
 *
 * <p>⚠️ 跨子工程读文件（后端读 App 的 .dart 源码），沿用 {@code MilestoneCatalogI18nTest} 的既有做法。
 * 找不到文件时**明确失败**，绝不静默跳过 —— 静默跳过等于这条防线不存在。
 */
class HealthMilestonesCrossRepoTest {

    /** App 侧那份集合，相对于后端模块根目录（Maven 的工作目录即 petgo-backend/）。 */
    private static final Path APP_SET = Path.of("..", "petgo_app", "lib", "features", "profile",
            "domain", "health_milestones.dart");

    /** 匹配 {@code kAutoOnlyHealthMilestoneCodes = { 'C-M3', 'C-M4', … };} 里的每个 code。 */
    private static final Pattern DART_CODE = Pattern.compile("'([A-Z]-[SML]\\d+)'");

    @Test
    void appSetIsWordForWordIdenticalToBackendSet() {
        assertThat(readAppCodes())
                .as("前端 kAutoOnlyHealthMilestoneCodes 与后端 HealthMilestones.CODES 已走散 —— "
                        + "两侧必须同批改（AD-A4.6 集合 ④ ↔ ①）")
                .containsExactlyInAnyOrderElementsOf(HealthMilestones.CODES);
    }

    /**
     * 本 story 的核心断言：通用宠物那两条确实在集合里。
     *
     * <p>与上一条不是重复 —— 上一条只保证两侧一致，两侧**同时**漏掉 G-M1/G-M2 它照样绿。
     */
    @Test
    void genericPetHealthMilestonesArePresentOnBothSides() {
        assertThat(HealthMilestones.CODES).contains("G-M1", "G-M2");
        assertThat(readAppCodes()).contains("G-M1", "G-M2");
    }

    /**
     * 🔴 反向断言：通用清单的 {@code G-M3}「陪伴满 30 天」/ {@code G-M4}「记录满 10 条」
     * 与健康毫无关系，不得因为撞上猫狗的疫苗 / 驱虫号位而被判成健康类（那会让它们**永远无法打卡**）。
     */
    @Test
    void genericPetNonHealthMilestonesAreNotInTheSet() {
        assertThat(HealthMilestones.CODES).doesNotContain("G-M3", "G-M4");
        assertThat(readAppCodes()).doesNotContain("G-M3", "G-M4");
    }

    /** 集合里的每个 code 都必须真实存在于对应物种的清单里（防手滑写一个不存在的 code）。 */
    @Test
    void everyCodeExistsInItsCatalog() {
        for (String code : HealthMilestones.CODES) {
            assertThat(MilestoneCatalog.byCode(code))
                    .as("健康类集合里的 %s 不在任何清单里", code)
                    .isNotNull();
        }
    }

    private static Set<String> readAppCodes() {
        String src = read(APP_SET);
        int start = src.indexOf("kAutoOnlyHealthMilestoneCodes");
        assertThat(start).as("在 %s 里找不到 kAutoOnlyHealthMilestoneCodes", APP_SET).isNotNegative();
        int end = src.indexOf("};", start);
        assertThat(end).as("kAutoOnlyHealthMilestoneCodes 的集合字面量没有结尾").isNotNegative();

        Set<String> codes = new LinkedHashSet<>();
        Matcher m = DART_CODE.matcher(src.substring(start, end));
        while (m.find()) {
            codes.add(m.group(1));
        }
        assertThat(codes).as("没能从 %s 解析出任何 code", APP_SET).isNotEmpty();
        return codes;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读不到 App 侧文件 " + p.toAbsolutePath()
                    + "（跨库契约测试不得静默跳过）", e);
        }
    }
}
