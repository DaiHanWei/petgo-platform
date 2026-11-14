package com.tailtopia.admin.usermgmt.dto;

import com.tailtopia.consult.service.ConsultHistoryService.SessionMeta;
import com.tailtopia.content.service.ContentService.PostSummary;
import java.time.Instant;
import java.util.List;

/**
 * 后台用户详情聚合（Story 3.1，只读五块）。问诊仅元数据（{@link SessionMeta}，不含对话内容/AI 上下文/媒体）。
 */
public record AdminUserDetailView(
        long id,
        String displayName,
        String nickname,
        String email,
        Instant createdAt,
        boolean deactivated,
        boolean deleted,
        /**
         * 🔴 用户填写的手机号（V1.1.6 Story 11.4 · PII）。
         *
         * <p>🛡 **无 `user.phone_view` 权限时这里恒为 null** —— 服务端就不装它。
         * 只在模板里隐藏是不够的：数据已经到了浏览器，查看源码或抓接口就能拿到。
         *
         * <p>⚠️ **字段名必须叫 `phone`。** 日志脱敏是**按字段名匹配**的
         * （见 `users.phone` 那条迁移的注释）：叫 phoneNumber / mobile / contact
         * 都不会命中脱敏名单，一旦这个对象被打进日志，真实号码就明晃晃落盘了。
         */
        String phone,
        long pawcoinBalance,
        List<PetRow> pets,
        List<PostSummary> posts,
        List<SessionMeta> sessions) {

    /**
     * 宠物档案行（只读摘要）。
     *
     * @param sex      性别（{@code PetSex} 的名字，可空）——V1.3.0 Story 8.1 增列（PRD §5 ③ 第 2 条例外，纯读展示）
     * @param birthday 生日（{@code LocalDate}，可空）——同上。⚠️ 是**日期**不是时刻：
     *                 别拿 {@code @adminTime.wib} 去格式化它，那是给 Instant 用的。
     */
    public record PetRow(long id, String name, String petType, String breed,
            String sex, java.time.LocalDate birthday,
            // ---- V1.5.0 pet-species Story 2.1（G1，只追加）----
            /** 细分类型（PetSpecies 名）。 */
            String species,
            /** 宫格项（pickerType 名）：「其他」组物种为 OTHER，模板据此显示「其他·麝香猫」。 */
            String speciesPicker,
            /** 用户下次可改日期（WIB）；null = 现在可改。 */
            java.time.LocalDate speciesChangeableFrom,
            /** 用户上次修改类型时刻；null = 从未改过。 */
            java.time.Instant petTypeChangedAt,
            /** 只读宠物（status = READ_ONLY）。 */
            boolean readOnly,
            /** 类型修改记录（新的在前；随用户详情一次批量取）。 */
            java.util.List<SpeciesChangeRow> speciesChanges) {

        /** 便捷构造：V1.5.0 前的形态。 */
        public PetRow(long id, String name, String petType, String breed, String sex, java.time.LocalDate birthday) {
            this(id, name, petType, breed, sex, birthday, null, null, null, null, false, java.util.List.of());
        }

        public PetRow {
            speciesChanges = speciesChanges == null ? java.util.List.of() : java.util.List.copyOf(speciesChanges);
        }

        /** 是否「其他」组下的具体物种（模板显示「其他·xx」）。 */
        public boolean otherGroupMember() {
            return com.tailtopia.profile.domain.PetSpecies.parse(species)
                    .map(com.tailtopia.profile.domain.PetSpecies::isOtherGroupMember).orElse(false);
        }
    }

    /**
     * 一条类型修改记录（V1.5.0 pet-species Story 2.1 · G4）。{@code operatorName}：运营 = 后台账号名；用户 / 迁移为 null
     * （模板分别显示「—」/「系统」）。
     */
    public record SpeciesChangeRow(java.time.Instant createdAt, String fromSpecies, String toSpecies, String source,
            String operatorName, String reason) {
    }
}
