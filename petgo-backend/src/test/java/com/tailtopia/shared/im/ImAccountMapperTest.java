package com.tailtopia.shared.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * L0：IM 账号环境前缀（2026-09-24，bug 519/521）。
 *
 * <p>回归护栏：前缀为空（生产）时输出/解析与改前逐字一致；前缀 {@code stg_} 时拼接与反解正确；
 * 不带当前前缀的账号在有前缀环境下解析为「非本环境」；非法前缀 fail-fast。
 */
class ImAccountMapperTest {

    @AfterEach
    void resetToProductionDefault() {
        // 静态状态：每条用例后恢复空前缀，避免污染同 JVM 其它测试（它们断言 u_/v_ 字面量）。
        ImAccountMapper.configure("");
    }

    @Test
    void emptyPrefixIsByteForByteIdenticalToLegacyFormat() {
        ImAccountMapper.configure("");
        assertThat(ImAccountMapper.userImId(75)).isEqualTo("u_75");
        assertThat(ImAccountMapper.vetImId(1)).isEqualTo("v_1");
        assertThat(ImAccountMapper.parseUserId("u_75")).hasValue(75);
        assertThat(ImAccountMapper.parseVetId("v_1")).hasValue(1);
        assertThat(ImAccountMapper.isLocalAccount("u_75")).isTrue();
    }

    @Test
    void nullPrefixTreatedAsEmpty() {
        ImAccountMapper.configure(null);
        assertThat(ImAccountMapper.prefix()).isEmpty();
        assertThat(ImAccountMapper.userImId(7)).isEqualTo("u_7");
    }

    @Test
    void stagPrefixBuildsAndParsesRoundTrip() {
        ImAccountMapper.configure("stg_");
        assertThat(ImAccountMapper.userImId(75)).isEqualTo("stg_u_75");
        assertThat(ImAccountMapper.vetImId(1)).isEqualTo("stg_v_1");
        assertThat(ImAccountMapper.parseUserId("stg_u_75")).hasValue(75);
        assertThat(ImAccountMapper.parseVetId("stg_v_1")).hasValue(1);
        assertThat(ImAccountMapper.isLocalAccount("stg_v_1")).isTrue();
    }

    @Test
    void unprefixedAccountIsForeignWhenPrefixConfigured() {
        // stag 上出现生产格式账号 → 非本环境，绝不映射到本环境同 id 用户。
        ImAccountMapper.configure("stg_");
        assertThat(ImAccountMapper.parseUserId("u_75")).isEmpty();
        assertThat(ImAccountMapper.parseVetId("v_1")).isEmpty();
        assertThat(ImAccountMapper.isLocalAccount("u_75")).isFalse();
    }

    @Test
    void prefixedAccountIsForeignInProduction() {
        // 反方向：生产（空前缀）见到 stag 账号同样不认。
        ImAccountMapper.configure("");
        assertThat(ImAccountMapper.parseUserId("stg_u_75")).isEmpty();
        assertThat(ImAccountMapper.parseVetId("stg_v_1")).isEmpty();
    }

    @Test
    void roleMismatchAndMalformedAreRejected() {
        ImAccountMapper.configure("stg_");
        assertThat(ImAccountMapper.parseUserId("stg_v_1")).isEmpty();
        assertThat(ImAccountMapper.parseVetId("stg_u_1")).isEmpty();
        assertThat(ImAccountMapper.parseUserId("stg_u_")).isEmpty();
        assertThat(ImAccountMapper.parseUserId("stg_u_12a")).isEmpty();
        assertThat(ImAccountMapper.parseUserId("stg_u_-1")).isEmpty();
        assertThat(ImAccountMapper.parseUserId(null)).isEmpty();
        assertThat(ImAccountMapper.parseUserId("administrator")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STG_", "stg-", "stg_prefix", "stg ", "中文", "a.b"})
    void illegalPrefixFailsFast(String bad) {
        assertThatThrownBy(() -> ImAccountMapper.configure(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IM_ACCOUNT_PREFIX");
        // 失败不改动已生效前缀。
        assertThat(ImAccountMapper.prefix()).isEmpty();
    }

    @Test
    void imConfigAppliesPrefixAndRejectsIllegalAtWiring() {
        ImProperties props = new ImProperties();
        props.setAccountPrefix("stg_");
        new ImConfig().tencentImClient(props);
        assertThat(ImAccountMapper.userImId(3)).isEqualTo("stg_u_3");

        ImProperties bad = new ImProperties();
        bad.setAccountPrefix("Stg-");
        assertThatThrownBy(() -> new ImConfig().tencentImClient(bad))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void defaultPropertyIsEmpty() {
        assertThat(new ImProperties().getAccountPrefix()).isEmpty();
    }
}
