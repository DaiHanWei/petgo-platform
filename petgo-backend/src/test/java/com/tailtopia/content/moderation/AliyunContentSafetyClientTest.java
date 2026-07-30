package com.tailtopia.content.moderation;

import static org.assertj.core.api.Assertions.assertThat;
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
    void missingCredentialsFailClosedOnImage() {
        // 图审已接大小模型融合（postImageCheckByVL_global）：凭证缺失 → 客户端不初始化，
        // 立即降级（HTTP_4XX，无网络调用），绝不 PASS。真实调用属 L2。
        AliyunContentSafetyClient client = new AliyunContentSafetyClient(new ModerationProperties());
        ModerationDegradedException ex = catchThrowableOfType(
                () -> client.scanImage("https://example.com/a.jpg"), ModerationDegradedException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.reason()).isEqualTo(DegradeReason.HTTP_4XX);
    }

    // ---------- 图审标签 → 内部三分类映射（纯函数 L0） ----------

    @Test
    void mergeImageLabel_mapsAliyunLabelsToInternalCategories() {
        java.util.Map<String, Double> m = new java.util.HashMap<>();
        AliyunContentSafetyClient.mergeImageLabel(m, "pornographic_adultContent", 0.92);
        AliyunContentSafetyClient.mergeImageLabel(m, "sexual_suggestiveContent", 0.40);
        AliyunContentSafetyClient.mergeImageLabel(m, "violent_explosion", 0.81);
        AliyunContentSafetyClient.mergeImageLabel(m, "contraband_drug", 0.77);
        assertThat(m.get("porn")).isEqualTo(0.92); // 同分类取最大（0.92 > 0.40）
        assertThat(m.get("violence")).isEqualTo(0.81);
        assertThat(m.get("contraband")).isEqualTo(0.77);
    }

    @Test
    void mergeImageLabel_ignoresUnmappedAndBlankLabels() {
        java.util.Map<String, Double> m = new java.util.HashMap<>();
        AliyunContentSafetyClient.mergeImageLabel(m, "nonLabel", 1.0); // 阿里云「无风险」标签
        AliyunContentSafetyClient.mergeImageLabel(m, "ad_creditCard", 0.9); // §4.2 三类之外不参与硬拦截
        AliyunContentSafetyClient.mergeImageLabel(m, null, 0.9);
        AliyunContentSafetyClient.mergeImageLabel(m, "  ", 0.9);
        assertThat(m).isEmpty();
    }
}
