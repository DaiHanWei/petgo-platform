package com.tailtopia.profile.dto;

import com.tailtopia.profile.domain.IdCard;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 身份证卡快照响应（Story 6-7）：单卡渲染 + 解锁态 + 建卡时刻。历史列表与单卡详情共用。
 * 不含 owner PII/健康数据；serialId 仅展示编号。
 */
public record IdCardResponse(
        long id,
        Long serialId,
        String cardType,
        String name,
        String petType,
        String breed,
        LocalDate birthday,
        String avatarUrl,
        String intro,
        boolean hdUnlocked,
        Instant createdAt) {

    public static IdCardResponse from(IdCard c) {
        return new IdCardResponse(c.getId(), c.getSerialId(), c.getCardType().name(), c.getName(),
                c.getPetType(), c.getBreed(), c.getBirthday(), c.getAvatarUrl(), c.getIntro(),
                c.isHdUnlocked(), c.getCreatedAt());
    }
}
