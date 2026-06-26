package com.tailtopia.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L0：症状语言确定性检测（印尼/英语判得出，其它语言/空/持平 → null 交调用方回落 locale）。 */
class SymptomLanguageDetectorTest {

    @Test
    void indonesianSymptom_detectsId() {
        assertThat(SymptomLanguageDetector.detect(
                "Kucing saya sakit, terus-terusan diare, harus bagaimana?")).isEqualTo("id");
    }

    @Test
    void englishSymptom_detectsEn() {
        assertThat(SymptomLanguageDetector.detect(
                "My cat has been sick with diarrhea, what should I do?")).isEqualTo("en");
    }

    @Test
    void frenchSymptom_returnsNull_soCallerFallsBackToLocale() {
        // 其它语言判不出 → null（调用方回落 App locale）。
        assertThat(SymptomLanguageDetector.detect(
                "Mon chat est malade, il a la diarrhée en permanence.")).isNull();
    }

    @Test
    void blankOrNull_returnsNull() {
        assertThat(SymptomLanguageDetector.detect(null)).isNull();
        assertThat(SymptomLanguageDetector.detect("   ")).isNull();
    }

    @Test
    void tie_returnsNull() {
        // 英印各一强词（cat / kucing）→ 持平 → null。
        assertThat(SymptomLanguageDetector.detect("cat kucing")).isNull();
    }

    @Test
    void shortIndonesian_stillDetectsId() {
        assertThat(SymptomLanguageDetector.detect("anjing saya muntah")).isEqualTo("id");
    }
}
