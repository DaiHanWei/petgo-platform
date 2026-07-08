package com.tailtopia.admin.moderation.read;

import java.util.Map;

/**
 * 账号累计违规计数<b>只读端口</b>（内容审核补充规范 §5.4，story 8 引入、story 9 提供实现）。
 *
 * <p><b>执行顺序倒置化解（R1，CROSS-STORY CM8）</b>：overview 编号 story 8 在 story 9 之前，但违规计数展示
 * 依赖 story 9 的 {@code violation_counts} 数据。经此端口解耦——story 8 独立可交付（未接入时空实现全 0 / 展示「—」），
 * story 9 合并后以 {@code violation_counts} 支撑的实现自动接入（{@code @ConditionalOnMissingBean} 让占位退场）。
 *
 * <p><b>只读、不触发任何自动限制</b>（§5.4：本版本仅记录，账号级处置留待下一版本）。
 */
public interface ViolationCountReader {

    /**
     * 该账号各类型累计违规次数快照。未接入（story 9 未合并）时返回空 Map。
     *
     * @param accountRef 账号内部标识（{@code users.id}）；仅后台受控读路径使用，不外露
     * @return 类型 → 次数；缺省类型视作 0
     */
    Map<ViolationType, Integer> countsFor(long accountRef);
}
