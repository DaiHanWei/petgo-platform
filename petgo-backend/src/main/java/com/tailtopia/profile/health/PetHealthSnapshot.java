package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.LocalDate;

/**
 * 宠物健康相关字段快照（V1.5.0 Story 1.2）。{@code pregnantNursing} 已按业务口径（sex=MALE 恒 false）。
 * 物种只认 CAT / DOG，其他一律按 OTHER 规则（{@link #isCatOrDog()}）。
 */
public record PetHealthSnapshot(long petId, long ownerId, PetType petType, PetSex sex, LocalDate birthday,
        NeuterStatus neuterStatus, Boolean catInHome, boolean pregnantNursing, LocalDate createdDate,
        PetStatus status) {

    public static PetHealthSnapshot of(PetProfile p) {
        return new PetHealthSnapshot(p.getId() == null ? 0L : p.getId(), p.getOwnerId() == null ? 0L : p.getOwnerId(),
                p.getPetType(), p.getSex(), p.getBirthday(), p.getNeuterStatus(), p.getCatInHome(),
                p.isPregnantNursingEffective(),
                p.getCreatedAt() == null ? null : p.getCreatedAt().atZone(ScheduleWindow.WIB).toLocalDate(),
                p.getStatus());
    }

    public boolean isCat() {
        return petType == PetType.CAT;
    }

    public boolean isDog() {
        return petType == PetType.DOG;
    }

    public boolean isCatOrDog() {
        return isCat() || isDog();
    }

    /** 绝育：只有 NEUTERED 算；null（没填）与 UNKNOWN（不确定）都按未绝育（架构 Z-9）。 */
    public boolean isNeutered() {
        return neuterStatus == NeuterStatus.NEUTERED;
    }

    /** 母狗且未绝育：发情 Next date / D-7 / 子宫蓄脓观察的前提。 */
    public boolean isIntactFemaleDog() {
        return isDog() && sex == PetSex.FEMALE && !isNeutered();
    }

    /** 家里有猫：null = 没填，按「否」。 */
    public boolean hasCatInHome() {
        return Boolean.TRUE.equals(catInHome);
    }
}
