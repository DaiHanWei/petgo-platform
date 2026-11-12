package com.tailtopia.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** L0：analytics-pipeline Story 1.4 AC2 —— 逐条校验只拒该条，可选字段类型错丢字段不拒条。 */
class IngestValidatorTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-13T01:00:00Z");
    private static final Predicate<String> REGISTERED = Set.of("toko_add_to_cart_tapped")::contains;
    private static final String AID = "3f2c8a1e-9b4d-4c1a-8e7f-0a1b2c3d4e5f";

    private static ObjectNode valid() {
        ObjectNode n = JSON.createObjectNode();
        n.put("messageId", "m-1");
        n.put("event", "toko_add_to_cart_tapped");
        n.put("timestamp", "2026-11-13T08:15:30.123+07:00");
        n.put("sentAt", "2026-11-13T08:16:00.000+07:00");
        n.put("anonymousId", AID);
        return n;
    }

    private static IngestValidator.Result run(JsonNode... nodes) {
        return IngestValidator.validate(List.of(nodes), NOW, REGISTERED);
    }

    @Test
    @DisplayName("合法事件：字段映射、时区转 Instant、source=APP、缺省 {}、身份未判定")
    void validEventMapped() {
        IngestValidator.Result r = run(valid());

        assertThat(r.rejected()).isZero();
        assertThat(r.accepted()).hasSize(1);
        RawEvent e = r.accepted().get(0);
        assertThat(e.messageId()).isEqualTo("m-1");
        assertThat(e.event()).isEqualTo("toko_add_to_cart_tapped");
        assertThat(e.clientTs()).isEqualTo(Instant.parse("2026-11-13T01:15:30.123Z"));
        assertThat(e.sentAt()).isEqualTo(Instant.parse("2026-11-13T01:16:00Z"));
        assertThat(e.receivedAt()).isEqualTo(NOW);
        assertThat(e.anonymousId()).isEqualTo(AID);
        assertThat(e.source()).isEqualTo(RawEvent.Source.APP);
        assertThat(e.propertiesJson()).isEqualTo("{}");
        assertThat(e.contextJson()).isEqualTo("{}");
        assertThat(e.userKey()).isNull();
        assertThat(e.userId()).isNull();
        assertThat(e.identityUnverified()).isFalse();
        assertThat(e.unknownEvent()).isFalse();
        assertThat(e.estimatedBytes()).isGreaterThan(RawEvent.FIXED_OVERHEAD_BYTES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"messageId", "event", "timestamp", "sentAt", "anonymousId"})
    @DisplayName("五个必填字段各缺一次：只拒该条")
    void missingRequiredRejectsOnlyThatEvent(String field) {
        ObjectNode bad = valid();
        bad.remove(field);

        IngestValidator.Result r = run(valid(), bad, valid());

        assertThat(r.rejected()).isEqualTo(1);
        assertThat(r.accepted()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"messageId", "event", "timestamp", "sentAt", "anonymousId"})
    @DisplayName("五个必填字段各非法一次（数字类型）：只拒该条")
    void wrongTypeRequiredRejected(String field) {
        ObjectNode bad = valid();
        bad.put(field, 123);

        assertThat(run(bad, valid()).rejected()).isEqualTo(1);
    }

    @Test
    @DisplayName("messageId / event：空白、超长、含 NUL 拒")
    void stringRules() {
        ObjectNode blank = valid();
        blank.put("messageId", "  ");
        ObjectNode longId = valid();
        longId.put("messageId", "x".repeat(65));
        ObjectNode okId = valid();
        okId.put("messageId", "x".repeat(64));
        ObjectNode longEvent = valid();
        longEvent.put("event", "e".repeat(129));
        ObjectNode okEvent = valid();
        okEvent.put("event", "e".repeat(128));
        ObjectNode nul = valid();
        nul.put("event", "a\u0000b");

        IngestValidator.Result r = run(blank, longId, okId, longEvent, okEvent, nul);

        assertThat(r.rejected()).isEqualTo(4);
        assertThat(r.accepted()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-13T08:15:30", "2026-11-13", "yesterday", "", "2026-13-40T00:00:00Z",
            "+10000-01-01T00:00:00Z"})
    @DisplayName("timestamp / sentAt：没时区偏移、不是日期时间、年份越界 → 拒")
    void timestampRules(String value) {
        ObjectNode ts = valid();
        ts.put("timestamp", value);
        ObjectNode sent = valid();
        sent.put("sentAt", value);

        assertThat(run(ts, sent).rejected()).isEqualTo(2);
    }

    @Test
    @DisplayName("timestamp 接受 Z 与各种偏移")
    void timestampAcceptsZAndOffsets() {
        ObjectNode z = valid();
        z.put("timestamp", "2026-11-13T01:15:30Z");
        ObjectNode neg = valid();
        neg.put("sentAt", "2026-11-12T20:15:30-05:00");

        IngestValidator.Result r = run(z, neg);

        assertThat(r.rejected()).isZero();
        assertThat(r.accepted().get(1).sentAt()).isEqualTo(Instant.parse("2026-11-13T01:15:30Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "3f2c8a1e9b4d4c1a8e7f0a1b2c3d4e5f", "",
            "3f2c8a1e-9b4d-4c1a-8e7f-0a1b2c3d4e5f0"})
    @DisplayName("anonymousId 非标准 UUID 拒（含 UUID.fromString 放过的宽松格式）")
    void anonymousIdMustBeCanonicalUuid(String value) {
        ObjectNode bad = valid();
        bad.put("anonymousId", value);

        assertThat(run(bad).rejected()).isEqualTo(1);
    }

    @Test
    @DisplayName("anonymousId 大写照收，统一小写入库")
    void anonymousIdUpperCaseNormalized() {
        ObjectNode upper = valid();
        upper.put("anonymousId", AID.toUpperCase());

        IngestValidator.Result r = run(upper);

        assertThat(r.rejected()).isZero();
        assertThat(r.accepted().get(0).anonymousId()).isEqualTo(AID);
    }

    @Test
    @DisplayName("userId / properties / context 缺失照收")
    void optionalMissingAccepted() {
        assertThat(run(valid()).accepted()).hasSize(1);
    }

    @Test
    @DisplayName("可选字段正常：userId 放进 userKey（待身份判定），properties / context 原样 JSON")
    void optionalPresent() {
        ObjectNode n = valid();
        n.put("userId", "a".repeat(64));
        n.putObject("properties").put("sku_id", "s-1").put("qty", 2);
        n.putObject("context").put("app_version", "1.5.0");

        RawEvent e = run(n).accepted().get(0);

        assertThat(e.userKey()).isEqualTo("a".repeat(64));
        assertThat(e.userId()).isNull();
        assertThat(JSON.readTree(e.propertiesJson()).get("qty").asInt()).isEqualTo(2);
        assertThat(JSON.readTree(e.contextJson()).get("app_version").asString()).isEqualTo("1.5.0");
    }

    @Test
    @DisplayName("可选字段类型错 / 超长 / 含 NUL：丢该字段，不拒条")
    void optionalWrongTypeDropped() {
        ObjectNode n = valid();
        n.put("userId", 42);
        n.putArray("properties").add(1);
        n.put("context", "oops");
        ObjectNode longUser = valid();
        longUser.put("userId", "a".repeat(65));
        ObjectNode nulProps = valid();
        nulProps.putObject("properties").put("k", "a\u0000b");

        IngestValidator.Result r = run(n, longUser, nulProps);

        assertThat(r.rejected()).isZero();
        assertThat(r.accepted()).hasSize(3).allSatisfy(e -> {
            assertThat(e.userKey()).isNull();
            assertThat(e.propertiesJson()).isEqualTo("{}");
            assertThat(e.contextJson()).isEqualTo("{}");
        });
    }

    @Test
    @DisplayName("事件名不在清单：照收，unknownEvent=true")
    void unknownEventAccepted() {
        ObjectNode n = valid();
        n.put("event", "not_in_catalog");

        RawEvent e = run(n).accepted().get(0);

        assertThat(e.unknownEvent()).isTrue();
    }

    @Test
    @DisplayName("元素不是对象：拒该条")
    void nonObjectElementRejected() {
        IngestValidator.Result r = run(JsonNodeFactory.instance.stringNode("x"), JsonNodeFactory.instance.nullNode(),
                valid());

        assertThat(r.rejected()).isEqualTo(2);
        assertThat(r.accepted()).hasSize(1);
    }
}
