package com.tailtopia.tailsonality;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import com.tailtopia.tailsonality.domain.TailsonalityQuestionSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * V1.3.2 Story 2.2 · AC3 · L0 跨库一致性：App 内容表的题目键 / 角色键 = 后端 {@link TailsonalityCatalog}。
 *
 * <p>照 {@code profile.domain.MilestoneCatalogI18nTest}：后端读 {@code ../petgo_app/} 的 Dart 源码，
 * <b>找不到文件明确失败，绝不静默跳过</b>（Maven 工作目录 = {@code petgo-backend/}）。
 */
class TailsonalityContentParityTest {

    private static final Path CONTENT =
            Path.of("..", "petgo_app", "lib", "features", "tailsonality", "domain", "content");

    private static final Pattern QUESTION_KEY =
            Pattern.compile("'(CAT|DOG|GENERAL)\\.(Q(?:1[0-5]|[1-9])|P[1-3])'\\s*:");
    private static final Pattern ROLE_KEY = Pattern.compile("'([EI][NS][TF][JP])'\\s*:\\s*TsRole\\(");

    private static String read(String file) throws IOException {
        Path p = CONTENT.resolve(file);
        assertThat(Files.exists(p)).as("App 内容表不存在（跨库测试不得静默跳过）：%s", p.toAbsolutePath()).isTrue();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static Set<String> extract(Pattern p, String src, boolean joinTwoGroups) {
        Set<String> out = new HashSet<>();
        Matcher m = p.matcher(src);
        while (m.find()) {
            assertThat(out.add(joinTwoGroups ? m.group(1) + "." + m.group(2) : m.group(1)))
                    .as("App 内容表键重复：%s", m.group()).isTrue();
        }
        return out;
    }

    @Test
    void questionKeysMatchCatalog() throws IOException {
        Set<String> expected = new HashSet<>();
        for (TailsonalityQuestionSet set : TailsonalityQuestionSet.values()) {
            for (String q : TailsonalityCatalog.QUESTION_IDS) {
                expected.add(set.name() + "." + q);
            }
        }
        assertThat(expected).hasSize(54);
        assertThat(extract(QUESTION_KEY, read("ts_questions.dart"), true)).isEqualTo(expected);
    }

    @Test
    void roleKeysMatchTypeCodes() throws IOException {
        assertThat(extract(ROLE_KEY, read("ts_roles.dart"), false))
                .isEqualTo(new HashSet<>(TailsonalityCatalog.TYPE_CODES));
    }

    @Test
    void everyQuestionHasExactlyFourWeights() {
        // 选项数两端一致：App 侧 content_tables_test 钉 4 选项，这里钉 4 权重。
        TailsonalityCatalog.WEIGHTS.forEach((set, byQuestion) -> byQuestion.forEach(
                (q, w) -> assertThat(w).as(set + "." + q).hasSize(4)));
    }
}
