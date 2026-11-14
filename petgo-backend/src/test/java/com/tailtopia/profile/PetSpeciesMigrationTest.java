package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.PetSpecies;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** L0 迁移文本守卫：V1.5.0 pet-species Story 1.1 · AC2.3 —— CHECK 全集 = {@link PetSpecies#values()}（顺序一致）。 */
class PetSpeciesMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V20261115_0400__pet_profiles_add_species.sql");

    @Test
    void checkConstraintListsAllSpeciesInOrder() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("ck_pet_profiles_pet_species\\s+CHECK\\s*\\(\\s*pet_species\\s+IN\\s*\\(([^)]*)\\)")
                .matcher(sql);
        assertThat(m.find()).as("迁移里应有 ck_pet_profiles_pet_species").isTrue();
        List<String> values = Arrays.stream(m.group(1).split(","))
                .map(v -> v.trim().replace("'", ""))
                .toList();
        assertThat(values).containsExactlyElementsOf(
                Arrays.stream(PetSpecies.values()).map(Enum::name).toList());
    }

    @Test
    void doesNotTouchPetTypeConstraintOrNotificationTypes() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        assertThat(sql).doesNotContain("DROP CONSTRAINT ck_pet_profiles_pet_type");
        assertThat(sql).doesNotContain("ck_notifications_type");
        assertThat(sql).contains("ck_pet_profiles_species_group").contains("ck_pet_profiles_milestone_track");
    }
}
