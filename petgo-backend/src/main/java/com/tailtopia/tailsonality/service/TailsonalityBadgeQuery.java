package com.tailtopia.tailsonality.service;

import com.tailtopia.tailsonality.repository.TailsonalityBadgeRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色小标只读口（V1.3.2 Story 3.3 · AC4）：本人档案与公开主页宠物卡<b>同一个方法</b>。
 *
 * <p>规则：佩戴行存在<b>且</b>所指结果已解锁 → 该结果的 4 字母（{@code type_code}，不含能量后缀）；否则 empty。
 * 🔴 只下发 4 字母，不下发结果 token。只依赖自己的仓库（profile 注入它，避免构造器循环）。
 */
@Service
public class TailsonalityBadgeQuery {

    private final TailsonalityBadgeRepository badges;

    public TailsonalityBadgeQuery(TailsonalityBadgeRepository badges) {
        this.badges = badges;
    }

    @Transactional(readOnly = true)
    public Optional<String> badgeOf(long petProfileId) {
        return badges.findEquippedUnlockedLetters(petProfileId).map(String::trim);
    }
}
