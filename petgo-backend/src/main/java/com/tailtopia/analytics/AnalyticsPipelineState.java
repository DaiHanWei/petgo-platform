package com.tailtopia.analytics;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 埋点管道启停状态（analytics-pipeline Story 1.1，架构「启动隔离」）。
 *
 * <p>埋点库连不上 / 埋点 Flyway 失败 / 事件清单无效时由启动路径调用 {@link #disable}；后续 story
 * （接收端返回 503、定时任务跳过）<b>只读</b>这个状态，不自行判断库是否可用。
 *
 * <p>线程安全：状态是一个不可变快照，经 {@link AtomicReference} 发布。停用后不会自动恢复（重启进程才会重新判定）；
 * 多次 {@code disable} 只保留<b>第一次</b>的原因 —— 第一个原因才是根因（库不在时迁移必然也失败）。
 */
@Component
public class AnalyticsPipelineState {

    /** 停用原因。 */
    public enum Reason {
        /** 埋点库取不到连接（库不存在、账号不对、实例不可达）。 */
        DB_UNAVAILABLE,
        /** 埋点库第二套 Flyway 迁移失败。 */
        MIGRATION_FAILED,
        /** 事件清单 / 口径 yaml 解析或校验失败（Story 1.2 使用）。库本身是好的。 */
        CATALOG_INVALID
    }

    /** 停用快照：原因 + 说明（说明里不得有连接串、账号、密码、用户数据）。 */
    public record Disabled(Reason reason, String detail) {
    }

    private final AtomicReference<Disabled> disabled = new AtomicReference<>();

    /** 管道是否可用。 */
    public boolean isEnabled() {
        return disabled.get() == null;
    }

    /**
     * 停用管道。
     *
     * @return {@code true} = 本次调用把管道从启用切到停用；{@code false} = 之前已停用（原因不覆盖）
     */
    public boolean disable(Reason reason, String detail) {
        if (reason == null) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        return disabled.compareAndSet(null, new Disabled(reason, detail));
    }

    /** 停用原因；启用时为空。 */
    public Optional<Reason> reason() {
        Disabled d = disabled.get();
        return d == null ? Optional.empty() : Optional.of(d.reason());
    }

    /** 停用说明；启用时为空。 */
    public Optional<String> detail() {
        Disabled d = disabled.get();
        return d == null ? Optional.empty() : Optional.ofNullable(d.detail());
    }
}
