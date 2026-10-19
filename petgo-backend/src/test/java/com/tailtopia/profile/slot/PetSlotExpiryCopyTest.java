package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * L0：V1.4.0 multi-pet Story 2.3 · AC3 / AC4（K-1 ~ K-4）—— 用<b>真实</b> {@code i18n/messages*.properties}
 * （与 {@code AdminLocaleConfig} 同配置）渲染 id / en 四个键；到期日按 WIB、{@code d MMM}；宠物名含 {@code '} / {@code {}
 * 不破坏格式；四份 properties 键齐全。
 */
class PetSlotExpiryCopyTest {

    private static final Locale ID = Locale.forLanguageTag("id");
    private static final List<String> FILES = List.of("messages.properties", "messages_en.properties",
            "messages_id.properties", "messages_zh_CN.properties");
    private static final List<String> KEYS = List.of("notify.PET_SLOT_EXPIRING.title",
            "notify.PET_SLOT_EXPIRING.body", "notify.PET_SLOT_EXPIRED.title", "notify.PET_SLOT_EXPIRED.body");

    private final PetSlotExpiryCopy copy = new PetSlotExpiryCopy(source());

    private static ReloadableResourceBundleMessageSource source() {
        ReloadableResourceBundleMessageSource s = new ReloadableResourceBundleMessageSource();
        s.setBasename("classpath:i18n/messages");
        s.setDefaultEncoding(StandardCharsets.UTF_8.name());
        s.setFallbackToSystemLocale(false);
        return s;
    }

    private String render(String key, String suffix, Object[] args, Locale locale) {
        return copy.render(key, suffix, args, locale);
    }

    @Test
    void expiringIdAndEn() {
        LocalDate d = LocalDate.of(2026, 10, 16);
        Object[] idArgs = copy.expiringArgs(d, ID);
        assertThat(render(PetSlotExpiryCopy.EXPIRING, "title", idArgs, ID)).isEqualTo("Slot hewan berakhir 16 Okt");
        assertThat(render(PetSlotExpiryCopy.EXPIRING, "body", idArgs, ID))
                .isEqualTo("Perpanjang sebelum 16 Okt agar semua hewanmu tetap aktif.");
        Object[] enArgs = copy.expiringArgs(d, Locale.ENGLISH);
        assertThat(render(PetSlotExpiryCopy.EXPIRING, "title", enArgs, Locale.ENGLISH)).isEqualTo("Pet slot ends 16 Oct");
        assertThat(render(PetSlotExpiryCopy.EXPIRING, "body", enArgs, Locale.ENGLISH))
                .isEqualTo("Renew before 16 Oct to keep all your pets active.");
    }

    @Test
    void expiredIdAndEnWithJoinedNames() {
        Object[] args = copy.expiredArgs(List.of("Mochi", "Kopi", "Teh"));
        assertThat(render(PetSlotExpiryCopy.EXPIRED, "title", args, ID)).isEqualTo("Slot hewan sudah berakhir");
        assertThat(render(PetSlotExpiryCopy.EXPIRED, "body", args, ID))
                .isEqualTo("Mochi, Kopi & Teh sekarang hanya-baca. Perpanjang slot untuk mengaktifkannya lagi.");
        assertThat(render(PetSlotExpiryCopy.EXPIRED, "title", args, Locale.ENGLISH)).isEqualTo("Pet slot has expired");
        assertThat(render(PetSlotExpiryCopy.EXPIRED, "body", args, Locale.ENGLISH))
                .isEqualTo("Mochi, Kopi & Teh is now read-only. Renew the slot to reactivate it.");
    }

    /** 宠物名是用户输入：作为参数传入，{@code '} / {@code {0}} 不会被当成 MessageFormat 语法。 */
    @Test
    void petNameWithQuoteAndBracesIsLiteral() {
        Object[] args = copy.expiredArgs(List.of("D'Artagnan", "{0} {1}"));
        assertThat(render(PetSlotExpiryCopy.EXPIRED, "body", args, Locale.ENGLISH))
                .isEqualTo("D'Artagnan & {0} {1} is now read-only. Renew the slot to reactivate it.");
    }

    /** 最坏情况（4 只 20 字名字）落库正文仍 ≤ notifications.body 的 255。 */
    @Test
    void worstCaseBodyFitsColumn() {
        String n = "x".repeat(20);
        Object[] args = copy.expiredArgs(List.of(n, n, n, n));
        for (Locale l : List.of(ID, Locale.ENGLISH)) {
            assertThat(render(PetSlotExpiryCopy.EXPIRED, "body", args, l).length()).isLessThanOrEqualTo(255);
            assertThat(render(PetSlotExpiryCopy.EXPIRING, "title", copy.expiringArgs(LocalDate.of(2026, 9, 30), l), l)
                    .length()).isLessThanOrEqualTo(120);
        }
    }

    /** 到期日按 WIB 自然日：UTC 15 日 18:00 = WIB 16 日 01:00 → 「16 Oct」。 */
    @Test
    void dateIsWib() {
        LocalDate d = PetSlotExpiryCopy.wibDate(Instant.parse("2026-10-15T18:00:00Z"));
        assertThat(d).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(PetSlotExpiryCopy.wibDate(Instant.parse("2026-10-15T16:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(PetSlotExpiryCopy.formatDate(d, Locale.ENGLISH)).isEqualTo("16 Oct");
        assertThat(PetSlotExpiryCopy.formatDate(LocalDate.of(2026, 8, 5), ID)).isEqualTo("5 Agu");
    }

    @Test
    void allFourPropertiesHaveTheKeysWithoutBareQuotes() throws IOException {
        for (String f : FILES) {
            Properties p = new Properties();
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("i18n/" + f)) {
                assertThat(in).as(f).isNotNull();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String k : KEYS) {
                String v = p.getProperty(k);
                assertThat(v).as(f + " " + k).isNotBlank();
                assertThat(v.replace("''", "")).as(f + " " + k + " 单引号须写 ''").doesNotContain("'");
                if (!k.equals("notify.PET_SLOT_EXPIRED.title")) {
                    assertThat(v).as(f + " " + k).contains("{0}");
                }
            }
        }
    }
}
