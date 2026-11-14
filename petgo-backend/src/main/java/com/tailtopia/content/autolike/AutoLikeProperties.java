package com.tailtopia.content.autolike;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自动点赞配置（V1.5.0 PRD 自动点赞 FR-222，架构 L-12）。前缀 {@code petgo.auto-like}。
 *
 * <p>运营侧不设开关（决策 2026-11-09：规则稳定、只留档）。{@link #enabled} 是<b>部署层</b>开关：
 * base 默认开（stag / 生产部署即生效，同自动评论）；{@code application-dev.yml} 覆盖为关，本机连真库也不会点赞、推送。
 * 要停某个环境就在 env 里设 {@code AUTO_LIKE_ENABLED=false}。改任何值都要改配置 + 重启（A-AC41）。
 *
 * <p>启动校验见 {@link #validate()}：不合法的组合直接启动失败，不带病运行。
 */
@ConfigurationProperties(prefix = "petgo.auto-like")
public class AutoLikeProperties {

    /** 部署层开关，base 默认开（dev profile 覆盖为关）。 */
    private boolean enabled = true;

    /** 早场 cron（Asia/Jakarta）：10:30。 */
    private String morningCron = "0 30 10 * * *";

    /** 晚场 cron（Asia/Jakarta）：16:30。 */
    private String eveningCron = "0 30 16 * * *";

    /** 检查窗口下界：发帖满这么久才检查（含）。 */
    private Duration checkWindowMin = Duration.ofHours(36);

    /** 检查窗口上界：发帖超过这么久不再检查（开区间）。 */
    private Duration checkWindowMax = Duration.ofHours(60);

    /** 帖子最长年龄：超过即离开集合（MAX_AGE）；同时是上线首轮的检查上限。 */
    private Duration maxPostAge = Duration.ofDays(7);

    /** 目标赞数（展示口径）：达到即离开。 */
    private int likeTarget = 5;

    /** 每帖每轮赞数：目前只支持 1（FR-212「每槽每帖最多一赞」，待拍板 Q-1）。 */
    private int likesPerRun = 1;

    /** 每位作者每轮最多被赞几条帖。 */
    private int maxLikesPerAuthorPerRun = 1;

    /** 一轮的赞随机散布在本轮开始后的这段时间里。 */
    private Duration spreadWindow = Duration.ofHours(3);

    /** 对照组比例（0–100，按作者）。 */
    private int holdoutPercent = 10;

    /** 对照组哈希盐：各环境相同、不进 env（改盐 = 换一批对照组，待拍板 Q-10）。 */
    private String holdoutSalt = "tt-auto-like-holdout-v1";

    /** 同一帖失败多少次后离开（TOO_MANY_FAILURES）。 */
    private int maxFailures = 3;

    /** 一轮内连续失败多少条后叫停本轮。 */
    private int stopAfterConsecutiveFailures = 10;

    /** 派发扫描间隔（毫秒，实现参数）。 */
    private long dispatchIntervalMs = 60_000L;

    /** 每次派发扫描最多处理多少条（实现参数）。 */
    private int dispatchBatch = 200;

    /** 启动校验：不合法即启动失败。 */
    @PostConstruct
    public void validate() {
        if (likesPerRun != 1) {
            throw new IllegalStateException(
                    "petgo.auto-like.likes-per-run 目前只支持 1（每槽每帖一赞，见 FR-212）");
        }
        if (checkWindowMin == null || checkWindowMax == null || maxPostAge == null
                || checkWindowMin.isNegative() || checkWindowMin.isZero()
                || checkWindowMin.compareTo(checkWindowMax) >= 0 || checkWindowMax.compareTo(maxPostAge) > 0) {
            throw new IllegalStateException(
                    "petgo.auto-like 窗口配置不合法：须 check-window-min < check-window-max <= max-post-age");
        }
        if (holdoutPercent < 0 || holdoutPercent > 100) {
            throw new IllegalStateException("petgo.auto-like.holdout-percent 须在 0–100");
        }
        if (likeTarget < 1) {
            throw new IllegalStateException("petgo.auto-like.like-target 须 >= 1");
        }
        if (spreadWindow == null || spreadWindow.isNegative() || spreadWindow.isZero()) {
            throw new IllegalStateException("petgo.auto-like.spread-window 须 > 0");
        }
        if (maxLikesPerAuthorPerRun < 1 || maxFailures < 1 || stopAfterConsecutiveFailures < 1
                || dispatchBatch < 1 || dispatchIntervalMs < 1) {
            throw new IllegalStateException("petgo.auto-like 的 max-likes-per-author-per-run / max-failures / "
                    + "stop-after-consecutive-failures / dispatch-batch / dispatch-interval-ms 须 >= 1");
        }
        if (holdoutSalt == null || holdoutSalt.isBlank()) {
            throw new IllegalStateException("petgo.auto-like.holdout-salt 不能为空");
        }
    }

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

    public Duration getCheckWindowMin() {
        return checkWindowMin;
    }

    public void setCheckWindowMin(Duration checkWindowMin) {
        this.checkWindowMin = checkWindowMin;
    }

    public Duration getCheckWindowMax() {
        return checkWindowMax;
    }

    public void setCheckWindowMax(Duration checkWindowMax) {
        this.checkWindowMax = checkWindowMax;
    }

    public Duration getMaxPostAge() {
        return maxPostAge;
    }

    public void setMaxPostAge(Duration maxPostAge) {
        this.maxPostAge = maxPostAge;
    }

    public int getLikeTarget() {
        return likeTarget;
    }

    public void setLikeTarget(int likeTarget) {
        this.likeTarget = likeTarget;
    }

    public int getLikesPerRun() {
        return likesPerRun;
    }

    public void setLikesPerRun(int likesPerRun) {
        this.likesPerRun = likesPerRun;
    }

    public int getMaxLikesPerAuthorPerRun() {
        return maxLikesPerAuthorPerRun;
    }

    public void setMaxLikesPerAuthorPerRun(int maxLikesPerAuthorPerRun) {
        this.maxLikesPerAuthorPerRun = maxLikesPerAuthorPerRun;
    }

    public Duration getSpreadWindow() {
        return spreadWindow;
    }

    public void setSpreadWindow(Duration spreadWindow) {
        this.spreadWindow = spreadWindow;
    }

    public int getHoldoutPercent() {
        return holdoutPercent;
    }

    public void setHoldoutPercent(int holdoutPercent) {
        this.holdoutPercent = holdoutPercent;
    }

    public String getHoldoutSalt() {
        return holdoutSalt;
    }

    public void setHoldoutSalt(String holdoutSalt) {
        this.holdoutSalt = holdoutSalt;
    }

    public int getMaxFailures() {
        return maxFailures;
    }

    public void setMaxFailures(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    public int getStopAfterConsecutiveFailures() {
        return stopAfterConsecutiveFailures;
    }

    public void setStopAfterConsecutiveFailures(int stopAfterConsecutiveFailures) {
        this.stopAfterConsecutiveFailures = stopAfterConsecutiveFailures;
    }

    public long getDispatchIntervalMs() {
        return dispatchIntervalMs;
    }

    public void setDispatchIntervalMs(long dispatchIntervalMs) {
        this.dispatchIntervalMs = dispatchIntervalMs;
    }

    public int getDispatchBatch() {
        return dispatchBatch;
    }

    public void setDispatchBatch(int dispatchBatch) {
        this.dispatchBatch = dispatchBatch;
    }
}
