package com.tailtopia.notify.lifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 生命周期推送配置（留存运营作战手册 · 抓手 1）。前缀 {@code petgo.lifecycle-push}。
 *
 * <p>手册第五章定的顺序是「先人工把阈值跑准，再自动化」。这几个旋钮就是为那两周准备的：
 * 运营先小批量放，看召回漏斗的送达/点击/建档，再决定拧多大。
 */
@ConfigurationProperties(prefix = "petgo.lifecycle-push")
public class LifecyclePushProperties {

    /**
     * 总开关，<b>默认关</b>。
     *
     * <p>刻意默认关：迁移把存量用户的 {@code last_active_at} 回填成 {@code updated_at} 之后，
     * 大量老用户会立刻满足「7 天未回」。若随部署自动生效，第一次日扫就会给几百人同时推召回
     * —— 手册要的是「按 SOP 放量、看漏斗、换文案」，不是一次性把召回额度烧光。
     * 上线后由运营在 env 里显式打开。
     */
    private boolean enabled = false;

    /** 每日投递上限。超出的推迟到下一次日扫（planner 已按 D1→D3→D7→召回排序，先保时效性最强的）。 */
    private int dailyCap = 200;

    /** 「多久没回来算流失」（天）。手册定义为 7。 */
    private int winbackAfterDays = 7;

    /**
     * 日扫 cron（UTC）。默认 {@code 0 0 12 * * *} = 12:00 UTC = <b>19:00 WIB</b>（印尼西部时间）
     * —— 晚间下班后的黄金时段。
     *
     * <p>⚠️ 别照抄 Story 6.7 的 01:00 UTC：那是 08:00 WIB，用户在通勤路上，
     * 「记录 Mochi 的一个瞬间」这种需要停下来拍张照的动作，那个点发等于白发。
     */
    private String cron = "0 0 12 * * *";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDailyCap() {
        return dailyCap;
    }

    public void setDailyCap(int dailyCap) {
        this.dailyCap = dailyCap;
    }

    public int getWinbackAfterDays() {
        return winbackAfterDays;
    }

    public void setWinbackAfterDays(int winbackAfterDays) {
        this.winbackAfterDays = winbackAfterDays;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }
}
