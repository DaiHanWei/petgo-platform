package com.tailtopia.shared.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L0：客户端回传 objectKey 的归属校验（D-10）。
 *
 * <p>直传拿到的 key 由客户端回传、业务侧存进自己的表，此前**没有任何一处校验它是不是真的**。
 * 退货凭证那条链路上，App 端压根没调相册、只塞了字面量 {@code return-evidence-1/2/…}，
 * 服务端照单全收 —— 运营在审核页无图可看。
 */
class MediaObjectKeysTest {

    private MediaProperties props(String keyPrefix) {
        MediaProperties p = new MediaProperties();
        p.getOss().setKeyPrefix(keyPrefix);
        return p;
    }

    @Test
    @DisplayName("自己直传的 key → 通过")
    void ownKeyPasses() {
        assertThat(MediaObjectKeys.belongsTo(
                props("stag/"), MediaScope.PRIVATE, 42L, "stag/private/42/abc123.jpg")).isTrue();
    }

    @Test
    @DisplayName("🔴 编造的字符串 → 拒（D-10 里入库的正是这种）")
    void fabricatedKeyRejected() {
        assertThat(MediaObjectKeys.belongsTo(
                props("stag/"), MediaScope.PRIVATE, 42L, "return-evidence-1")).isFalse();
    }

    @Test
    @DisplayName("🔴 别人的 key → 拒（前缀里带 userId，一条判定同时挡越权）")
    void otherUsersKeyRejected() {
        assertThat(MediaObjectKeys.belongsTo(
                props("stag/"), MediaScope.PRIVATE, 42L, "stag/private/43/abc123.jpg")).isFalse();
        // 43 开头但其实是 431 的 —— 不能用「以 42 开头」这种松判据
        assertThat(MediaObjectKeys.belongsTo(
                props("stag/"), MediaScope.PRIVATE, 42L, "stag/private/421/abc.jpg")).isFalse();
    }

    @Test
    @DisplayName("🔴 隐私域不对 → 拒（私有凭证不能拿公开桶的 key 冒充）")
    void wrongScopeRejected() {
        assertThat(MediaObjectKeys.belongsTo(
                props("stag/"), MediaScope.PRIVATE, 42L, "stag/public/42/abc.jpg")).isFalse();
    }

    @Test
    @DisplayName("只有前缀、没有对象名 → 拒")
    void prefixOnlyRejected() {
        assertThat(MediaObjectKeys.belongsTo(
                props("stag/"), MediaScope.PRIVATE, 42L, "stag/private/42/")).isFalse();
    }

    @Test
    @DisplayName("keyPrefix 未配（本地/测试）同样成立")
    void worksWithoutKeyPrefix() {
        assertThat(MediaObjectKeys.belongsTo(
                props(null), MediaScope.PRIVATE, 7L, "private/7/x.jpg")).isTrue();
        assertThat(MediaObjectKeys.belongsTo(
                props(null), MediaScope.PRIVATE, 7L, "private/8/x.jpg")).isFalse();
    }

    @Test
    @DisplayName("null / 空 key → 拒，不 NPE")
    void nullAndBlankRejected() {
        assertThat(MediaObjectKeys.belongsTo(props("s/"), MediaScope.PRIVATE, 1L, null)).isFalse();
        assertThat(MediaObjectKeys.belongsTo(props("s/"), MediaScope.PRIVATE, 1L, "  ")).isFalse();
    }

    @Test
    @DisplayName("批量：任一不合格即整体拒")
    void requireAllOwnedRejectsAnyBad() {
        MediaProperties p = props("stag/");
        assertThatThrownBy(() -> MediaObjectKeys.requireAllOwned(p, MediaScope.PRIVATE, 42L,
                List.of("stag/private/42/a.jpg", "return-evidence-2"), "凭证图"))
                .isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("🔴 错误文案不回显用户传的原串 —— 那等于把可控字符串送回响应体")
    void errorDoesNotEchoInput() {
        MediaProperties p = props("stag/");
        assertThatThrownBy(() -> MediaObjectKeys.requireAllOwned(p, MediaScope.PRIVATE, 42L,
                List.of("<script>x</script>"), "凭证图"))
                .isInstanceOf(AppException.class)
                .hasMessageNotContaining("script");
    }

    @Test
    @DisplayName("空列表 / null → 放行（是否必填由各业务自己判）")
    void emptyIsCallerConcern() {
        MediaProperties p = props("stag/");
        MediaObjectKeys.requireAllOwned(p, MediaScope.PRIVATE, 42L, List.of(), "凭证图");
        MediaObjectKeys.requireAllOwned(p, MediaScope.PRIVATE, 42L, null, "凭证图");
    }
}
