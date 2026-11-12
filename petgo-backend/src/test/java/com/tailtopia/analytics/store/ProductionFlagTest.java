package com.tailtopia.analytics.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.analytics.store.ProductionFlag.UserAccount;
import com.tailtopia.analytics.store.ProductionFlag.Verdict;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** L0：明细层生产标记判定（analytics-pipeline Story 1.6 AC8）。上下文三种标记 × 用户角色 / 账号类型、查不到用户。 */
class ProductionFlagTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final Map<Long, UserAccount> USERS = Map.of(
            1L, new UserAccount("USER", "REAL"),
            2L, new UserAccount("VET", "REAL"),
            3L, new UserAccount("ADMIN", "REAL"),
            4L, new UserAccount("USER", "VIRTUAL"),
            5L, new UserAccount("VET", "VIRTUAL"));

    private static JsonNode ctx(String json) {
        return JSON.readTree(json);
    }

    private static final String STORE = "{\"platform\":\"android\",\"isPhysicalDevice\":true,"
            + "\"installSource\":\"store\",\"isProduction\":true}";
    private static final String SIMULATOR = "{\"platform\":\"android\",\"isPhysicalDevice\":false,"
            + "\"installSource\":\"simulator\",\"isProduction\":false}";
    private static final String TESTFLIGHT = "{\"platform\":\"ios\",\"isPhysicalDevice\":true,"
            + "\"installSource\":\"testflight\"}";
    private static final String SIDELOAD = "{\"platform\":\"android\",\"installSource\":\"sideload\"}";

    @ParameterizedTest(name = "[{index}] user={0} → {1}")
    @CsvSource({
        "1, PRODUCTION",
        "2, NON_PRODUCTION",
        "3, NON_PRODUCTION",
        "4, NON_PRODUCTION",
        "5, NON_PRODUCTION",
        "99, UNKNOWN_USER"
    })
    void storeContextDependsOnUser(long userId, Verdict expected) {
        assertThat(ProductionFlag.judge(ctx(STORE), userId, USERS)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] user={0}")
    @CsvSource({"1", "2", "3", "4", "5", "99"})
    void simulatorTestflightSideloadAreNeverProduction(long userId) {
        for (String c : new String[] {SIMULATOR, TESTFLIGHT, SIDELOAD}) {
            Verdict v = ProductionFlag.judge(ctx(c), userId, USERS);
            assertThat(v).as(c).isEqualTo(Verdict.NON_PRODUCTION);
            assertThat(v.isProduction()).isFalse();
        }
    }

    @Test
    void contextMarkersWithoutUser() {
        assertThat(ProductionFlag.judge(ctx(STORE), null, USERS)).isEqualTo(Verdict.PRODUCTION);
        assertThat(ProductionFlag.judge(ctx(SIMULATOR), null, USERS)).isEqualTo(Verdict.NON_PRODUCTION);
        assertThat(ProductionFlag.judge(ctx(TESTFLIGHT), null, USERS)).isEqualTo(Verdict.NON_PRODUCTION);
        assertThat(ProductionFlag.judge(ctx(SIDELOAD), null, USERS)).isEqualTo(Verdict.NON_PRODUCTION);
    }

    @Test
    void eachMarkerAloneMakesNonProduction() {
        assertThat(ProductionFlag.judge(ctx("{\"isPhysicalDevice\":false}"), 1L, USERS))
                .isEqualTo(Verdict.NON_PRODUCTION);
        assertThat(ProductionFlag.judge(ctx("{\"isProduction\":false}"), 1L, USERS))
                .isEqualTo(Verdict.NON_PRODUCTION);
        assertThat(ProductionFlag.judge(ctx("{\"installSource\":\"TestFlight\"}"), 1L, USERS))
                .as("大小写不敏感").isEqualTo(Verdict.NON_PRODUCTION);
        assertThat(ProductionFlag.judge(ctx("{\"isPhysicalDevice\":\"false\"}"), 1L, USERS))
                .as("字符串 false 容错").isEqualTo(Verdict.NON_PRODUCTION);
    }

    @Test
    void contradictoryContextPrefersNonProduction() {
        assertThat(ProductionFlag.judge(ctx("{\"isProduction\":true,\"isPhysicalDevice\":false}"), 1L, USERS))
                .isEqualTo(Verdict.NON_PRODUCTION);
    }

    @Test
    void missingOrEmptyContextCountsAsProduction() {
        // 已知偏差：App 事件各键都缺失视为生产；服务端 / H5 事件本来就没有设备上下文
        assertThat(ProductionFlag.judge(ctx("{}"), 1L, USERS)).isEqualTo(Verdict.PRODUCTION);
        assertThat(ProductionFlag.judge(null, 1L, USERS)).isEqualTo(Verdict.PRODUCTION);
        assertThat(ProductionFlag.judge(null, null, Map.of())).isEqualTo(Verdict.PRODUCTION);
        assertThat(ProductionFlag.judge(ctx("{\"installSource\":\"store\",\"isPhysicalDevice\":null}"), 1L, USERS))
                .isEqualTo(Verdict.PRODUCTION);
    }

    @Test
    void serverEventWithoutContextUsesUserOnly() {
        assertThat(ProductionFlag.judge(null, 2L, USERS)).isEqualTo(Verdict.NON_PRODUCTION);
        assertThat(ProductionFlag.judge(null, 4L, USERS)).isEqualTo(Verdict.NON_PRODUCTION);
        assertThat(ProductionFlag.judge(null, 42L, USERS)).isEqualTo(Verdict.UNKNOWN_USER);
        assertThat(Verdict.UNKNOWN_USER.isProduction()).isFalse();
    }
}
