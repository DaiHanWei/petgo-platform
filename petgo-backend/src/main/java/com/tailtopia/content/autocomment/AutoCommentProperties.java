package com.tailtopia.content.autocomment;

import java.time.Duration;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自动评论配置（2026-09-29）。前缀 {@code petgo.auto-comment}。
 *
 * <p>运营侧不设开关（产品拍板：规则稳定、直接发、只留档）。{@link #enabled} 是<b>部署层</b>开关：
 * 默认关，按环境在 env 里打开，stag 先验、生产确认后再开。
 */
@ConfigurationProperties(prefix = "petgo.auto-comment")
public class AutoCommentProperties {

    /** 部署层开关，默认关。 */
    private boolean enabled = false;

    /** 早场 cron（Asia/Jakarta）：09:30。 */
    private String morningCron = "0 30 9 * * *";

    /** 晚场 cron（Asia/Jakarta）：20:00。 */
    private String eveningCron = "0 0 20 * * *";

    /** 只评这一天（WIB 零点）之后发的帖，老帖没有意义。 */
    private LocalDate startDate = LocalDate.of(2026, 9, 29);

    /** 发帖满多久仍零评论才评。 */
    private Duration minPostAge = Duration.ofHours(2);

    /** 单轮最多处理多少帖（兜底，防积压时一轮跑太久）。 */
    private int maxPerRun = 200;

    /** 同一帖 AI 失败最多尝试几次。 */
    private int maxAttempts = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMorningCron() {
        return morningCron;
    }

    public void setMorningCron(String morningCron) {
        this.morningCron = morningCron;
    }

    public String getEveningCron() {
        return eveningCron;
    }

    public void setEveningCron(String eveningCron) {
        this.eveningCron = eveningCron;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public Duration getMinPostAge() {
        return minPostAge;
    }

    public void setMinPostAge(Duration minPostAge) {
        this.minPostAge = minPostAge;
    }

    public int getMaxPerRun() {
        return maxPerRun;
    }

    public void setMaxPerRun(int maxPerRun) {
        this.maxPerRun = maxPerRun;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }
}
