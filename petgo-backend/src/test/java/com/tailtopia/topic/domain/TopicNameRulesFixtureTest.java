package com.tailtopia.topic.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0：话题名规则共用样例（V1.4.2 social-topic · Story 4.1 · AC3 · P-1）。
 *
 * <p>读 {@code /fixtures/topic-parse-cases.json}（App Story 4.4 的 Dart 测试读同一份）。逐条断言
 * {@link TopicNameRules#effectiveDistinct} 的 display / norm 与 {@link TopicNameRules#parse} 中 {@code effective=true} 的 ranges。
 * {@code dartSkip=true} 的条目 Java 照跑。
 */
class TopicNameRulesFixtureTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static JsonNode root() throws Exception {
        try (InputStream in = TopicNameRulesFixtureTest.class.getResourceAsStream("/fixtures/topic-parse-cases.json")) {
            assertThat(in).as("fixture topic-parse-cases.json").isNotNull();
            return JSON.readTree(in);
        }
    }

    static Stream<Arguments> cases() throws Exception {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode c : root().get("cases")) {
            out.add(Arguments.of(c.get("id").asText(), c));
        }
        return out.stream();
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void fixtureHasEnoughCasesWithUniqueIds() throws Exception {
        JsonNode cases = root().get("cases");
        assertThat(root().get("version").asInt()).isEqualTo(1);
        assertThat(cases.size()).isGreaterThanOrEqualTo(28);
        List<String> ids = new ArrayList<>();
        cases.forEach(c -> ids.add(c.get("id").asText()));
        assertThat(ids).doesNotHaveDuplicates();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void caseMatches(String id, JsonNode c) {
        String input = c.get("input").asText();

        List<TopicToken> distinct = TopicNameRules.effectiveDistinct(input);
        assertThat(distinct).extracting(TopicToken::display).as(id + " topics").containsExactlyElementsOf(strings(c.get("topics")));
        assertThat(distinct).extracting(TopicToken::norm).as(id + " norms").containsExactlyElementsOf(strings(c.get("norms")));

        List<List<Integer>> expected = new ArrayList<>();
        c.get("ranges").forEach(r -> expected.add(List.of(r.get(0).asInt(), r.get(1).asInt())));
        List<List<Integer>> actual = TopicNameRules.parse(input).stream()
                .filter(TopicToken::effective)
                .map(t -> List.of(t.start(), t.end()))
                .toList();
        assertThat(actual).as(id + " ranges").isEqualTo(expected);

        // 每处 range 的文字 = '#' + 原文写法。
        for (TopicToken t : TopicNameRules.parse(input)) {
            assertThat(input.substring(t.start(), t.end())).isEqualTo("#" + t.display());
        }
    }

    @Test
    void isValidNameMatchesRules() {
        assertThat(TopicNameRules.isValidName("kucing")).isTrue();
        assertThat(TopicNameRules.isValidName("2024")).isFalse();
        assertThat(TopicNameRules.isValidName("a")).isFalse();
        assertThat(TopicNameRules.isValidName("a".repeat(30))).isTrue();
        assertThat(TopicNameRules.isValidName("a".repeat(31))).isFalse();
        assertThat(TopicNameRules.isValidName("ku cing")).isFalse();
        assertThat(TopicNameRules.isValidName("#kucing")).isFalse();
        assertThat(TopicNameRules.isValidName("")).isFalse();
        assertThat(TopicNameRules.isValidName(null)).isFalse();
    }
}
