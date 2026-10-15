package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.AudienceSpec.Mode;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * L0：运营通知人群规格 D-16（V1.3.4 Story 6.1 · AC4.5）。
 */
class AudienceSpecTest {

    private static final String FULL = "{\"mode\":\"FILTER\",\"registeredFrom\":\"2026-01-01\","
            + "\"registeredTo\":\"2026-06-30\",\"activeFrom\":\"2026-09-01\",\"activeTo\":\"2026-10-12\","
            + "\"hasPet\":true,\"species\":[\"CAT\",\"DOG\"],\"hasShopOrder\":false,\"hasPhone\":true}";

    @Test
    @DisplayName("D-16 完整示例往返：字段逐个解析，toJson 原样写回")
    void fullExampleRoundTrips() {
        AudienceSpec s = AudienceSpec.parse(FULL);

        assertThat(s.mode()).isEqualTo(Mode.FILTER);
        assertThat(s.registeredFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(s.registeredTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(s.activeFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(s.activeTo()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(s.hasPet()).isTrue();
        assertThat(s.species()).containsExactly(PetType.CAT, PetType.DOG);
        assertThat(s.hasShopOrder()).isFalse();
        assertThat(s.hasPhone()).isTrue();

        assertThat(s.toJson()).isEqualTo(FULL);
        assertThat(AudienceSpec.parse(s.toJson())).isEqualTo(s);
    }

    @Test
    @DisplayName("缺键 = null（不筛），toJson 省略 null 键")
    void missingKeysAreNull() {
        AudienceSpec s = AudienceSpec.parse("{\"mode\":\"FILTER\",\"hasPet\":false}");

        assertThat(s.registeredFrom()).isNull();
        assertThat(s.registeredTo()).isNull();
        assertThat(s.activeFrom()).isNull();
        assertThat(s.activeTo()).isNull();
        assertThat(s.species()).isNull();
        assertThat(s.hasShopOrder()).isNull();
        assertThat(s.hasPhone()).isNull();
        assertThat(s.hasPet()).isFalse();
        assertThat(s.toJson()).isEqualTo("{\"mode\":\"FILTER\",\"hasPet\":false}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALL", "IDS"})
    @DisplayName("ALL / IDS 下其余筛选键被丢弃，toJson 只剩 mode")
    void allAndIdsDropFilters(String mode) {
        AudienceSpec s = AudienceSpec.parse(FULL.replace("\"FILTER\"", "\"" + mode + "\""));

        assertThat(s.mode()).isEqualTo(Mode.valueOf(mode));
        assertThat(s.registeredFrom()).isNull();
        assertThat(s.hasPet()).isNull();
        assertThat(s.species()).isNull();
        assertThat(s.toJson()).isEqualTo("{\"mode\":\"" + mode + "\"}");
    }

    @Test
    @DisplayName("species 空数组视同未出现")
    void emptySpeciesIsAbsent() {
        AudienceSpec s = AudienceSpec.parse("{\"mode\":\"FILTER\",\"species\":[]}");

        assertThat(s.species()).isNull();
        assertThat(s.toJson()).isEqualTo("{\"mode\":\"FILTER\"}");
    }

    @Test
    @DisplayName("日期区间含两端：起止同一天合法")
    void sameDayRangeIsValid() {
        AudienceSpec s = AudienceSpec.parse(
                "{\"mode\":\"FILTER\",\"activeFrom\":\"2026-10-12\",\"activeTo\":\"2026-10-12\"}");
        assertThat(s.activeFrom()).isEqualTo(s.activeTo());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        // ① mode 缺失
        "{\"hasPet\":true}",
        // ① mode 非三值之一
        "{\"mode\":\"SOME\"}",
        // ② 日期非 yyyy-MM-dd
        "{\"mode\":\"FILTER\",\"registeredFrom\":\"2026/01/01\"}",
        // ③ registeredFrom > registeredTo
        "{\"mode\":\"FILTER\",\"registeredFrom\":\"2026-02-01\",\"registeredTo\":\"2026-01-31\"}",
        // ④ activeFrom > activeTo
        "{\"mode\":\"FILTER\",\"activeFrom\":\"2026-10-12\",\"activeTo\":\"2026-10-11\"}",
        // ⑤ species 含三值以外的值
        "{\"mode\":\"FILTER\",\"species\":[\"CAT\",\"BIRD\"]}",
        // ⑥ 布尔键给了非布尔
        "{\"mode\":\"FILTER\",\"hasPhone\":\"yes\"}",
        // ⑦ 出现 D-16 以外的键
        "{\"mode\":\"FILTER\",\"userIds\":[1,2,3]}",
        // 附加：不是合法 JSON / 不是对象 / 不存在的日期
        "{mode:",
        "[\"ALL\"]",
        "{\"mode\":\"FILTER\",\"activeTo\":\"2026-02-30\"}",
        // 复审：尾随垃圾 / 重复键（后值静默覆盖前值）
        "{\"mode\":\"ALL\"}xyz",
        "{\"mode\":\"ALL\"}{\"mode\":\"IDS\"}",
        "{\"mode\":\"ALL\",\"mode\":\"FILTER\"}",
        "{\"mode\":\"FILTER\",\"hasPet\":true,\"hasPet\":false}",
        // V1.3.4 返工 §0-5：相对天数须为 1..3650 的整数
        "{\"mode\":\"FILTER\",\"registeredWithinDays\":0}",
        "{\"mode\":\"FILTER\",\"activeWithinDays\":3651}",
        "{\"mode\":\"FILTER\",\"activeWithinDays\":\"30\"}",
        "{\"mode\":\"FILTER\",\"registeredWithinDays\":7.5}"
    })
    @DisplayName("非法输入统一抛 admin.err.ops-notify.audience.invalid")
    void invalidInputsThrowUnifiedCode(String json) {
        assertThatThrownBy(() -> AudienceSpec.parse(json))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getMessageCode())
                        .isEqualTo("admin.err.ops-notify.audience.invalid"));
    }

    @Test
    @DisplayName("ALL / IDS 下出现 D-16 以外的键仍判非法")
    void unknownKeyRejectedInAnyMode() {
        assertThatThrownBy(() -> AudienceSpec.parse("{\"mode\":\"IDS\",\"ids\":[1]}"))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("工厂 all() / ids() 序列化")
    void factories() {
        assertThat(AudienceSpec.all().toJson()).isEqualTo("{\"mode\":\"ALL\"}");
        assertThat(AudienceSpec.ids().toJson()).isEqualTo("{\"mode\":\"IDS\"}");
    }

    @Test
    @DisplayName("V1.3.4 返工 §0-5：相对键 registeredWithinDays / activeWithinDays 往返；与旧绝对日期键可并存")
    void relativeKeysRoundTrip() {
        AudienceSpec s = AudienceSpec.parse("{\"mode\":\"FILTER\",\"activeWithinDays\":30,"
                + "\"registeredWithinDays\":7,\"registeredFrom\":\"2026-01-01\"}");
        assertThat(s.activeWithinDays()).isEqualTo(30);
        assertThat(s.registeredWithinDays()).isEqualTo(7);
        assertThat(s.registeredFrom()).isEqualTo(java.time.LocalDate.of(2026, 1, 1));
        assertThat(AudienceSpec.parse(s.toJson())).isEqualTo(s);
        assertThat(s.toJson()).contains("\"registeredWithinDays\":7").contains("\"activeWithinDays\":30");
        assertThat(s.noFilter()).isFalse();
    }

    @Test
    @DisplayName("V1.3.4 返工：存量行（只有绝对日期键）照常解析；ALL / IDS 丢弃相对键")
    void legacyAbsoluteKeysStillParseAndNonFilterDropsRelative() {
        AudienceSpec legacy = AudienceSpec.parse("{\"mode\":\"FILTER\",\"activeFrom\":\"2026-10-01\","
                + "\"activeTo\":\"2026-10-12\"}");
        assertThat(legacy.activeWithinDays()).isNull();
        assertThat(legacy.activeFrom()).isNotNull();
        AudienceSpec ids = AudienceSpec.parse("{\"mode\":\"IDS\",\"activeWithinDays\":30}");
        assertThat(ids.activeWithinDays()).isNull();
        assertThat(ids.toJson()).isEqualTo("{\"mode\":\"IDS\"}");
        assertThat(AudienceSpec.parse("{\"mode\":\"FILTER\"}").noFilter()).isTrue();
    }
}
