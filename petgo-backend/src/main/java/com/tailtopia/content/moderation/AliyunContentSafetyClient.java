package com.tailtopia.content.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阿里云内容安全（green20220302）live 客户端（内容审核 Story 1，{@code app.moderation.mode=live}）。
 *
 * <p><b>⚠️ L2 待接真实 SDK/凭证</b>：本类目前为 <b>fail-closed 占位</b>。为守「不加中间件/避开拉不下来的新依赖」
 * 护栏（本项目 Nexus 常宕），未引入 {@code aliyun-java-sdk-green} / green20220302 typed SDK；真实
 * {@code TextModerationPlus}/{@code ImageModeration} 调用 + confidence 归一 + label→阈值校准（§4.2）+
 * ap-southeast-1 印尼语实测（AC9/AC10/AC11）在本地 L2 阶段接线。
 *
 * <p>占位语义严格 <b>fail-closed</b>：{@code mode=live} 但真实 SDK 未接时，一律抛
 * {@link ModerationDegradedException}（HTTP_5XX），门面映射为 {@code DEGRADED} → 交调用方转人工队列，
 * <b>绝不返回 PASS</b>——即使误切 live，也不会漏放违规内容。接真实 SDK 时仅替换本类内部两方法。
 *
 * <p>护栏：AK/Secret 从 {@link ModerationProperties.Aliyun} 注入，<b>绝不落日志</b>；日志仅记 region/是否配置齐，
 * 不记原文 / 图 URL / AK。
 */
public class AliyunContentSafetyClient implements ContentSafetyClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunContentSafetyClient.class);

    private final ModerationProperties props;

    public AliyunContentSafetyClient(ModerationProperties props) {
        this.props = props;
        boolean credsPresent = notBlank(props.getAliyun().getAccessKeyId())
                && notBlank(props.getAliyun().getAccessKeySecret());
        // 仅记 region + 凭证是否齐备（布尔），绝不记 AK/Secret 本身。
        log.warn("Aliyun content-safety live client is a FAIL-CLOSED placeholder "
                + "(green20220302 SDK not wired yet); region={}, credentialsConfigured={}. "
                + "All calls DEGRADE (never PASS) until L2 wiring.",
                props.getAliyun().getRegion(), credsPresent);
    }

    @Override
    public TextScore scanText(String text) {
        throw pendingWiring("scanText");
    }

    @Override
    public ImageScore scanImage(String imageUrl) {
        throw pendingWiring("scanImage");
    }

    private ModerationDegradedException pendingWiring(String op) {
        // fail-closed：真实 SDK 未接前，任何 live 调用降级为 DEGRADED（HTTP_5XX 语义），绝不 PASS。
        return new ModerationDegradedException(DegradeReason.HTTP_5XX,
                "aliyun green20220302 SDK not wired yet (" + op + "); L2 pending");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
