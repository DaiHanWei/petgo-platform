package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * V1.3.0 批次 A · Story 1.4 · AC1/AC2（L0）：庆祝记账迁移的**形态**断言。
 *
 * <p>迁移本身要 DB 才跑得动（L1），但它最容易出事的两点纯靠读文件就能钉住，不必等到本地：
 * <ol>
 *   <li><b>建列与回填必须在同一支迁移里</b>（AD-A1.4）。拆成两次上线的后果是：中间那段时间
 *       列全是 NULL = 全体老用户的每条历史成就都算「未庆祝」，进列表页就被补弹一堆陈年旧成就，
 *       而庆祝<b>不可撤回</b>。这条断言是那份纪律唯一的自动执行者。</li>
 *   <li><b>列加在 {@code milestone_completions}，不是 {@code pet_milestones}</b>
 *       —— PRD §3.2 原文写错了表，AD-A1.1 已订正。照 PRD 字面写会给每一行未完成的里程碑
 *       都挂一个恒空的列，并把同生命周期的两个事实拆到两张表。</li>
 * </ol>
 */
class CelebratedAtMigrationTest {

    private static final Path MIGRATION_DIR =
            Path.of("src", "main", "resources", "db", "migration");

    /** 时间戳版本号（决策 E7）：{@code V<yyyyMMdd_HHmm>__<snake_case>.sql}。 */
    private static final Pattern TIMESTAMP_NAME = Pattern.compile(
            "^V20\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])_([01]\\d|2[0-3])[0-5]\\d__[a-z0-9_]+\\.sql$");

    private static Path migration() {
        try (var files = Files.list(MIGRATION_DIR)) {
            List<Path> hits = files
                    .filter(p -> p.getFileName().toString().contains("celebrated_at"))
                    .toList();
            assertThat(hits)
                    .as("应恰好有一支 celebrated_at 迁移（多支 = 建列与回填被拆开了）")
                    .hasSize(1);
            return hits.get(0);
        } catch (IOException e) {
            throw new AssertionError("读不到迁移目录 " + MIGRATION_DIR.toAbsolutePath(), e);
        }
    }

    private static String sql() {
        try {
            return Files.readString(migration(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void migrationFileNameIsTimestamped() {
        assertThat(migration().getFileName().toString())
                .as("Flyway 迁移一律时间戳版本号，禁止序列号（决策 E7）")
                .matches(TIMESTAMP_NAME.pattern());
    }

    /** AC1：列加在 {@code milestone_completions}（完成态所在的表），不是 {@code pet_milestones}。 */
    @Test
    void columnGoesOnMilestoneCompletions_notPetMilestones() {
        String sql = sql();
        assertThat(sql).contains("alter table milestone_completions");
        assertThat(sql).contains("add column celebrated_at");
        assertThat(sql)
                .as("PRD §3.2 写的是 pet_milestones，AD-A1.1 已订正 —— 别照字面实现")
                .doesNotContain("alter table pet_milestones");
    }

    /** 🔴 AC2：回填与建列同在这一支里。 */
    @Test
    void backfillLivesInTheSameMigrationAsTheColumn() {
        String sql = sql();
        assertThat(sql)
                .as("回填不可省、也不可拆到另一支迁移（AD-A1.4）—— 拆开上线，"
                        + "中间态所有老用户都会被补弹一堆旧成就，且庆祝不可撤回")
                .contains("update milestone_completions")
                .contains("set celebrated_at = completed_at");
    }

    /** 可空列：NULL 是「未庆祝」这个语义本身，加 NOT NULL 会让判据无从表达。 */
    @Test
    void columnIsNullableTimestamptz() {
        String sql = sql();
        assertThat(sql).contains("celebrated_at timestamptz");
        assertThat(sql)
                .as("celebrated_at 必须可空：NULL 正是「已完成但未庆祝」的判据（AD-A1.2/1.3）")
                .doesNotContain("celebrated_at timestamptz not null");
    }
}
