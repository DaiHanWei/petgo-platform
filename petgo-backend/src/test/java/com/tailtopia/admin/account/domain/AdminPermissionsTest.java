package com.tailtopia.admin.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * L0（Story 9.1 · AB-1.1-29）：后台权限码全集完整性 + 每码有双语 {@code perm.*} 标签。
 * 纯常量 + 类路径资源读取，无 Spring / DB。
 */
class AdminPermissionsTest {

    @Test
    void allContainsV11NewCodesWithoutDuplicates() {
        assertThat(AdminPermissions.ALL).contains(
                AdminPermissions.ORDER_VIEW,
                AdminPermissions.ORDER_EXPORT,
                AdminPermissions.VIRTUAL_ACCOUNT_MANAGE,
                AdminPermissions.CONFIG_VIEW,
                AdminPermissions.CONFIG_EDIT);
        // 退款三码在 Epic 4 已存在，仍应在册。
        assertThat(AdminPermissions.ALL).contains(
                AdminPermissions.REFUND_SUBMIT,
                AdminPermissions.REFUND_APPROVE,
                AdminPermissions.REFUND_PAYOUT,
                AdminPermissions.SUPPORT_HANDLE);
        // 无重复码。
        assertThat(new HashSet<>(AdminPermissions.ALL)).hasSameSizeAs(AdminPermissions.ALL);
    }

    @Test
    void isValidRecognizesNewCodesAndRejectsUnknown() {
        assertThat(AdminPermissions.isValid(AdminPermissions.CONFIG_EDIT)).isTrue();
        assertThat(AdminPermissions.isValid(AdminPermissions.ORDER_EXPORT)).isTrue();
        assertThat(AdminPermissions.isValid("config.nuke")).isFalse();
        assertThat(AdminPermissions.isValid("")).isFalse();
        // bug 20260731-440：三个从未有落点的死码已摘除，勾选页不再出现。
        assertThat(AdminPermissions.isValid("content.export")).isFalse();
        assertThat(AdminPermissions.isValid("content.view_reporters")).isFalse();
        assertThat(AdminPermissions.isValid("consult.edit_sessions")).isFalse();
    }

    @Test
    void everyPermissionHasBilingualLabel() throws Exception {
        Properties zh = load("/i18n/messages_zh_CN.properties");
        Properties en = load("/i18n/messages_en.properties");
        for (String code : AdminPermissions.ALL) {
            String key = "perm." + code;
            assertThat(zh.getProperty(key)).as("zh 缺权限标签 " + key).isNotBlank();
            assertThat(en.getProperty(key)).as("en 缺权限标签 " + key).isNotBlank();
        }
    }

    private Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("缺少资源 " + path).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void listStableSize() {
        // 23 既有 + 7（9.1）+ 2（9.5）+ 2（9.6 payment/risk）+ 10（后续批：审核/评论/名称头像等）
        // = 44 + 1（content.manual_review，stag 拣回）= 45
        // − 3（bug 20260731-440 摘除无落点死码 content.export/content.view_reporters/consult.edit_sessions）= 42
        // + 1（bug 20260728-389 后台赠送 PawCoin user.grant_pawcoin）= 43
        // + 1（V1.1.4 Story 3.1 统一工单队列 content.view_tickets）= 44
        // + 1（V1.1.4 Story 3.2 工单处置 content.dispose_account）= 45
        // + 2（V1.1.6 Story 11.1 顶置管理 content.pin_view / content.pin_manage）= 47
        // + 2（V1.1.6 Story 11.2 装饰标签 content.tag_view / content.tag_manage）= 49
        // + 2（V1.1.6 Story 11.3 用户标签 user.tag_view / user.tag_manage）= 51
        // + 2（V1.1.6 Story 11.4 手机号 user.phone_view / user.phone_export）= 53
        // + 1（V1.1.6 Story 12.1 运营发布身份池 seed.publish_as_real）= 54
        // + 2（V1.1.6 Story 15.1 互动积分 content.stats_view / content.stats_export）= 56
        // + 2（V1.1.6 Story 17.2 限流处置 content.throttle_view / content.throttle_manage）= 58
        // + 2（V1.1.6 Story 18.3 分享奖励 config.share_reward_view / config.share_reward_edit）= 60
        //     🔴 与 config.view/config.edit 分开不是洁癖而是**可用性**：
        //     总开关的意义是「发现被刷要能立刻全线关掉」，而 config.edit 那道门
        //     管着兽医单价与分成比例，只有极少数人过得去 —— 塞在那后面，"立刻"就做不到。
        //     🛡 处置码归**编辑组**，且刻意**不**额外要 user.deactivate（封号那一档才要）——
        //     限流可逆、用户不可感知，抬到与停用账号同级会让这一档又变得没人敢用。
        //     ⚠️ 两个都归**查看组** —— 导出不改任何数据，它是"看得更狠的一种看"（同 11.4）。
        //     🔴 归**编辑组**，且与 virtual_account.manage **完全解耦** ——
        //     能管虚拟账号 ≠ 能以真人身份发言（以真实账号误发不可撤回：已推送给他的粉丝）。
        //     ⚠️ 两个都归**查看组** —— 导出不改任何数据，它是"看得更狠的一种看"。 
        //
        // ⚠️ 这条守的是「新增权限码是件需要被看见的事」：权限码一旦落地即冻结（改名会切断
        //    已授予关系），所以每加一个都应当在这里留一行账，而不是让数字悄悄变大。
        List<String> all = AdminPermissions.ALL;
        assertThat(all).hasSize(60);
    }
}
