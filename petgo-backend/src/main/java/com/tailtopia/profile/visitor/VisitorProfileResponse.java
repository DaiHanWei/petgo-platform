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
 * @param iFollow 我是否追更这只宠物（V1.4.2 social-topic Story 1.1 · D-8）。<b>只有站内入口填</b>；
 *                分享 token 公开路径为 null → 全局 NON_NULL 不下发（公开路径 key 集合不变）
 * @param iFollowOwner 我是否关注这只宠物的主人（同上，R-2）
 * @param ownerToken 主人公开 token（同上，供埋点 {@code owner_id}，R-2）。
 *                ⚠️ 分享链接可外转，公开路径<b>不</b>多吐主人 token
 */
public record VisitorProfileResponse(
        String name,
        String avatarUrl,
        PetType petType,
        String breed,
        PetSex sex,
        LocalDate birthday,
        String intro,
        String ownerNickname,
        Boolean iFollow,
        Boolean iFollowOwner,
        String ownerToken,
        /** 细分类型（V1.5.0 pet-species · D-10，PetSpecies 名）；{@code petType} 仍是大类三值。 */
        String species) {

    /** 便捷构造：V1.5.0 前的形态（不带细分类型，species = null，App 按 petType 兜底）。 */
    public VisitorProfileResponse(String name, String avatarUrl, PetType petType, String breed, PetSex sex,
            LocalDate birthday, String intro, String ownerNickname, Boolean iFollow, Boolean iFollowOwner,
            String ownerToken) {
        this(name, avatarUrl, petType, breed, sex, birthday, intro, ownerNickname, iFollow, iFollowOwner, ownerToken,
                null);
    }

    /** 分享 token 公开路径：不填追更 / 主人 token 三个字段（null → NON_NULL 省略）。 */
    static VisitorProfileResponse of(PetProfile p, String ownerNickname) {
        return of(p, ownerNickname, null, null, null);
    }

    /** 站内入口（V1.4.2 social-topic Story 1.1）：带 {@code iFollow} / {@code iFollowOwner} / {@code ownerToken}。 */
    static VisitorProfileResponse of(PetProfile p, String ownerNickname, Boolean iFollow, Boolean iFollowOwner,
            String ownerToken) {
        return new VisitorProfileResponse(
                p.getName(),
                p.getAvatarUrl() == null ? null : AliyunOssClient.exifStrippedDeliveryUrl(p.getAvatarUrl()),
                p.getPetType(),
                p.getBreed(),
                p.getSex(),
                p.getBirthday(),
                p.getIntro(),
                ownerNickname,
                iFollow,
                iFollowOwner,
                ownerToken,
                p.getPetSpecies() == null ? null : p.getPetSpecies().name());
    }
}
