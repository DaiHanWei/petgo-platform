package com.tailtopia.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.config.service.AdminConfigService;
import com.tailtopia.config.domain.ConfigChangeLog.ConfigType;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.shared.config.SupportContactProvider;
import com.tailtopia.shared.error.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：客服联系方式（Story 3-1 AC1/AC2/AC4/AC5/AC9）。
 *
 * <p>本类看三件事：<b>免鉴权端点真的免鉴权</b>、<b>改配置后多处同批生效</b>、
 * <b>`SUPPORT_CONTACT` 写变更日志不撞 CHECK 约束</b>。
 *
 * <p>🔴 最后一条是本 story 最容易踩的雷：只在 Java 枚举里加值不够，列上有 CHECK 白名单，
 * 不同步放开会在写日志时撞约束 —— 表现是「保存客服配置报 500」而错误栈指向
 * {@code config_change_logs}、不指向配置模块，极易被误判成审计模块坏了。
 */
class SupportContactIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminConfigService adminConfig;
    @Autowired
    private SupportContactProvider contacts;
    @Autowired
    private ConfigChangeLogRepository changeLogs;

    private static final long ACTOR = 1L;

    @Test
    @DisplayName("🔓 不带 Authorization 的 GET 返 200，字段恰好三项")
    void anonymousGetReturnsContact() throws Exception {
        mvc.perform(get("/api/v1/support/contact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.whatsappNumber").exists())
                .andExpect(jsonPath("$.whatsappE164").exists())
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    @DisplayName("种子值就是现网号码（迁移灌进去的，行为零变化）")
    void seedIsTheCurrentNumber() throws Exception {
        mvc.perform(get("/api/v1/support/contact"))
                .andExpect(jsonPath("$.whatsappNumber").value("081290906953"))
                .andExpect(jsonPath("$.whatsappE164").value("+6281290906953"))
                .andExpect(jsonPath("$.email").value("cs@tailtopia.id"));
    }

    @Test
    @DisplayName("🔴 改配置 → 端点与停用文案同批变，不需重启")
    void updatePropagatesToEveryConsumer() throws Exception {
        adminConfig.updateSupportContact("+62 813-1111-2222", "halo@tailtopia.id", ACTOR);

        // ① 免鉴权端点
        mvc.perform(get("/api/v1/support/contact"))
                .andExpect(jsonPath("$.whatsappNumber").value("+62 813-1111-2222"))
                .andExpect(jsonPath("$.whatsappE164").value("+6281311112222"));

        // ② provider（AuthService 的停用文案读的就是它）
        var c = contacts.contact();
        assertThat(c.whatsappNumber()).isEqualTo("+62 813-1111-2222");
        assertThat(c.email()).isEqualTo("halo@tailtopia.id");

        // 还原，免得污染同进程内后续用例（共享库不回滚）。
        adminConfig.updateSupportContact("081290906953", "cs@tailtopia.id", ACTOR);
    }

    @Test
    @DisplayName("🔴 写 SUPPORT_CONTACT 变更日志不撞 ck_config_change_type")
    void changeLogAcceptsSupportContactType() {
        long before = changeLogs.count();

        adminConfig.updateSupportContact("081290906954", "cs@tailtopia.id", ACTOR);

        // 撞 CHECK 的话上面这行就抛了；能走到这里说明迁移里的 DROP+ADD 重列生效了。
        assertThat(changeLogs.count()).isGreaterThan(before);
        assertThat(changeLogs.findAll().stream()
                .anyMatch(l -> l.getConfigType() == ConfigType.SUPPORT_CONTACT)).isTrue();

        adminConfig.updateSupportContact("081290906953", "cs@tailtopia.id", ACTOR);
    }

    @Test
    @DisplayName("无变更 → 不写日志、不记审计（与本类其它配置一致）")
    void noChangeWritesNothing() {
        adminConfig.updateSupportContact("081290906953", "cs@tailtopia.id", ACTOR);
        long before = changeLogs.count();

        adminConfig.updateSupportContact("081290906953", "cs@tailtopia.id", ACTOR);

        assertThat(changeLogs.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("🔒 号码格式不对整单拒绝，且错误里不回显用户输入")
    void invalidNumberIsRejectedWithoutEchoingInput() {
        String bogus = "12345-not-a-phone";
        try {
            adminConfig.updateSupportContact(bogus, "cs@tailtopia.id", ACTOR);
            throw new AssertionError("非法号码应当被拒");
        } catch (AppException e) {
            assertThat(e.getMessage()).doesNotContain(bogus);
        }
        // 整单拒绝：邮箱也没被改。
        assertThat(contacts.contact().whatsappNumber()).isEqualTo("081290906953");
    }

    @Test
    @DisplayName("🔴 超长输入是 422 不是 500 —— 两个上限都由 DB 列宽决定")
    void oversizedInputsAreRejectedAsValidationNot500() {
        // ① 号码：归一化会先剔掉空格与连字符，所以下面这串**归一化后合法**，
        //    光靠 IndonesiaPhone 拦不住；原样落 VARCHAR(20) 才炸。
        String longButNormalisable = "+62 812-3456-789 0000000000";
        assertThat(longButNormalisable.length()).isGreaterThan(20);
        assertThatThrownBy(
                () -> adminConfig.updateSupportContact(longButNormalisable, "cs@tailtopia.id",
                        ACTOR))
                .isInstanceOf(AppException.class);

        // ② 邮箱：config_change_logs.old_value/new_value 是 VARCHAR(64)，比本表的 120 更紧。
        String longEmail = "a".repeat(60) + "@tailtopia.id";
        assertThat(longEmail.length()).isGreaterThan(64);
        assertThatThrownBy(
                () -> adminConfig.updateSupportContact("081290906953", longEmail, ACTOR))
                .isInstanceOf(AppException.class);

        // 两次都整单拒绝，配置原样未动。
        assertThat(contacts.contact().whatsappNumber()).isEqualTo("081290906953");
        assertThat(contacts.contact().email()).isEqualTo("cs@tailtopia.id");
    }
}
