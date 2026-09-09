package com.tailtopia.admin.shared.web;

import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.StagFlag;
import com.tailtopia.admin.shared.nav.AdminNavModel;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 后台全站模型注入（V1.3.0 Story 2.2）——每个 admin 控制器渲染前塞入：
 * <ul>
 *   <li>{@code navGroups}：按 {@code AdminPageCatalog} 算出的可见组 / 项（{@link AdminNavModel}）；</li>
 *   <li>{@code stag}：staging 标记（{@link StagFlag} 仅 stag profile 存在）；</li>
 *   <li>{@code topbarName / topbarEmail / topbarRoleKey}：顶栏账号菜单，取<b>登录时的 principal</b>（D-2：改名 / 改角色
 *       后对方重新登录才更新，顶栏不查库）。principal 不是 {@code AdminUserDetails}（如 @WithMockUser）时为 null。</li>
 * </ul>
 * 只覆盖 {@code com.tailtopia.admin} 包下的控制器；api 链不受影响。
 */
@ControllerAdvice(basePackages = "com.tailtopia.admin")
public class GlobalModelAdvice {

    private final boolean stag;

    public GlobalModelAdvice(Optional<StagFlag> stagFlag) {
        this.stag = stagFlag.isPresent();
    }

    @ModelAttribute
    public void inject(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> authorities = auth == null ? Set.of()
                : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        model.addAttribute("navGroups", AdminNavModel.build(authorities));
        model.addAttribute("stag", stag);
        String name = null;
        String email = null;
        String roleKey = null;
        if (auth != null && auth.getPrincipal() instanceof AdminUserDetails admin) {
            email = admin.getUsername();
            // 显示名为空 / 空白（历史数据）时退回邮箱，避免模板 substring(0,1) 抛异常把全站渲染成 500。
            name = admin.getDisplayName() != null && !admin.getDisplayName().isBlank()
                    ? admin.getDisplayName().trim() : email;
            String code = admin.getRoleCode();
            if (code == null) {
                code = admin.getAccountType() == AdminAccountType.SUPER_ADMIN ? "SUPER_ADMIN" : "CUSTOM";
            }
            roleKey = "role." + code;
        }
        model.addAttribute("topbarName", name);
        model.addAttribute("topbarEmail", email);
        model.addAttribute("topbarRoleKey", roleKey);
        // 待办中心组是否可见（角标 hx-get 只在可见时挂）。
        model.addAttribute("inboxVisible", ((List<AdminNavModel.NavGroup>) model.getAttribute("navGroups"))
                .stream().anyMatch(AdminNavModel.NavGroup::isInbox));
    }
}
