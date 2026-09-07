package com.tailtopia.admin.moderation.dto;

import com.tailtopia.content.dto.AdminContentRow;
import com.tailtopia.content.species.ResolvedSpecies;
import com.tailtopia.content.species.SpeciesSource;

/**
 * 内容列表上带物种信息的一行（V1.1.6 Story 14.1 · AC5）。
 *
 * @param editable 运营能不能改这一行的物种覆写。
 *                 🛡 <b>真实用户内容只读</b> —— 其物种由作者宠物档案决定，运营不应手工干预。
 *                 ⚠️ 判据是"作者是虚拟账号 **或** 在运营发布身份池内"：
 *                 后者（IP 号）是<b>公司资产账号</b>，它自己在 App 里发的内容同样属于运营范畴；
 *                 而普通用户的内容一律只读。
 */
public record ContentSpeciesRow(AdminContentRow content, ResolvedSpecies species,
        boolean editable) {

    public String speciesLabel() {
        return species.known() ? species.species() : "—";
    }

    public SpeciesSource source() {
        return species.source();
    }
}
