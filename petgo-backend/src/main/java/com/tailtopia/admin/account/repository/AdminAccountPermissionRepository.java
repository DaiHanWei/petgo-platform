package com.tailtopia.admin.account.repository;

import com.tailtopia.admin.account.domain.AdminAccountPermission;
import com.tailtopia.admin.account.domain.AdminAccountPermissionId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAFF 模块权限仓库（Story 1.5）。注意：{@code deleteByAccountId} 仅用于「重置该账号权限再重建」
 * （权限表非审计表，可删），与「账号硬删」无关——账号永不删除。
 */
public interface AdminAccountPermissionRepository
        extends JpaRepository<AdminAccountPermission, AdminAccountPermissionId> {

    /** 账号的全部权限码行（authorities 装载 + UI 回显勾选）。 */
    List<AdminAccountPermission> findByAccountId(Long accountId);

    /** 重置账号权限（改权限 = 先删后批量 saveAll）。 */
    @Transactional
    void deleteByAccountId(Long accountId);
}
