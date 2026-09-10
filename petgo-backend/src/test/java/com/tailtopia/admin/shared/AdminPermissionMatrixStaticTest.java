package com.tailtopia.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.account.domain.AdminPermissions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * L0：权限矩阵的**结构**收口（V1.3.0 Story 11.4 · AC2 / AC5）。
 *
 * <p>AC5 的原话是「账号页权限面板与角色配置页矩阵使用同一 {@code PermissionMatrixBuilder}，
 * 分组顺序 / 页面维度 / 码归属完全一致」，验收方式写的是「比对两处 DOM 的分组标题序列」。
 * 但本项目里两处**本来就 `th:replace` 同一个 fragment**（`perm-matrix :: matrix`），
 * 数据同样来自 {@link AdminPageCatalog#byGroup()} —— 也就是说「一致」不是靠比对得来的，
 * 而是结构上就无法不一致。
 *
 * <p>那么真正值得钉的就不是「两处 DOM 是否相同」（恒真，比了也是白比），而是
 * <b>「有没有人把它 fork 掉」</b>：某天为了给账号面板加一列，复制一份 fragment 改改 ——
 * 从那一刻起两处开始各自演化，而任何比对 DOM 的测试**在 fork 当天仍然是绿的**（内容还一样），
 * 等到分叉显现已是几个 story 之后。所以这里钉的是引用关系本身。
 *
 * <p>纯文件扫描，无 Spring / 无 DB。
 */
class AdminPermissionMatrixStaticTest {

    private static final Path TPL = Path.of("src", "main", "resources", "templates", "admin");
    private static final Path MATRIX = TPL.resolve("fragments").resolve("perm-matrix.html");
    private static final List<Path> CONSUMERS = List.of(
            TPL.resolve("roles-edit.html"),
            TPL.resolve("fragments").resolve("drawer-admin-account.html"));

    @Test
    void bothPermissionPanelsRenderTheSameMatrixFragment() throws IOException {
        assertThat(Files.exists(MATRIX)).as("矩阵 fragment 不见了").isTrue();
        for (Path p : CONSUMERS) {
            String html = Files.readString(p, StandardCharsets.UTF_8);
            assertThat(html)
                    .as(p.getFileName() + " 没有引用共用的 perm-matrix fragment —— "
                            + "两处矩阵一旦各写各的，分叉当天没有任何测试会红")
                    .contains("admin/fragments/perm-matrix :: matrix");
        }
    }

    /**
     * 矩阵模板里不许出现**手写的权限码字符串**（AC2）。
     *
     * <p>码只该从 {@link AdminPageCatalog}（→ {@link AdminPermissions} 常量）流进来。
     * 模板里手写一个，改码时 Java 那边全改完了、模板这一处还留着旧的 ——
     * 编译不报错，渲染不报错，只是那一格复选框从此对不上任何权限。
     */
    @Test
    void theMatrixTemplateNeverHardcodesAPermissionCode() throws IOException {
        String html = Files.readString(MATRIX, StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("['\"]([a-z][a-z_]*\\.[a-z][a-z_]*)['\"]").matcher(html);
        while (m.find()) {
            if (AdminPermissions.isValid(m.group(1))) {
                found.add(m.group(1));
            }
        }
        assertThat(found)
                .as("矩阵模板里手写了权限码 —— 码应当只从 AdminPageCatalog 流进来")
                .isEmpty();
    }

    /**
     * 已撤销的码不许再出现在目录 / 矩阵里（AC2 第二句）。
     *
     * <p>`content.stats_view` / `content.stats_export` 随「内容数据」整页于 2026-08-28 撤销。
     * 存量账号里的残留字符串不清（清了要动数据，且没有收益），但**矩阵不能再把它们发出去** ——
     * 发出去就意味着有人能勾一个什么也打不开的框。
     */
    @Test
    void revokedCodesAreGoneFromTheCatalogAndTheMatrix() throws IOException {
        String catalog = Files.readString(
                Path.of("src", "main", "java", "com", "tailtopia", "admin", "shared",
                        "AdminPageCatalog.java"),
                StandardCharsets.UTF_8);
        for (String revoked : List.of("content.stats_view", "content.stats_export")) {
            assertThat(AdminPermissions.isValid(revoked))
                    .as(revoked + " 又回到 AdminPermissions 了（整页 2026-08-28 撤销）").isFalse();
            assertThat(catalog)
                    .as("AdminPageCatalog 里仍有已撤销的 " + revoked)
                    .doesNotContain("\"" + revoked + "\"");
        }
    }

    /** 权限册规模：基线 72 + 本版 `place.manage`、`comment.virtual_post` = 74（AC2）。 */
    @Test
    void thePermissionRegisterHasExactlySeventyFourCodes() {
        assertThat(AdminPermissions.ALL).hasSize(74);
        assertThat(AdminPermissions.ALL)
                .contains(AdminPermissions.PLACE_MANAGE, AdminPermissions.COMMENT_VIRTUAL_POST);
    }
}
