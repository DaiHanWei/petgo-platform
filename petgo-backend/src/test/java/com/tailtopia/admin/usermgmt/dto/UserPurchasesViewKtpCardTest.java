package com.tailtopia.admin.usermgmt.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.io.Reader;
import org.junit.jupiter.api.Test;

/**
 * L0（V1.3.4 ktp Story 1.1，D-6）：后台购买记录卡种名 key 与编号口径；8 个卡种名在四份 properties 都有。
 */
class UserPurchasesViewKtpCardTest {

    private static UserPurchasesView.KtpCard card(long serial, String type, String variant, String no) {
        return new UserPurchasesView.KtpCard(serial, "Mochi", null, type, variant, no);
    }

    @Test
    void styleKeyPerCardTypeAndStudentVariant() {
        assertThat(card(1, "KTP", null, null).styleKey()).isEqualTo("KTP");
        assertThat(card(1, "PASSPORT", null, null).styleKey()).isEqualTo("PASSPORT");
        assertThat(card(1, "KITAS", null, null).styleKey()).isEqualTo("KITAS");
        assertThat(card(1, "SIM", null, null).styleKey()).isEqualTo("SIM");
        assertThat(card(1, "STUDENT", "BINTANG", null).styleKey()).isEqualTo("STUDENT.BINTANG");
        assertThat(card(1, "STUDENT", null, null).styleKey()).isEqualTo("STUDENT.KLASIK");
    }

    @Test
    void displayNoPrefersCardNoElseZeroPaddedSerial() {
        assertThat(card(42, "KTP", null, "TT600324020001").displayNo()).isEqualTo("TT600324020001");
        assertThat(card(42, "KTP", null, null).displayNo()).isEqualTo("0042");
        assertThat(card(7, "KTP", null, "").displayNo()).isEqualTo("0007");
        assertThat(card(123456, "KTP", null, null).displayNo()).isEqualTo("123456");
    }

    @Test
    void allEightStyleNamesExistInEveryLocale() throws Exception {
        List<String> keys = List.of("KTP", "PASSPORT", "KITAS", "SIM", "STUDENT.BINTANG", "STUDENT.MALAM",
                "STUDENT.CERIA", "STUDENT.KLASIK");
        for (String f : List.of("messages.properties", "messages_en.properties", "messages_zh_CN.properties",
                "messages_id.properties")) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(Path.of("src/main/resources/i18n", f), StandardCharsets.UTF_8)) {
                p.load(r);
            }
            for (String k : keys) {
                assertThat(p.getProperty("admin.v134.idcard.style." + k)).as(f + " " + k).isNotBlank();
            }
        }
    }
}
