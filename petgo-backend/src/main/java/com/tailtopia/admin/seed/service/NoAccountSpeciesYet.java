package com.tailtopia.admin.seed.service;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link AccountSpeciesDefaultReader} 的当前实现：恒空（V1.1.6 Story 13.3）。
 *
 * <p>🔴 <b>这个空是正确答案，不是占位</b>：「账号物种定位」这个字段由 Story 14-1（AB-3H）
 * 落地，在那之前虚拟账号上确实没有可继承的物种。
 *
 * <p>📌 <b>14-1 的接线点就是这里</b>。参照 13-1 接 12-1 那次的教训：
 * <b>字段一建好就要立刻换掉本实现</b>，否则这个"正确的空"会变成"等着变错的硬编码" ——
 * 表现是运营在虚拟账号上配了猫/狗定位，批量发出去的内容物种却全是空。
 */
@Component
public class NoAccountSpeciesYet implements AccountSpeciesDefaultReader {

    @Override
    public Optional<String> speciesOf(long userId) {
        return Optional.empty();
    }
}
