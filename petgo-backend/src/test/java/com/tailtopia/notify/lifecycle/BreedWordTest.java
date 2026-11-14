package com.tailtopia.notify.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.notify.domain.NotificationType;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * L0：品种词（V1.5.0 onboarding-recall Story 6.1 · AC8）—— 有品种用品种（trim）；没有 → 按收件人语言取物种词
 * {@code notify.species.<PetType>}（ID Kucing / Anjing / Anabul，EN cat / dog / pet），经真实 properties 渲染整句。
 */
class BreedWordTest {

    private static ReloadableResourceBundleMessageSource messages() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:i18n/messages");
        ms.setDefaultEncoding(StandardCharsets.UTF_8.name());
        ms.setFallbackToSystemLocale(false);
        return ms;
    }

    private static LifecyclePlannedPush squad(String breed, String petType) {
        return new LifecyclePlannedPush(1L, NotificationType.LIFECYCLE_D1, "ONCE", LifecycleVariant.DISCOVER, "Mochi",
                LifecycleLayer.D, "SQUAD", breed, petType, 0);
    }

    private static String render(LifecyclePlannedPush p, String suffix, Locale locale) {
        return messages().getMessage("notify." + p.copyKey() + "." + suffix,
                new Object[] {"Mochi", LifecyclePushDispatcher.breedArg(p), "0"}, "fallback", locale);
    }

    @Test
    @DisplayName("有品种 → 品种原样（trim）：ID「Geng Persia lagi ngumpul 🐾」/ 正文含宠物名与品种")
    void breedWins() {
        LifecyclePlannedPush p = squad(" Persia ", "CAT");
        assertThat(LifecyclePushDispatcher.breedArg(p)).isEqualTo("Persia");
        Locale id = Locale.forLanguageTag("id");
        assertThat(render(p, "title", id)).isEqualTo("Geng Persia lagi ngumpul 🐾");
        assertThat(render(p, "body", id)).isEqualTo("Kenalin Mochi ke sesama Persia, siapa tahu jadi bestie 💜");
        assertThat(render(p, "body", Locale.ENGLISH))
                .isEqualTo("Introduce Mochi to fellow Persia pals, maybe they'll be besties 💜");
    }

    @Test
    @DisplayName("无品种 → 物种词按语言：CAT Kucing / cat，DOG Anjing / dog，OTHER 与缺物种 Anabul / pet")
    void speciesFallbackPerLocale() {
        Locale id = Locale.forLanguageTag("id");
        Locale en = Locale.ENGLISH;
        assertThat(render(squad(null, "CAT"), "title", id)).isEqualTo("Geng Kucing lagi ngumpul 🐾");
        assertThat(render(squad(null, "CAT"), "title", en)).isEqualTo("The cat squad is hanging out 🐾");
        assertThat(render(squad("  ", "DOG"), "title", id)).isEqualTo("Geng Anjing lagi ngumpul 🐾");
        assertThat(render(squad(null, "DOG"), "title", en)).isEqualTo("The dog squad is hanging out 🐾");
        assertThat(render(squad(null, "OTHER"), "title", id)).isEqualTo("Geng Anabul lagi ngumpul 🐾");
        assertThat(render(squad(null, null), "title", en)).isEqualTo("The pet squad is hanging out 🐾");
    }

    @Test
    @DisplayName("D7 还差 N 项与第 1 天已互动整句（撇号正确）")
    void otherSentences() {
        LifecyclePlannedPush tasks = new LifecyclePlannedPush(1L, NotificationType.LIFECYCLE_D7, "ONCE",
                LifecycleVariant.NEWBIE_TASKS, null, LifecycleLayer.A, "TASKS_LEFT", null, null, 3);
        assertThat(messages().getMessage("notify." + tasks.copyKey() + ".body",
                new Object[] {"hewanmu", LifecyclePushDispatcher.breedArg(tasks), "3"}, "x", Locale.forLanguageTag("id")))
                .isEqualTo("Tinggal 3 Tugas Pemula lagi, Lulus Pemula udah di depan mata! 💪");
        LifecyclePlannedPush active = new LifecyclePlannedPush(1L, NotificationType.LIFECYCLE_D1, "ONCE",
                LifecycleVariant.RECORD, "Mochi", LifecycleLayer.E, "ACTIVE", null, "CAT", 0);
        assertThat(render(active, "title", Locale.ENGLISH)).isEqualTo("What's Mochi up to today? 👀");
        assertThat(render(active, "body", Locale.ENGLISH)).isEqualTo("One more photo to make Mochi's Diary even more fun! 📸");
    }
}
