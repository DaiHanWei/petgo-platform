package com.tailtopia.admin.usermgmt.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.admin.shared.web.AdminConfirmModals;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.usermgmt.service.AdminPetRows;
import com.tailtopia.admin.usermgmt.service.AdminPetSpeciesService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.service.PetSpeciesCooldown;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 用户抽屉「宠物档案」页签的运营代改类型（V1.5.0 pet-species Story 2.1 · AB-42 · UI G2 ~ G4）。结构照
 * {@code AdminPetSlotController.grant} / {@code AdminGrowthUserController}：
 * <ul>
 *   <li>{@code …/species/confirm}：服务端确认弹层（→ {@code #confirm-host}），事实行 + 黄色提示条 + 新类型下拉（15 项 +「其他」
 *       分组 12 项）+ 修改原因；失败（404）落抽屉错误槽 {@code #user-drawer-err}；</li>
 *   <li>{@code …/species}：htmx 成功 = 收弹层 + 只换本页签（{@code #user-pets-panel} outerHTML，展开该宠物修改记录）+ toast；
 *       失败（422 / 404 / 409）落弹层内 {@code #confirm-modal-err}，弹层不关；非 htmx：PRG + flash。</li>
 * </ul>
 * 门控 {@code user.pet_type_change}（不预授，待拍板 Q-3）。
 */
@Controller
public class AdminPetSpeciesController {

    /** ⚠️ 表达式须与模板「代改类型」按钮的 {@code sec:authorize} 逐字一致。 */
    static final String PET_TYPE_CHANGE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.USER_PET_TYPE_CHANGE + "')";

    static final String PANEL_TARGET = "#user-pets-panel";

    private final AdminPetSpeciesService service;
    private final AdminPetRows petRows;
    private final Messages msg;

    public AdminPetSpeciesController(AdminPetSpeciesService service, AdminPetRows petRows, Messages msg) {
        this.service = service;
        this.petRows = petRows;
        this.msg = msg;
    }

    @PostMapping("/admin/users/{userId}/pets/{petId}/species/confirm")
    @PreAuthorize(PET_TYPE_CHANGE_AUTH)
    public String confirm(@PathVariable long userId, @PathVariable long petId, HxRequest hx, Model model,
            HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/users?open=" + userId;
        }
        PetProfile pet = service.requireOwnedPet(userId, petId);
        return AdminConfirmModals.render(model, response, modal(userId, pet));
    }

    @PostMapping("/admin/users/{userId}/pets/{petId}/species")
    @PreAuthorize(PET_TYPE_CHANGE_AUTH)
    public String change(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @PathVariable long petId, @RequestParam(value = "species", required = false) String species,
            @RequestParam(value = "reason", required = false) String reason, HxRequest hx, Model model,
            HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            service.change(admin.getAdminAccountId(), userId, petId, species, reason);
            // 在 swap 之后再收弹层：先收的话发请求的表单已不在 DOM 里（同成长值调整）。
            response.setHeader("HX-Trigger-After-Swap", AdminHxEvents.CONFIRM_CLOSE);
            response.setHeader(AdminFragmentResponses.HEADER_RETARGET, PANEL_TARGET);
            response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "outerHTML");
            model.addAttribute("petsTabUserId", userId);
            model.addAttribute("petsTabRows", petRows.rowsOf(userId));
            model.addAttribute("petsTabExpanded", petId);
            model.addAttribute("toast", msg.get("admin.flash.petSpecies.changed"));
            return "admin/fragments/drawer-users-pets :: saved";
        }
        try {
            service.change(admin.getAdminAccountId(), userId, petId, species, reason);
            flash.addFlashAttribute("notice", msg.get("admin.flash.petSpecies.changed"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/users?open=" + userId;
    }

    /** G2：事实行（宠物 / 当前类型 · 品种 / 用户下次可改）→ 黄色提示条 → 新类型下拉（默认当前值）→ 修改原因。 */
    AdminConfirmModal modal(long userId, PetProfile pet) {
        PetSpecies current = pet.getPetSpecies();
        LocalDate next = PetSpeciesCooldown.changeableFromOrNull(pet, PetSpeciesCooldown.todayWib());
        String breed = pet.getBreed() == null || pet.getBreed().isBlank() ? "—" : pet.getBreed();
        List<AdminConfirmModal.Fact> facts = List.of(
                new AdminConfirmModal.Fact(msg.get("admin.petSpecies.modal.pet"),
                        msg.get("admin.petSpecies.modal.petValue", pet.getName(), String.valueOf(pet.getId()))),
                new AdminConfirmModal.Fact(msg.get("admin.petSpecies.modal.current"),
                        msg.get("admin.petSpecies.modal.currentValue", displayName(current), breed)),
                new AdminConfirmModal.Fact(msg.get("admin.petSpecies.modal.nextChange"),
                        next == null ? msg.get("admin.petSpecies.canChangeNow") : next.toString()));
        List<AdminConfirmModal.Option> flat = new ArrayList<>();
        for (PetSpecies s : PetSpeciesLabels.pickerTypes()) {
            flat.add(new AdminConfirmModal.Option(s.name(), msg.get(PetSpeciesLabels.key(s.name())), s == current));
        }
        List<AdminConfirmModal.Option> others = new ArrayList<>();
        for (PetSpecies s : PetSpeciesLabels.otherGroupMembers()) {
            others.add(new AdminConfirmModal.Option(s.name(), msg.get(PetSpeciesLabels.key(s.name())), s == current));
        }
        AdminConfirmModal.SelectField select = new AdminConfirmModal.SelectField("species",
                msg.get("admin.petSpecies.modal.newType"), flat, true,
                List.of(new AdminConfirmModal.OptionGroup(msg.get(PetSpeciesLabels.key(PetSpecies.OTHER.name())),
                        others)));
        AdminConfirmModal.TextAreaField reason = new AdminConfirmModal.TextAreaField("reason",
                msg.get("admin.petSpecies.modal.reason"), msg.get("admin.petSpecies.modal.reasonPlaceholder"),
                AdminPetSpeciesService.REASON_MAX, true);
        return new AdminConfirmModal(msg.get("admin.petSpecies.modal.title"),
                msg.get("admin.petSpecies.modal.banner"), List.of(), null, null, null, null,
                "/admin/users/" + userId + "/pets/" + pet.getId() + "/species", Map.of(),
                msg.get("admin.petSpecies.modal.confirm"), false, true, facts)
                .withSelect(select)
                .withTextArea(reason);
    }

    /** 中文名；「其他」组物种显示「其他·麝香猫」。 */
    private String displayName(PetSpecies s) {
        String name = msg.get(PetSpeciesLabels.key(s.name()));
        return s.isOtherGroupMember() ? msg.get("admin.petSpecies.groupPrefix") + name : name;
    }
}
