package com.tailtopia.admin.roles.dto;

import com.tailtopia.admin.shared.AdminPageCatalog;
import java.util.List;
import java.util.Set;

/**
 * 角色权限矩阵（Story 1.5，整页）：行 = 页面（按 8 个导航组分组），列 = 查看 / 编辑 / 其他操作；
 * {@code checked} 为当前勾选集合。数据源 {@link AdminPageCatalog}。
 */
public record PermissionMatrixView(List<GroupRows> groups, Set<String> checked) {

    public record GroupRows(AdminPageCatalog.Group group, List<AdminPageCatalog.Page> pages) {
    }

    public boolean isChecked(String code) {
        return checked.contains(code);
    }
}
