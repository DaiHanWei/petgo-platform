package com.tailtopia.admin.seed.service;

import com.tailtopia.content.species.ContentSpeciesResolver;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * {@link AccountSpeciesDefaultReader} 的真实实现（V1.1.6 Story 14.1 接上）。
 *
 * <p>📌 <b>这就是 Story 13.3 里写明的那个接线点。</b>
 * 13-3 交付「关联物种」的继承规则时，「账号物种定位」这个字段还不存在，
 * 所以当时的实现 {@code NoAccountSpeciesYet} 恒返回空 —— 而那个空当时是**正确答案**。
 *
 * <p>🔴 13-3 的注释里写着这句话，现在照做了：
 * 「<b>字段一建好就要立刻换掉本实现</b>，否则这个"正确的空"会变成"等着变错的硬编码" ——
 * 表现是运营在虚拟账号上配了猫/狗定位，批量发出去的内容物种却全是空」。
 *
 * <p>⚠️ 用 {@code @Primary} 覆盖而不是删掉旧实现：{@code NoAccountSpeciesYet} 的类注释
 * 本身就是这条接线关系的记录，留着它比留一段 git 历史更容易被下一个人看到。
 */
@Component
@Primary
public class AccountSpeciesFromUsers implements AccountSpeciesDefaultReader {

    private final ContentSpeciesResolver resolver;

    public AccountSpeciesFromUsers(ContentSpeciesResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 🔴 <b>只有虚拟账号能继承</b>（AC3/AC4）：运营真实账号**无此字段**，
     * 留空即由算法按作者宠物档案推导 —— 它有真实档案，猜不如让算法读。
     */
    @Override
    public Optional<String> speciesOf(long userId) {
        return resolver.accountSpeciesOf(userId);
    }
}
