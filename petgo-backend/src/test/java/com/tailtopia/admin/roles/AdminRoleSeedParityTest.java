package com.tailtopia.admin.roles;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.account.domain.AdminRole;
import com.tailtopia.admin.roles.domain.AdminRoleEntity;
import com.tailtopia.admin.roles.domain.AdminRolePermission;
import com.tailtopia.admin.roles.domain.RoleType;
import com.tailtopia.admin.roles.repository.AdminRolePermissionRepository;
import com.tailtopia.admin.roles.repository.AdminRoleRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（真库）：Story 1.4 的「合同」——seed 迁移 V20260909_1141 灌入的四个 SYSTEM 角色码集合
 * 与迁移前枚举快照 {@link AdminRoleSeedSnapshot} 逐位相等；新码 place.manage / comment.virtual_post 不在任何 SYSTEM 角色下。
 */
class AdminRoleSeedParityTest extends ApiIntegrationTest {

    @Autowired
    private AdminRoleRepository roles;
    @Autowired
    private AdminRolePermissionRepository rolePermissions;

    @Test
    void seededSystemRolesMatchPreMigrationSnapshot() {
        for (var e : AdminRoleSeedSnapshot.MIGRATED.entrySet()) {
            AdminRole role = e.getKey();
            AdminRoleEntity row = roles.findByCode(role.name()).orElseThrow(
                    () -> new AssertionError("admin_roles 缺 SYSTEM 行 " + role));
            assertThat(row.getRoleType()).isEqualTo(RoleType.SYSTEM);
            assertThat(row.getNameKey()).isEqualTo(role.titleCode());
            List<String> inDb = rolePermissions.findByRoleId(row.getId()).stream()
                    .map(AdminRolePermission::getPermissionCode).toList();
            assertThat(inDb).as(role + " 表内码集合 ≠ 迁移前快照")
                    .containsExactlyInAnyOrderElementsOf(e.getValue());
            assertThat(inDb).doesNotContain(AdminPermissions.PLACE_MANAGE, AdminPermissions.COMMENT_VIRTUAL_POST);
            assertThat(inDb).allSatisfy(c -> assertThat(AdminPermissions.isValid(c)).isTrue());
        }
        assertThat(roles.findByCode("OPS_MANAGER")).as("OPS_MANAGER 不迁移（D-13）").isEmpty();
        assertThat(roles.findByCode("SUPER_ADMIN")).isEmpty();
    }
}
