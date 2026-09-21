package com.tailtopia.admin.roles.repository;

import com.tailtopia.admin.roles.domain.AdminRoleEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 岗位角色表仓库（V1.3.0 Story 1.4）。 */
public interface AdminRoleRepository extends JpaRepository<AdminRoleEntity, Long> {

    /** 按 code 取（SYSTEM 行 code = 枚举名；CUSTOM 行 code = role-<id>）。 */
    Optional<AdminRoleEntity> findByCode(String code);

    /** 角色配置页列表（Story 1.5）：预置在前、自建按创建序。 */
    List<AdminRoleEntity> findAllByOrderByRoleTypeAscIdAsc();
}
