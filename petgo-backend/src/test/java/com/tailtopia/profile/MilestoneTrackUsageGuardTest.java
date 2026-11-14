package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * L0 源码守卫：V1.5.0 pet-species Story 1.2 · AC3.2 / 架构 G-2 —— 里程碑 / 新手任务 code 只按 {@code milestoneTrack} 算。
 *
 * <p>FR-208 迁移把误存「其他」的猫改成 CAT 大类但清单仍是 OTHER；这些文件若再读 {@code getPetType()}，
 * 就会去找不存在的 C- 行，自动完成全部静默失效。
 */
class MilestoneTrackUsageGuardTest {

    private static final Path SERVICE = Path.of("src/main/java/com/tailtopia/profile/service");

    private static List<Path> guarded() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(SERVICE)) {
            s.filter(p -> p.getFileName().toString().matches("Milestone.*\\.java")).forEach(files::add);
        }
        for (String f : List.of("NewbieTaskService.java", "AccountMilestoneService.java", "LegacyMilestoneCompat.java",
                "TimelineService.java")) {
            Path p = SERVICE.resolve(f);
            if (Files.exists(p)) {
                files.add(p);
            }
        }
        files.add(Path.of("src/main/java/com/tailtopia/profile/visitor/VisitorProjectionService.java"));
        return files;
    }

    @Test
    void milestoneCodePathsReadTrackNotPetType() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<Path> files = guarded();
        assertThat(files).hasSizeGreaterThanOrEqualTo(8);
        for (Path p : files) {
            assertThat(p).exists();
            if (Files.readString(p, StandardCharsets.UTF_8).contains("getPetType()")) {
                offenders.add(p.toString());
            }
        }
        assertThat(offenders).as("里程碑 code 只按 milestoneTrack 算（架构 G-2）").isEmpty();
    }
}
