package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0 源码守卫：V1.5.0 pet-species Story 1.1 · AC3.4 / 架构 G-2 —— 大类 {@code pet_type} 只经
 * {@code PetProfile.applySpecies} 改。
 *
 * <p>扫 {@code src/main/java}（迁移文件与 {@code scripts/} 不在范围）：除 {@code PetProfile.java} 外，不得出现对
 * {@code petType} 字段的赋值、{@code setField(..., "petType"} 反射写、对 {@code pet_profiles} 的 {@code SET pet_type}。
 */
class PetSpeciesWriteGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** {@code x.petType = ...} / {@code this.petType = ...}（排除 {@code ==}）。 */
    static final Pattern FIELD_ASSIGN = Pattern.compile("\\.\\s*petType\\s*=(?!=)");

    static final Pattern REFLECTIVE_WRITE = Pattern.compile("setField\\s*\\([^;]*\"petType\"");

    /** 只看包含 pet_profiles / PetProfile 的语句里的 SET pet_type / petType。 */
    static final Pattern RAW_WRITE = Pattern.compile(
            "(?is)(pet_profiles|PetProfile)[^;]{0,200}?\\bSET\\b[^;]{0,200}?\\b(pet_type|petType)\\s*=(?!=)");

    private static final String ALLOWED = "com/tailtopia/profile/domain/PetProfile.java";

    /** {@code IdCard.petType} 是 {@code id_cards} 的建卡快照（另一张表、另一字段），不属 pet_profiles 大类写入。 */
    private static final List<String> OTHER_ENTITIES = List.of("com/tailtopia/profile/domain/IdCard.java");

    @Test
    void petTypeOnlyWrittenInsidePetProfile() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                if (rel.equals(ALLOWED) || OTHER_ENTITIES.contains(rel)) {
                    continue;
                }
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (FIELD_ASSIGN.matcher(src).find() || REFLECTIVE_WRITE.matcher(src).find()
                        || RAW_WRITE.matcher(src).find()) {
                    offenders.add(rel);
                }
            }
        }
        assertThat(offenders).as("pet_type 只经 PetProfile.applySpecies 改（架构 G-2）").isEmpty();
    }

    @Test
    void petProfileWritesPetTypeOnlyInApplySpecies() throws IOException {
        String src = Files.readString(MAIN.resolve(ALLOWED), StandardCharsets.UTF_8);
        assertThat(FIELD_ASSIGN.matcher(src).results().count()).isEqualTo(1);
        assertThat(src).contains("this.petType = s.group();");
    }

    /** 正则自检。 */
    @Test
    void patternsSelfCheck() {
        assertThat(FIELD_ASSIGN.matcher("p.petType = PetType.CAT;").find()).isTrue();
        assertThat(FIELD_ASSIGN.matcher("this.petType=x;").find()).isTrue();
        assertThat(FIELD_ASSIGN.matcher("if (p.petType == PetType.CAT)").find()).isFalse();
        assertThat(FIELD_ASSIGN.matcher("String petType = req.petType();").find()).isFalse();
        assertThat(REFLECTIVE_WRITE.matcher("ReflectionTestUtils.setField(p, \"petType\", PetType.CAT)").find())
                .isTrue();
        assertThat(RAW_WRITE.matcher("UPDATE pet_profiles SET pet_type = 'CAT' WHERE id = ?").find()).isTrue();
        assertThat(RAW_WRITE.matcher("update PetProfile p set p.petType = :t").find()).isTrue();
        assertThat(RAW_WRITE.matcher("UPDATE pet_profiles SET name = ?").find()).isFalse();
        assertThat(RAW_WRITE.matcher("SELECT * FROM pet_profiles WHERE pet_type = 'CAT'").find()).isFalse();
    }
}
