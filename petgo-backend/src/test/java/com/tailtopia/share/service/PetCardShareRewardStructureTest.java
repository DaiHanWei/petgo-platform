package com.tailtopia.share.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.share.dto.PassportShareRewardRequest;
import com.tailtopia.share.dto.PetCardShareRewardResponse;
import com.tailtopia.share.dto.TailsonalityShareRewardRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * V1.3.2 Story 4.5 · L0 结构断言：两个新分享奖励渠道的形状（发放规则本身在 L1 {@code PetCardShareRewardIntegrationTest}）。
 */
class PetCardShareRewardStructureTest {

    private static String read(String rel) throws IOException {
        return Files.readString(Path.of("src/main/java/com/tailtopia/" + rel), StandardCharsets.UTF_8);
    }

    private static String code(String rel) throws IOException {
        return read(rel).lines()
                .filter(l -> !l.trim().startsWith("*") && !l.trim().startsWith("/**") && !l.trim().startsWith("//"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void channelRunsInExplicitRequiresNewTemplateNotSelfInvokedAnnotation() throws IOException {
        String base = code("share/service/PetCardShareRewardChannel.java");
        assertThat(base).contains("PROPAGATION_REQUIRES_NEW");
        assertThat(base).doesNotContain("@Transactional");
    }

    @Test
    void wibDayReusesTheSingleImplementation() throws IOException {
        String base = code("share/service/PetCardShareRewardChannel.java");
        assertThat(base).contains("IdCardShareRewardService.shareDateOf(at)");
        assertThat(base).doesNotContain("Asia/Jakarta");
    }

    @Test
    void advisoryLockNamespacesAreUniquePerChannel() {
        Set<Integer> ns = Set.of(AgeCardShareRewardService.DAILY_CAP_LOCK_NS,
                TailsonalityShareRewardService.DAILY_CAP_LOCK_NS, PassportShareRewardService.DAILY_CAP_LOCK_NS);
        assertThat(ns).hasSize(3);
    }

    @Test
    void refTypesAndIdempotencyPrefixesAreDistinct() {
        assertThat(TailsonalityShareRewardService.REF_TYPE).isEqualTo("TAILSONALITY_SHARE");
        assertThat(PassportShareRewardService.REF_TYPE).isEqualTo("PASSPORT_SHARE");
        assertThat(TailsonalityShareRewardService.CHANNEL_PREFIX).isEqualTo("tailsonality-share:");
        assertThat(PassportShareRewardService.CHANNEL_PREFIX).isEqualTo("passport-share:");
    }

    /** 🔴 接口只收卡类型、只回 coins（刻意不返回原因；不收结果 / 场所 token、不收水印态）。 */
    @Test
    void requestCarriesOnlyCardTypeAndResponseOnlyCoins() {
        assertThat(TailsonalityShareRewardRequest.class.getRecordComponents()).hasSize(1);
        assertThat(PassportShareRewardRequest.class.getRecordComponents()).hasSize(1);
        assertThat(PetCardShareRewardResponse.class.getRecordComponents()).hasSize(1);
        assertThat(PetCardShareRewardResponse.class.getRecordComponents()[0].getName()).isEqualTo("coins");
    }

    @Test
    void cardTypeValidationRejectsOtherChannelsValues() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator v = factory.getValidator();
            assertThat(v.validate(new TailsonalityShareRewardRequest("RESULT"))).isEmpty();
            assertThat(v.validate(new TailsonalityShareRewardRequest("MATCH"))).isEmpty();
            assertThat(v.validate(new TailsonalityShareRewardRequest("PAGE"))).isNotEmpty();
            assertThat(v.validate(new TailsonalityShareRewardRequest(null))).isNotEmpty();
            assertThat(v.validate(new PassportShareRewardRequest("PAGE"))).isEmpty();
            assertThat(v.validate(new PassportShareRewardRequest("BOARDING"))).isEmpty();
            assertThat(v.validate(new PassportShareRewardRequest("RESULT"))).isNotEmpty();
        }
    }

    @Test
    void enumValuesMatchTableChecks() throws IOException {
        String ddl = Files.readString(Path.of(
                "src/main/resources/db/migration/V20261001_1215__create_tailsonality_passport_share_rewards.sql"),
                StandardCharsets.UTF_8);
        assertThat(ddl).contains("card_type IN ('RESULT', 'MATCH')").contains("card_type IN ('PAGE', 'BOARDING')");
        assertThat(ddl).contains("UNIQUE (pet_profile_id, card_type)");
        assertThat(ddl).contains("ON DELETE SET NULL").contains("REFERENCES users (id)");
        assertThat(TailsonalityShareRewardService.CardType.values()).extracting(Enum::name)
                .containsExactly("RESULT", "MATCH");
        assertThat(PassportShareRewardService.CardType.values()).extracting(Enum::name)
                .containsExactly("PAGE", "BOARDING");
    }
}
