package com.tailtopia.admin.usermgmt.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.PetSpecies;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 pet-species Story 2.1 —— 27 值都有 emoji 与四语文案 key；下拉顺序 15 + 12。 */
class PetSpeciesLabelsTest {

    @Test
    void emojiPerPrd() {
        assertThat(PetSpeciesLabels.emoji("CAT")).isEqualTo("🐱");
        assertThat(PetSpeciesLabels.emoji("RABBIT")).isEqualTo("🐰");
        assertThat(PetSpeciesLabels.emoji("SUGAR_GLIDER")).isEqualTo("🐾");
        assertThat(PetSpeciesLabels.emoji("GUINEA_PIG")).isEqualTo("🐾");
        assertThat(PetSpeciesLabels.emoji("CIVET")).isEqualTo("🐾");
        assertThat(PetSpeciesLabels.emoji(null)).isEqualTo("🐾");
        for (PetSpecies s : PetSpecies.values()) {
            assertThat(PetSpeciesLabels.emoji(s.name())).isNotBlank();
        }
    }

    @Test
    void pickerOrder() {
        List<PetSpecies> flat = PetSpeciesLabels.pickerTypes();
        assertThat(flat).hasSize(15).startsWith(PetSpecies.CAT, PetSpecies.DOG, PetSpecies.RABBIT)
                .endsWith(PetSpecies.FROG, PetSpecies.GUINEA_PIG, PetSpecies.OTHER);
        assertThat(PetSpeciesLabels.otherGroupMembers()).hasSize(12).startsWith(PetSpecies.CIVET)
                .endsWith(PetSpecies.MOUSE);
    }

    @Test
    void everySpeciesHasLabelInAllFourFiles() throws IOException {
        for (String f : List.of("messages.properties", "messages_en.properties", "messages_id.properties",
                "messages_zh_CN.properties")) {
            Properties p = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/i18n/" + f)) {
                p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (PetSpecies s : PetSpecies.values()) {
                assertThat(p.getProperty(PetSpeciesLabels.key(s.name()))).as(f + " " + s).isNotBlank();
            }
            assertThat(p.getProperty("perm.user.pet_type_change")).as(f).isNotBlank();
            assertThat(p.getProperty("admin.audit.action.PET_SPECIES_CHANGED")).as(f).isNotBlank();
            assertThat(p.getProperty("admin.opsNotify.form.targetHint")).as(f).contains("6");
        }
    }
}
