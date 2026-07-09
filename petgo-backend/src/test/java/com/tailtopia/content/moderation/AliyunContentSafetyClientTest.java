package com.tailtopia.content.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

/**
 * L0（无网络）：live 客户端的 fail-closed 护栏。真实 green20220302 端到端评分属 L2（需 AK + 网络）。
 * 这里只验安全攸关不变量：凭证缺失 / 图像未开通 一律降级、绝不 PASS。
 */
class AliyunContentSafetyClientTest {

    @Test
    void missingCredentialsFailClosedOnText() {
        // 未配 AK/Secret → 客户端不初始化，扫描降级（HTTP_4XX），绝不 PASS。
        AliyunContentSafetyClient client = new AliyunContentSafetyClient(new ModerationProperties());
        ModerationDegradedException ex = catchThrowableOfType(
                () -> client.scanText("apa saja"), ModerationDegradedException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.reason()).isEqualTo(DegradeReason.HTTP_4XX);
    }

    @Test
    void imageModerationAlwaysFailClosed() {
        // 图像审核本期未开通：即便配了 AK 也恒降级（转人工），绝不误判。
        ModerationProperties props = new ModerationProperties();
        props.getAliyun().setAccessKeyId("dummy");
        props.getAliyun().setAccessKeySecret("dummy");
        AliyunContentSafetyClient client = new AliyunContentSafetyClient(props);
        assertThatThrownBy(() -> client.scanImage("https://example.com/a.jpg"))
                .isInstanceOf(ModerationDegradedException.class);
    }
}
