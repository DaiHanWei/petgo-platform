package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.PetSex;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * L0：H5 名片页的三段纯文本逻辑（V1.1.6 Story 1.2）。
 *
 * <p>都是纯函数，不碰 DB / HTTP，故放 L0。三段各自防一类事故：
 * <ul>
 *   <li><b>元信息行</b>：品种 / 性别 / 年龄 / 主人四段<b>全都可能缺</b>（E2 屏就只有两段）。
 *       拼错的表现是页面上出现 {@code 「Kucing · · bersama Rina」} 这种连续分隔符。</li>
 *   <li><b>年龄</b>：生日可空；不足一岁要说月而不是「0 岁」。</li>
 *   <li><b>相对时间</b>：H5 全篇印尼语，这里漏一处就是页面上蹦出中文或英文。</li>
 * </ul>
 */
class CardPageTextTest {

    // ===== 元信息行：逐段判空，不留空段 =====

    @Test
    void metaLineJoinsAllFourSegments() {
        assertThat(CardPageController.metaLine("Kucing Domestik", PetSex.FEMALE, "2 tahun", "Rina"))
                .isEqualTo("Kucing Domestik · Betina · 2 tahun · bersama Rina");
    }

    @Test
    void metaLineWithOnlyBreedAndSex() {
        // E2 屏的实际形态：Golden Retriever · Jantan
        assertThat(CardPageController.metaLine("Golden Retriever", PetSex.MALE, null, null))
                .isEqualTo("Golden Retriever · Jantan");
    }

    /** 🛡 任何一段缺失都不得留下空位 —— 这是本组最要紧的一条。 */
    @Test
    void metaLineNeverProducesEmptySegments() {
        String[] results = {
            CardPageController.metaLine(null, PetSex.FEMALE, "2 tahun", "Rina"),
            CardPageController.metaLine("Kucing", null, "2 tahun", "Rina"),
            CardPageController.metaLine("Kucing", PetSex.MALE, null, "Rina"),
            CardPageController.metaLine("Kucing", PetSex.MALE, "2 tahun", null),
            CardPageController.metaLine(null, null, null, "Rina"),
            CardPageController.metaLine("  ", PetSex.MALE, "  ", null),
        };
        for (String r : results) {
            assertThat(r).doesNotContain("··").doesNotContain("·  ·");
            assertThat(r).isNotBlank();
            assertThat(r.trim()).doesNotStartWith("·").doesNotEndWith("·");
        }
    }

    /** 四段全缺 → 返回 null，模板整行不渲染（不是渲染一个空行）。 */
    @Test
    void metaLineIsNullWhenNothingToShow() {
        assertThat(CardPageController.metaLine(null, null, null, null)).isNull();
        assertThat(CardPageController.metaLine("", null, "  ", "")).isNull();
    }

    /** 性别用印尼语，且只有两个值（档案侧没有 UNKNOWN —— 那是身份证那套）。 */
    @Test
    void sexRendersInIndonesian() {
        assertThat(CardPageController.metaLine(null, PetSex.MALE, null, null)).isEqualTo("Jantan");
        assertThat(CardPageController.metaLine(null, PetSex.FEMALE, null, null)).isEqualTo("Betina");
    }

    // ===== 年龄 =====

    @Test
    void ageTextInYearsAndMonths() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        assertThat(CardPageController.ageText(LocalDate.of(2024, 3, 10), today)).isEqualTo("2 tahun");
        assertThat(CardPageController.ageText(LocalDate.of(2026, 2, 17), today)).isEqualTo("6 bulan");
        assertThat(CardPageController.ageText(LocalDate.of(2025, 8, 17), today)).isEqualTo("1 tahun");
    }

    /** 不足一个月 → 说天，不说「0 bulan」。 */
    @Test
    void ageTextForNewborn() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        assertThat(CardPageController.ageText(LocalDate.of(2026, 8, 14), today)).isEqualTo("3 hari");
        assertThat(CardPageController.ageText(LocalDate.of(2026, 8, 17), today)).isEqualTo("baru lahir");
    }

    @Test
    void ageTextIsNullWhenBirthdayMissing() {
        assertThat(CardPageController.ageText(null, LocalDate.of(2026, 8, 17))).isNull();
    }

    /** 生日在未来（脏数据）→ 不渲染，而不是显示负数。 */
    @Test
    void ageTextIsNullForFutureBirthday() {
        assertThat(CardPageController.ageText(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 8, 17))).isNull();
    }

    // ===== 相对时间（最新里程碑动态） =====

    @Test
    void relativeTimeInIndonesian() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        assertThat(CardPageController.relativeDays(today, today)).isEqualTo("HARI INI");
        assertThat(CardPageController.relativeDays(LocalDate.of(2026, 8, 16), today)).isEqualTo("KEMARIN");
        assertThat(CardPageController.relativeDays(LocalDate.of(2026, 8, 14), today)).isEqualTo("3 HARI LALU");
        assertThat(CardPageController.relativeDays(LocalDate.of(2026, 8, 3), today)).isEqualTo("2 MINGGU LALU");
        assertThat(CardPageController.relativeDays(LocalDate.of(2026, 5, 17), today)).isEqualTo("3 BULAN LALU");
        assertThat(CardPageController.relativeDays(LocalDate.of(2024, 8, 17), today)).isEqualTo("2 TAHUN LALU");
    }

    /** 🛡 全部相对时间文案都不含中日韩字符（H5 页面不得出现中文，AC3）。 */
    @Test
    void relativeTimeNeverContainsCjk() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        for (int back = 0; back <= 800; back++) {
            String s = CardPageController.relativeDays(today.minusDays(back), today);
            assertThat(s.codePoints().noneMatch(cp -> cp >= 0x2E80 && cp <= 0x9FFF))
                    .as("第 %d 天前的文案含中日韩字符：%s", back, s)
                    .isTrue();
        }
    }

    /** 未来时间（时钟偏差）→ 当作今天，不出现负数。 */
    @Test
    void relativeTimeClampsFutureToToday() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        assertThat(CardPageController.relativeDays(LocalDate.of(2026, 9, 1), today)).isEqualTo("HARI INI");
    }
}
