package com.tailtopia.admin.seed.service;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link AccountSpeciesDefaultReader} 的当前实现：恒空（V1.1.6 Story 13.3）。
 *
 * <p>🔴 <b>这个空是正确答案，不是占位</b>：「账号物种定位」这个字段由 Story 14-1（AB-3H）
 * 落地，在那之前虚拟账号上确实没有可继承的物种。
 *
 * <p>✅ <b>V1.1.6 Story 14.1 已接上真实数据</b>（{@link AccountSpeciesFromUsers}，标了
 * {@code @Primary}）。本类保留，是因为它的注释就是那条接线关系的记录 ——
 * 留着它比留一段 git 历史更容易被下一个人看到。
 *
 * <p>⚠️ <b>不要删掉 {@code @Primary} 那个实现</b>：删了之后 Spring 会回落到本类，
 * 于是运营在虚拟账号上配的猫/狗定位会**静默失效**（批量发出去的内容物种全是空），
 * 而这件事不报错、不崩、只是数据慢慢变得没用。
 */
@Component
public class NoAccountSpeciesYet implements AccountSpeciesDefaultReader {

    @Override
    public Optional<String> speciesOf(long userId) {
        return Optional.empty();
    }
}
