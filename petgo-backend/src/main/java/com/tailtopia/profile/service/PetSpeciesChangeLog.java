package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.domain.PetSpeciesChange;
import com.tailtopia.profile.domain.PetSpeciesChangeSource;
import com.tailtopia.profile.repository.PetSpeciesChangeRepository;
import org.springframework.stereotype.Component;

/**
 * 宠物类型修改记录的<b>唯一写入口</b>（V1.5.0 pet-species Story 1.2 · D-5）。用户改、运营代改两处调用；
 * FR-208 迁移脚本是 SQL 直写，字段口径同此处。
 */
@Component
public class PetSpeciesChangeLog {

    private final PetSpeciesChangeRepository changes;

    public PetSpeciesChangeLog(PetSpeciesChangeRepository changes) {
        this.changes = changes;
    }

    /**
     * 记一条修改。{@code type_changed} / {@code group_changed} 在这里算。
     *
     * @throws IllegalArgumentException {@code source = ADMIN} 却缺操作人或原因（编程错误）
     */
    public PetSpeciesChange append(long petId, PetSpecies from, PetSpecies to, PetSpeciesChangeSource source,
            Long operatorAdminId, String reason) {
        if (from == null || to == null || source == null) {
            throw new IllegalArgumentException("from / to / source 不能为空");
        }
        if (source == PetSpeciesChangeSource.ADMIN
                && (operatorAdminId == null || reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("运营代改须带操作人与原因");
        }
        boolean typeChanged = from.pickerType() != to.pickerType();
        boolean groupChanged = from.group() != to.group();
        return changes.save(PetSpeciesChange.of(petId, from.name(), to.name(), typeChanged, groupChanged, source,
                operatorAdminId, reason == null ? null : reason.trim()));
    }
}
