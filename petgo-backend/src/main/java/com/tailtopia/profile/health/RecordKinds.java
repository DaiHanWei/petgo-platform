package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.util.Locale;

/**
 * 记录种类与覆盖组键（V1.5.0 Story 1.2，架构增量 §3.3，PRD 词表 Record kind / Coverage group）。纯函数。
 *
 * <ul>
 *   <li>疫苗：预设；{@code OTHER} 预设、无预设或非猫狗物种 → 规范化名字（与某适用预设标签规范化后相同 → 该预设）；</li>
 *   <li>驱虫：驱虫类型，null → {@code LEGACY}；Custom：规范化名字；Vet visit：每条记录各自一种；其余：类型本身。</li>
 * </ul>
 * 规范化 = trim + 小写 + 空白折叠，<b>只用于匹配，不改存储值</b>。
 */
public final class RecordKinds {

    public static final String LEGACY_DEWORM = "DEWORM:LEGACY";
    private static final String F3_F4_GROUP = "VACCINE:F3F4";

    private RecordKinds() {
    }

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** 疫苗记录的有效预设（含「其他」名字等于预设标签的情形）；无 → null。 */
    public static VaccinePreset effectivePreset(HealthRecordSnapshot r, PetType petType) {
        if (r.type() != HealthRecordType.VACCINE) {
            return null;
        }
        if (petType != PetType.CAT && petType != PetType.DOG) {
            return null;
        }
        if (r.vaccinePreset() != null && r.vaccinePreset() != VaccinePreset.OTHER) {
            return r.vaccinePreset();
        }
        String name = normalize(r.vaccineName());
        for (VaccinePreset p : VaccinePreset.values()) {
            if (p.label() != null && p.appliesTo(petType) && normalize(p.label()).equals(name)) {
                return p;
            }
        }
        return null;
    }

    public static String kindOf(HealthRecordSnapshot r, PetType petType) {
        HealthRecordType type = r.type();
        if (type == HealthRecordType.VACCINE) {
            VaccinePreset p = effectivePreset(r, petType);
            return p != null ? "VACCINE:" + p.name() : "VACCINE~" + normalize(r.vaccineName());
        }
        if (type == HealthRecordType.DEWORM) {
            DewormType d = r.dewormType();
            return d == null ? LEGACY_DEWORM : "DEWORM:" + d.name();
        }
        if (type == HealthRecordType.CUSTOM) {
            return "CUSTOM~" + normalize(r.customName());
        }
        if (type == HealthRecordType.VET_VISIT) {
            return "VET_VISIT#" + r.id();
        }
        return type.name();
    }

    /** 覆盖组键：{@code {F3, F4}} 合一组，其余每个 kind 自成一组（Vet visit 每条一组）。 */
    public static String groupOf(HealthRecordSnapshot r, PetType petType) {
        String kind = kindOf(r, petType);
        if (kind.equals("VACCINE:F3") || kind.equals("VACCINE:F4")) {
            return F3_F4_GROUP;
        }
        return kind;
    }

    public static String groupOfDeworm(DewormType type) {
        return type == null ? LEGACY_DEWORM : "DEWORM:" + type.name();
    }

    public static String groupOfVaccine(VaccinePreset preset) {
        return preset == VaccinePreset.F3 || preset == VaccinePreset.F4 ? F3_F4_GROUP : "VACCINE:" + preset.name();
    }
}
