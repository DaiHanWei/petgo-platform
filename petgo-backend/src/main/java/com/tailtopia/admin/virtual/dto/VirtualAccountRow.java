package com.tailtopia.admin.virtual.dto;

import java.time.Instant;

/** 虚拟账号列表行（Story 9.8，A-6）。 */
public record VirtualAccountRow(
        long id,
        String nickname,
        String avatarUrl,
        boolean enabled,
        int publishedCount,
        /**
         * 账号物种定位（V1.1.6 Story 14.1 · AC2）。
         *
         * <p>🔴 <b>这是本 story 杠杆最高的地方</b>：内容物种是**读时 join 推导**，
         * 改完这一项，该号名下<b>全部历史内容</b>的物种归属立即生效、零回填。
         *
         * <p>⚠️ 存量为 NULL 时这里显示的是读时默认值 {@code GENERAL} ——
         * 界面上分不出"配过 GENERAL"与"从没配过"，这是零回填换来的代价，可接受：
         * 两者对算法的效果完全一样。
         */
        String accountSpecies,
        Long createdBy,
        Instant createdAt) {
}
