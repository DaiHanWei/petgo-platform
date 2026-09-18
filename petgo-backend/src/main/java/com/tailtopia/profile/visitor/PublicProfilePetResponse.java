package com.tailtopia.profile.visitor;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.media.AliyunOssClient;
import java.time.LocalDate;

/**
 * 他人公开主页上的**宠物卡片**（V1.3.0 batch-b1 Story 2.3 · FR-118.2 · AC3）。
 *
 * <h2>🛡 白名单，且**没有 cardToken**（AC2 / AD-4 Rule 3）</h2>
 * B1-D1 否掉的方案正是「由主页下发对方宠物的分享链接码」—— 那等于把一条
 * <b>永久公开、可转发到站外</b>的链接发给每个站内访客。
 * <b>站内可见 ≠ 可对外分发。</b>这里物理上就没有那个字段，不是"记得别填"。
 *
 * <p>⚠️ 同样没有 {@code intro}（自述）与 {@code sex} —— 那些在**点进去之后**的
 * 访客视图里给（{@link VisitorProfileResponse}）。一张列表卡片不需要它们。
 *
 * <p>⚠️ <b>没有 Tailsonality 角色小标</b>：FR-117 在批次 B2，本批次这个位置是
 * <b>天然空状态</b>，**不做占位设计**（AC3 原文）。
 *
 * @param petId      宠物 id —— 客户端拿它调站内访客接口（AD-4 Rule 1 明写按 petId）
 * @param name       宠物名
 * @param avatarUrl  头像，<b>已去 EXIF</b>（对外分发的图一律经服务端脱敏）
 * @param petType    物种（客户端本地化成「Kucing」/「Anjing」）
 * @param birthday   生日，可空 —— 供客户端算「2th 3bln」；服务端不下发算好的字符串，
 *                   那样每过一天就得靠缓存失效才准
 * @param diaryCount 该宠物的 Diary 条数，与点进去之后统计条上那个数**同一个实现**
 */
public record PublicProfilePetResponse(
        long petId,
        String name,
        String avatarUrl,
        PetType petType,
        LocalDate birthday,
        long diaryCount) {

    static PublicProfilePetResponse of(PetProfile p, long diaryCount) {
        return new PublicProfilePetResponse(
                p.getId(),
                p.getName(),
                p.getAvatarUrl() == null ? null
                        : AliyunOssClient.exifStrippedDeliveryUrl(p.getAvatarUrl()),
                p.getPetType(),
                p.getBirthday(),
                diaryCount);
    }
}
