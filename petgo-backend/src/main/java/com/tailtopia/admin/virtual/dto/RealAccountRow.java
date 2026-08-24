package com.tailtopia.admin.virtual.dto;

import java.time.Instant;

/**
 * 运营发布身份池里的一个真实账号（V1.1.6 Story 12.1 · AC7）。
 *
 * @param publishedByAdminCount 经**后台代发**的内容数。🛡 该账号在 App 内自主发布的**不计入**
 *                              （AC7/AC8）—— 运营看这个数是为了核对"我们发了多少"。
 * @param deletedCount          其中已被持有人在 App 内删除的条数（AC8）。
 *                              后台不阻止他删，但记录必须反映出来，不可继续显示为在线。
 * @param speciesGuess          物种推导结果，只读自查列。⚠️ 运营真实账号**没有**「账号物种定位」
 *                              字段（那是虚拟账号补偿"无宠物档案"的专属字段），推不出来是常态，
 *                              本列的用途就是让运营提前知道哪些号属于这种情况。
 * @param speciesSource         推导来源（"宠物档案" / 推不出来的原因）。
 */
public record RealAccountRow(
        long userId,
        String nickname,
        String avatarUrl,
        String authorizationNote,
        Instant grantedAt,
        long grantedBy,
        boolean enabled,
        boolean deactivated,
        long publishedByAdminCount,
        long deletedCount,
        String speciesGuess,
        String speciesSource) {
}
