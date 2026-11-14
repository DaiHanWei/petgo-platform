package com.tailtopia.admin.usermgmt.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.usermgmt.dto.AdminUserDetailView;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSpeciesChange;
import com.tailtopia.profile.domain.PetSpeciesChangeSource;
import com.tailtopia.profile.repository.PetSpeciesChangeRepository;
import com.tailtopia.profile.service.PetResolver;
import com.tailtopia.profile.service.PetSpeciesCooldown;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户详情「宠物档案」页签的宠物行（V1.5.0 pet-species Story 2.1 · G1 / G4）：细分类型、下次可改、只读、修改记录。
 *
 * <p>修改记录一次批量查该用户全部宠物、运营昵称一次批量查 {@code admin_accounts}——<b>不 N+1</b>。
 * 用户详情聚合与代改成功后的页签刷新共用这一处。
 */
@Component
public class AdminPetRows {

    private final PetResolver petResolver;
    private final PetSpeciesChangeRepository changes;
    private final AdminAccountRepository admins;

    public AdminPetRows(PetResolver petResolver, PetSpeciesChangeRepository changes, AdminAccountRepository admins) {
        this.petResolver = petResolver;
        this.changes = changes;
        this.admins = admins;
    }

    /** 账号全部宠物（按创建时间）的行。 */
    @Transactional(readOnly = true)
    public List<AdminUserDetailView.PetRow> rowsOf(long userId) {
        return rows(petResolver.listOwned(userId), PetSpeciesCooldown.todayWib());
    }

    List<AdminUserDetailView.PetRow> rows(List<PetProfile> pets, LocalDate todayWib) {
        if (pets.isEmpty()) {
            return List.of();
        }
        List<Long> petIds = pets.stream().map(PetProfile::getId).toList();
        List<PetSpeciesChange> all = changes.findByPetProfileIdInOrderByCreatedAtDescIdDesc(petIds);
        Set<Long> adminIds = new LinkedHashSet<>();
        for (PetSpeciesChange c : all) {
            if (c.getSource() == PetSpeciesChangeSource.ADMIN && c.getOperatorAdminId() != null) {
                adminIds.add(c.getOperatorAdminId());
            }
        }
        Map<Long, String> adminNames = new HashMap<>();
        if (!adminIds.isEmpty()) {
            for (AdminAccount a : admins.findAllById(adminIds)) {
                adminNames.put(a.getId(), a.getDisplayName());
            }
        }
        Map<Long, List<AdminUserDetailView.SpeciesChangeRow>> byPet = new HashMap<>();
        for (PetSpeciesChange c : all) {
            byPet.computeIfAbsent(c.getPetProfileId(), k -> new ArrayList<>()).add(
                    new AdminUserDetailView.SpeciesChangeRow(c.getCreatedAt(), c.getFromSpecies(), c.getToSpecies(),
                            c.getSource().name(),
                            c.getSource() == PetSpeciesChangeSource.ADMIN
                                    ? adminNames.getOrDefault(c.getOperatorAdminId(),
                                            "#" + Objects.toString(c.getOperatorAdminId(), ""))
                                    : null,
                            c.getReason()));
        }
        return pets.stream().map(p -> toRow(p, byPet.getOrDefault(p.getId(), List.of()), todayWib)).toList();
    }

    static AdminUserDetailView.PetRow toRow(PetProfile p, List<AdminUserDetailView.SpeciesChangeRow> changeRows,
            LocalDate todayWib) {
        return new AdminUserDetailView.PetRow(p.getId(), p.getName(),
                p.getPetType() == null ? null : p.getPetType().name(), p.getBreed(),
                // V1.3.0 Story 8.1：性别 / 生日纯读展示（PRD §5 ③ 第 2 条例外），不参与任何判定。
                p.getSex() == null ? null : p.getSex().name(), p.getBirthday(),
                p.getPetSpecies() == null ? null : p.getPetSpecies().name(),
                p.getPetSpecies() == null ? null : p.getPetSpecies().pickerType().name(),
                PetSpeciesCooldown.changeableFromOrNull(p, todayWib),
                p.getPetTypeChangedAt(),
                p.isReadOnly(),
                changeRows);
    }
}
