package com.tailtopia.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shared.i18n.AdminLocaleConfig;
import jakarta.validation.ConstraintViolation;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * L0（bug 20260923-541 附带）：兽医开户 / 编辑表单的校验提示走 i18n（{@code {admin.vets.validation.*}}），
 * 随后台语言切换。校验器按 Boot 的 {@code ValidationAutoConfiguration} 同样方式装配
 * （{@link MessageInterpolatorFactory} + 应用 messageSource），语言取 {@link LocaleContextHolder}（DispatcherServlet 按 LocaleResolver 设置）。
 */
class VetFormValidationI18nTest {

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean v = new LocalValidatorFactoryBean();
        v.setMessageInterpolator(new MessageInterpolatorFactory(new AdminLocaleConfig().messageSource()).getObject());
        v.afterPropertiesSet();
        return v;
    }

    private final LocalValidatorFactoryBean validator = validator();

    @AfterEach
    void reset() {
        LocaleContextHolder.resetLocaleContext();
    }

    private Set<String> messages(Object form, Locale locale) {
        LocaleContextHolder.setLocale(locale);
        return validator.validate(form).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    @Test
    void createFormBlankFieldsFollowTheLocale() {
        CreateVetForm f = new CreateVetForm();
        assertThat(messages(f, Locale.ENGLISH)).containsExactlyInAnyOrder("Vet nickname is required", "Login email is required",
                "Contact phone is required", "Initial password is required");
        assertThat(messages(f, Locale.SIMPLIFIED_CHINESE)).containsExactlyInAnyOrder("兽医昵称不能为空", "登录邮箱不能为空",
                "联系手机号不能为空", "初始密码不能为空");
        assertThat(messages(f, Locale.forLanguageTag("id"))).contains("Email login wajib diisi");
    }

    @Test
    void formatAndLengthMessagesAreLocalizedToo() {
        EditVetForm f = new EditVetForm();
        f.setDisplayName("x".repeat(65));
        f.setUsername("not-an-email");
        f.setContactPhone("1".repeat(33));
        assertThat(messages(f, Locale.ENGLISH)).containsExactlyInAnyOrder("Vet nickname must be at most 64 characters",
                "Login email format is invalid", "Contact phone is too long");
        CreateVetForm c = new CreateVetForm();
        c.setDisplayName("Dr");
        c.setUsername("a@b.co");
        c.setContactPhone("0812");
        c.setPassword("short");
        assertThat(messages(c, Locale.SIMPLIFIED_CHINESE)).containsExactly("密码需为 8～72 位");
    }

    @Test
    void noMessageLeaksTheRawKey() {
        CreateVetForm f = new CreateVetForm();
        for (Locale l : AdminLocaleConfig.SUPPORTED_LOCALES) {
            assertThat(messages(f, l)).noneMatch(m -> m.contains("admin.vets.validation") || m.contains("{"));
        }
    }
}
