package com.tailtopia.analytics.rollup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 推送汇总配置 {@code analytics.rollup.push-campaign-days}（analytics-pipeline Story 4.4）：运营推送「累计打开人数」
 * （{@code agg_daily_push.campaign_opened_persons_cum}）只计发送后多少天内的打开，同时决定每天给多少天内发出的活动补行刷新累计值。
 * 默认 14，须在 1–92（明细保留期）之间。由 {@link RollupConfig} 上的 {@code @EnableConfigurationProperties} 绑定
 * （与 {@link AnalyticsRollupProperties} 同前缀，各绑各的键）。
 *
 * <p>🔴 <b>配置非法不阻断后端启动</b>（4-2 复审结转，写法同 {@link Ga4SnapshotProperties}）：按字符串绑定、自行解析；
 * 值非法（如 env {@code ANALYTICS_ROLLUP_PUSH_CAMPAIGN_DAYS=14d}）时只<b>停用累计打开人数</b>（该列写空、不再给跟踪期内的活动补行，
 * 日 / 周 / 月打开数照常），启动时打 WARN，{@link PushFamily} 在应用就绪时发 TECH 告警。改对后重算窗口内的行自动恢复。
 */
@ConfigurationProperties(prefix = "analytics.rollup")
public final class PushRollupProperties {

    private static final Logger log = LoggerFactory.getLogger(PushRollupProperties.class);

    static final int DEFAULT_CAMPAIGN_DAYS = 14;
    static final int MAX_CAMPAIGN_DAYS = 92;

    /** 0 = 停用（配置非法）。 */
    private final int pushCampaignDays;
    /** 非 null = 配置非法，内容只含键名，不含配置值。 */
    private final String invalidReason;

    /** Spring 绑定用：按字符串接收，非法值不抛、改为停用。 */
    @ConstructorBinding
    public PushRollupProperties(@DefaultValue("14") String pushCampaignDays) {
        Integer d = parse(pushCampaignDays);
        if (d == null) {
            this.pushCampaignDays = 0;
            this.invalidReason = "analytics.rollup.push-campaign-days 须为 1–" + MAX_CAMPAIGN_DAYS + " 的整数";
            log.warn("analytics rollup push campaign cumulative opens disabled: invalid config ({})", invalidReason);
        } else {
            this.pushCampaignDays = d;
            this.invalidReason = null;
        }
    }

    /** 测试 / 代码内构造用。 */
    public PushRollupProperties(int pushCampaignDays) {
        this(String.valueOf(pushCampaignDays));
    }

    /** 全默认值（测试用）。 */
    public static PushRollupProperties defaults() {
        return new PushRollupProperties(DEFAULT_CAMPAIGN_DAYS);
    }

    private static Integer parse(String raw) {
        try {
            int d = Integer.parseInt(raw == null ? "" : raw.strip());
            return d < 1 || d > MAX_CAMPAIGN_DAYS ? null : d;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 跟踪天数；0 = 配置非法、已停用累计打开人数。 */
    public int pushCampaignDays() {
        return pushCampaignDays;
    }

    /** 配置非法的原因（只含键名）；合法 = null。 */
    public String invalidReason() {
        return invalidReason;
    }
}
