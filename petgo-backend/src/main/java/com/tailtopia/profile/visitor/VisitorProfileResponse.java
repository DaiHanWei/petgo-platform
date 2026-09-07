package com.tailtopia.profile.visitor;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.media.AliyunOssClient;
import java.time.LocalDate;

/**
 * 访客看到的宠物档案（V1.1.6 Story 2.3）。
 *
 * <h2>🛡 白名单，不是「作者档案去掉几个字段」</h2>
 * 这里<b>只列访客该看的</b>：名字 · 头像 · 物种 · 品种 · 性别 · 生日 · 自述 · 主人昵称。
 *
 * <p>作者态的档案对象还带着 {@code ownerId} · 内部自增 {@code id} · {@code cardToken} ·
 * {@code ogImageUrl} · {@code serialId} 等 —— 这些<b>物理上不在这个 record 里</b>：
 * {@code ownerId} 与自增 id 是可枚举的内部标识（架构护栏：对外标识一律用不可枚举 token），
 * 而 {@code cardToken} 更不该回显（访客本来就拿着它，回显只是徒增泄漏面）。
 *
 * <p>⚠️ 加字段前先问一句：<b>陌生人有必要知道这个吗？</b>
 *
 * @param name 宠物名
 * @param avatarUrl 头像，<b>已去 EXIF</b>
 * @param petType 物种
 * @param breed 品种，可空
 * @param sex 性别，可空（V1.1.6 Story 1.1 起有此字段，存量为空不回填）
 * @param birthday 生日，可空 —— 供客户端算年龄展示
 * @param intro 自述，可空
 * @param ownerNickname 主人昵称，供顶部「由 {昵称} 分享」横幅用；查不到为 null
 */
public record VisitorProfileResponse(
        String name,
        String avatarUrl,
        PetType petType,
        String breed,
        PetSex sex,
        LocalDate birthday,
        String intro,
        String ownerNickname) {

    static VisitorProfileResponse of(PetProfile p, String ownerNickname) {
        return new VisitorProfileResponse(
                p.getName(),
                p.getAvatarUrl() == null ? null : AliyunOssClient.exifStrippedDeliveryUrl(p.getAvatarUrl()),
                p.getPetType(),
                p.getBreed(),
                p.getSex(),
                p.getBirthday(),
                p.getIntro(),
                ownerNickname);
    }
}
