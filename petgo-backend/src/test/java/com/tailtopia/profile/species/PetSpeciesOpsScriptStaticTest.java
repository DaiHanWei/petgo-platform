package com.tailtopia.profile.species;

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

/**
 * L0 文本守卫：V1.5.0 pet-species Story 1.4 —— 运维脚本不写清单归属 / 冷却时间、无事务控制与元命令（供 JDBC 执行）、
 * 物种白名单与 {@link PetSpecies} 一致、导出 SQL 猫 39 / 狗 43、脚本无凭证。
 */
class PetSpeciesOpsScriptStaticTest {

    private static final Path DIR = Path.of("../scripts/ops/pet-species");

    private static String read(String f) throws IOException {
        return Files.readString(DIR.resolve(f), StandardCharsets.UTF_8);
    }

    /** 去掉 -- 行注释，只看可执行 SQL。 */
    private static String code(String sql) {
        return sql.lines().map(l -> l.replaceAll("--.*$", "")).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void applyMappingNeverTouchesTrackOrCooldownAndIsPureSql() throws IOException {
        String sql = code(read("apply-mapping.sql"));
        assertThat(sql).doesNotContain("milestone_track").doesNotContain("pet_type_changed_at");
        assertThat(sql).doesNotContainPattern("(?im)^\\s*(BEGIN|COMMIT|ROLLBACK)\\s*;");
        assertThat(sql).doesNotContainPattern("(?m)^\\s*\\\\");
        assertThat(sql).doesNotContain("notifications");
        assertThat(sql).contains("'MIGRATION'");
    }

    @Test
    void applyMappingWhitelistEqualsPetSpecies() throws IOException {
        Matcher m = Pattern.compile("NOT IN \\(('CAT'[^)]*)\\)").matcher(read("apply-mapping.sql"));
        assertThat(m.find()).isTrue();
        List<String> values = Arrays.stream(m.group(1).split(","))
                .map(s -> s.trim().replace("'", "")).toList();
        assertThat(values).containsExactlyElementsOf(Arrays.stream(PetSpecies.values()).map(Enum::name).toList());
    }

    /** type_changed / group_changed 的 CASE 列表与 PetSpecies.pickerType() / group() 一致（新增物种时提醒同步）。 */
    @Test
    void applyMappingPickerAndGroupListsMatchEnum() throws IOException {
        String sql = read("apply-mapping.sql");
        Matcher picker = Pattern.compile("pet_species NOT IN \\(([^)]*)\\)").matcher(sql);
        assertThat(picker.find()).isTrue();
        List<String> otherGroup = Arrays.stream(picker.group(1).split(","))
                .map(s -> s.trim().replace("'", "")).toList();
        assertThat(otherGroup).containsExactlyInAnyOrderElementsOf(Arrays.stream(PetSpecies.values())
                .filter(s -> s.pickerType() == PetSpecies.OTHER).map(Enum::name).toList());
        Matcher group = Pattern.compile("pet_species IN \\(([^)]*)\\)").matcher(sql);
        assertThat(group.find()).isTrue();
        List<String> nonOther = Arrays.stream(group.group(1).split(",")).map(s -> s.trim().replace("'", "")).toList();
        assertThat(nonOther).containsExactlyInAnyOrderElementsOf(Arrays.stream(PetSpecies.values())
                .filter(s -> s.group() != com.tailtopia.profile.domain.PetType.OTHER).map(Enum::name).toList());
    }

    @Test
    void exportHasCat39Dog43AndBaseFilter() throws IOException {
        String sql = read("export-push-groups.sql");
        assertThat(count(sql, "cat_breeds")).isEqualTo(39);
        assertThat(count(sql, "dog_breeds")).isEqualTo(43);
        assertThat(sql).contains("u.role = 'USER' AND u.account_type = 'REAL' AND u.deleted_at IS NULL")
                .contains("u.enabled = TRUE AND u.status = 'ACTIVE'")
                .contains("p.status = 'ACTIVE'")
                .contains("-- BEGIN push_groups_view").contains("-- END push_groups_view");
    }

    private static int count(String sql, String cte) {
        Matcher m = Pattern.compile(cte + "\\(name\\) AS \\(\\s*VALUES\\s*(.*?)\\n\\)", Pattern.DOTALL).matcher(sql);
        assertThat(m.find()).as(cte).isTrue();
        return (int) m.group(1).lines().filter(l -> l.trim().startsWith("('")).count();
    }

    @Test
    void noCredentialsInScripts() throws IOException {
        for (String f : List.of("run-migration.sh", "apply-mapping.sql", "export-push-groups.sql", "README.md")) {
            String s = read(f);
            assertThat(s).as(f).doesNotContainPattern("(?i)PGPASSWORD\\s*=\\s*\\S")
                    .doesNotContainPattern("(?i)password\\s*=\\s*['\"]?\\w")
                    .doesNotContainPattern("postgres(ql)?://[^\\s]*:[^\\s]*@");
        }
    }
}
