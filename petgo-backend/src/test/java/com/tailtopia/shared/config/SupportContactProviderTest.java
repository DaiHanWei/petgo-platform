package com.tailtopia.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.config.domain.SupportContactConfig;
import com.tailtopia.config.repository.SupportContactConfigRepository;
import com.tailtopia.config.service.DbSupportContactProvider;
import com.tailtopia.config.service.PlatformConfigService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：客服联系方式 provider（Story 3-1 AC3/AC8）。
 *
 * <p>🔴 本类的重点是**回退**：客服号的消费方里有「账号被停用」的登录拒绝文案 ——
 * 配置表出问题时若抛异常，用户会连「找谁申诉」都看不到，而那正是他最需要看到客服号的时刻。
 * 所以 {@link DbSupportContactProvider} 的每条失败路径都必须有一条用例钉住「回退且不抛」。
 *
 * <p>⚠️ **不重复造归一化本身的测试**：{@code IndonesiaPhoneTest.threeInputFormsNormalizeToSameValue}
 * 已经覆盖了三种写法归一到同一个 E.164。本类验的是「provider 层把它接对了」。
 */
class SupportContactProviderTest {

    private final SupportContactProvider.Default fallback = new SupportContactProvider.Default();

    /** 造一个已持久化形态的配置行（实体是 JPA 实体，只有 protected 构造器）。 */
    private static SupportContactConfig row(String number, String email) {
        SupportContactConfig c = BeanUtils.instantiateClass(SupportContactConfig.class);
        ReflectionTestUtils.setField(c, "id", SupportContactConfig.SINGLETON_ID);
        ReflectionTestUtils.setField(c, "whatsappNumber", number);
        ReflectionTestUtils.setField(c, "email", email);
        return c;
    }

    private DbSupportContactProvider providerReturning(SupportContactConfig row) {
        SupportContactConfigRepository repo = mock(SupportContactConfigRepository.class);
        when(repo.findById(anyLong())).thenReturn(Optional.ofNullable(row));
        PlatformConfigService config = new PlatformConfigService(null, null, null, null, repo);
        return new DbSupportContactProvider(config, fallback);
    }

    // ---------- AC8：三种写法 → 同一个 E.164，原样写法保留 ----------

    @Test
    @DisplayName("三种输入写法落库后 whatsappE164 相同，whatsappNumber 保留运营输入的原样")
    void threeInputFormsYieldSameE164ButKeepRawNumber() {
        for (String raw : new String[] {"08123456789", "8123456789", "+62 812-3456-789"}) {
            SupportContact c = providerReturning(row(raw, "cs@tailtopia.id")).contact();

            assertThat(c.whatsappE164())
                    .as("深链拨出去的号码必须与写法无关")
                    .isEqualTo("+628123456789");
            assertThat(c.whatsappNumber())
                    .as("展示与复制用的是运营输入的原样写法（印尼人认 08xx 这个形式）")
                    .isEqualTo(raw);
        }
    }

    @Test
    @DisplayName("实际配置值 081290906953 → +6281290906953")
    void actualConfiguredNumberNormalises() {
        SupportContact c = providerReturning(row("081290906953", "cs@tailtopia.id")).contact();

        assertThat(c.whatsappNumber()).isEqualTo("081290906953");
        assertThat(c.whatsappE164()).isEqualTo("+6281290906953");
        assertThat(c.email()).isEqualTo("cs@tailtopia.id");
    }

    // ---------- AC3：回退 ----------

    @Nested
    @DisplayName("🔴 回退：配置出问题也绝不让客服号消失")
    class Fallback {

        @Test
        @DisplayName("DB 单行缺失 → 回退内置值，不抛")
        void missingRowFallsBack() {
            SupportContact c = providerReturning(null).contact();

            assertThat(c.whatsappNumber()).isEqualTo("081290906953");
            assertThat(c.whatsappE164()).isEqualTo("+6281290906953");
            assertThat(c.email()).isEqualTo("cs@tailtopia.id");
        }

        @Test
        @DisplayName("库里存了个归一化不了的号码 → 回退内置值，不抛")
        void unnormalisableNumberFallsBack() {
            // 运营存进去时是合法的、后来校验规则收紧了，或者数据是别处灌的 —— 都不能把登录页搞挂。
            SupportContact c =
                    providerReturning(row("not-a-phone", "cs@tailtopia.id")).contact();

            assertThat(c).isEqualTo(fallback.contact());
        }

        @Test
        @DisplayName("读库直接抛异常 → 回退内置值，不抛")
        void repositoryFailureFallsBack() {
            SupportContactConfigRepository repo = mock(SupportContactConfigRepository.class);
            when(repo.findById(anyLong())).thenThrow(new IllegalStateException("db down"));
            var provider = new DbSupportContactProvider(
                    new PlatformConfigService(null, null, null, null, repo), fallback);

            assertThat(provider.contact()).isEqualTo(fallback.contact());
        }
    }

    @Test
    @DisplayName("内置 Default 本身也是合法的 —— 兜底值自己算不出 E.164 就白兜了")
    void builtInDefaultIsItselfValid() {
        SupportContact c = fallback.contact();

        assertThat(c.whatsappNumber()).isEqualTo("081290906953");
        assertThat(c.whatsappE164()).isEqualTo("+6281290906953");
        assertThat(c.email()).isEqualTo("cs@tailtopia.id");
    }

    @Test
    @DisplayName("E.164 带 + —— wa.me 要剥 + 是调用方的事，provider 给标准值")
    void e164KeepsThePlusSign() {
        assertThat(fallback.contact().whatsappE164()).startsWith("+");
    }
}
