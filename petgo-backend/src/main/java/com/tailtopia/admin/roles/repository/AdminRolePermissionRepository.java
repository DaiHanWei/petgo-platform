package com.tailtopia.admin.roles.repository;

import com.tailtopia.admin.roles.domain.AdminRolePermission;
import com.tailtopia.admin.roles.domain.AdminRolePermissionId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** 角色权限码仓库（V1.3.0 Story 1.4）。{@code deleteByRoleId} 仅用于「重置该角色权限再重建」（Story 1.5）。 */
public interface AdminRolePermissionRepository
        extends JpaRepository<AdminRolePermission, AdminRolePermissionId> {

    List<AdminRolePermission> findByRoleId(Long roleId);

    @Transactional
    void deleteByRoleId(Long roleId);
}
