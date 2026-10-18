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
 * L0 源码守卫：V1.4.0 multi-pet Story 2.1 · AC2.1 / 架构 §5 —— 宠物状态只经 {@code PetSlotStateService} 改。
 *
 * <p>{@code PetProfile} 与 {@code PetSlotStateService} 不在同一包，Java 访问修饰符表达不了「仅该类可调」，
 * 故由本守卫扫描主代码：{@code markReadOnly()} / {@code markActive()} / {@code setKeepActive(...)} 的调用只允许出现在
 * {@code profile/slot/PetSlotStateService.java}（定义处 {@code PetProfile.java} 除外）；也不许绕过实体直接写
 * {@code pet_profiles.status} / {@code keep_active}（原生 SQL / JPQL 的 {@code SET status} / {@code SET keep_active}）。
 */
class PetStatusSingleEntryGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    static final Pattern MUTATOR_CALL = Pattern.compile("(?:\\.\\s*(markReadOnly|markActive|setKeepActive)\\s*\\(|::\\s*(markReadOnly|markActive|setKeepActive)\\b)");

    /** 只看包含 pet_profiles / PetProfile 的语句里的 SET status / keep_active。 */
    static final Pattern RAW_STATUS_WRITE = Pattern.compile(
            "(?is)(pet_profiles|PetProfile)[^;]{0,200}?\\bSET\\b[^;]{0,200}?\\b(status|keep_active|keepActive)\\s*=");

    private static final List<String> ALLOWED = List.of(
            "com/tailtopia/profile/slot/PetSlotStateService.java",
            "com/tailtopia/profile/domain/PetProfile.java");

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static boolean allowed(Path p) {
        String rel = MAIN.relativize(p).toString().replace('\\', '/');
        return ALLOWED.contains(rel);
    }

    @Test
    void statusMutatorsOnlyCalledFromPetSlotStateService() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaFiles()) {
            if (allowed(p)) {
                continue;
            }
            String src = Files.readString(p, StandardCharsets.UTF_8);
            if (MUTATOR_CALL.matcher(src).find() || RAW_STATUS_WRITE.matcher(src).find()) {
                offenders.add(MAIN.relativize(p).toString());
            }
        }
        assertThat(offenders).as("宠物状态只经 PetSlotStateService 改（架构 §5）").isEmpty();
    }

    @Test
    void stateServiceActuallyUsesTheMutators() throws IOException {
        String src = Files.readString(MAIN.resolve(ALLOWED.get(0)), StandardCharsets.UTF_8);
        assertThat(MUTATOR_CALL.matcher(src).results().map(m -> m.group(1) != null ? m.group(1) : m.group(2)).distinct())
                .containsExactlyInAnyOrder("markReadOnly", "markActive", "setKeepActive");
    }

    /** 正则自检：正例命中、反例不命中。 */
    @Test
    void patternsSelfCheck() {
        assertThat(MUTATOR_CALL.matcher("pet.markReadOnly();").find()).isTrue();
        assertThat(MUTATOR_CALL.matcher("p .setKeepActive( true)").find()).isTrue();
        assertThat(MUTATOR_CALL.matcher("pets.get(0).markActive()").find()).isTrue();
        assertThat(MUTATOR_CALL.matcher("public void markActive() {").find()).isFalse();
        assertThat(MUTATOR_CALL.matcher("p.isKeepActive()").find()).isFalse();
        // 2.1 复审补强：方法引用也算改状态。
        assertThat(MUTATOR_CALL.matcher("pets.forEach(PetProfile::markReadOnly)").find()).isTrue();
        assertThat(RAW_STATUS_WRITE.matcher("UPDATE pet_profiles SET status = 'READ_ONLY' WHERE id = ?").find())
                .isTrue();
        assertThat(RAW_STATUS_WRITE.matcher("update PetProfile p set p.keepActive = true").find()).isTrue();
        assertThat(RAW_STATUS_WRITE.matcher("update PetProfile p set keepActive = true").find()).isTrue();
        assertThat(RAW_STATUS_WRITE.matcher("UPDATE pet_profiles SET keep_active = false").find()).isTrue();
        assertThat(RAW_STATUS_WRITE.matcher("UPDATE orders SET status = 'PAID'").find()).isFalse();
        assertThat(RAW_STATUS_WRITE.matcher("UPDATE pet_profiles SET name = ?").find()).isFalse();
    }
}
