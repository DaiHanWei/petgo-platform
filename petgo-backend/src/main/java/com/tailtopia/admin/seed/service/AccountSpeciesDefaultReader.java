package com.tailtopia.admin.seed.service;

import java.util.Optional;

/**
 * 「账号物种定位」的读取口（V1.1.6 Story 13.3 · AC5 的继承规则 ①）。
 *
 * <h2>🔴 为什么是一个接口</h2>
 * <b>「账号物种定位」这个字段本身由 Story 14-1（AB-3H）落地</b>，而 14-1 按 Epic 13 的
 * 依赖顺序**必须排在 13-3 之后**（它的两个触点挂在 12-2 的选择器与 13-3 的 Excel 模板上）。
 *
 * <p>所以本 story 交付的是**继承规则本身**（填了就用填的、留空就按账号类型解析），
 * 数据源留一个口子。14-1 换掉实现即可，<b>继承规则与它的测试都不必改</b>。
 *
 * <p>⚠️ 当前实现 {@link NoAccountSpeciesYet} 恒返回空 —— 这个空**是正确的**：
 * 那个字段确实还不存在。
 * 🔴 但 13-1 的教训在这里同样适用：<b>14-1 一旦建了那个列，这个空就从"正确答案"
 * 变成"等着变错的硬编码"</b>。14-1 的任务清单里必须包含"替换本实现"。
 */
public interface AccountSpeciesDefaultReader {

    /**
     * 该账号配置的物种定位。
     *
     * @return 空 = 没配 / 该账号不是虚拟账号 / 该能力还没交付
     */
    Optional<String> speciesOf(long userId);
}
