package com.tailtopia.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.config.domain.AppVersionConfig;
import com.tailtopia.config.repository.AppVersionConfigRepository;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

/** L0（V1.3.4 Story 3.1 · AC4）：版本配置写校验顺序与错误码（D-17）。纯 Mockito。 */
class AppVersionConfigServiceTest {

    private static final String IOS_URL = "https://apps.apple.com/app/id6785361196";
    private static final String ANDROID_URL = "https://play.google.com/store/apps/details?id=com.tailtopia.app";

    private AppVersionConfigRepository repo;
    private AppVersionConfigService svc;
    private AppVersionConfig row;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(AppVersionConfigRepository.class);
        svc = new AppVersionConfigService(repo);
        row = instantiate(AppVersionConfig.class);
        set(row, "id", 1L);
        when(repo.findById(1L)).thenReturn(Optional.of(row));
        when(repo.save(any(AppVersionConfig.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static AppVersionConfigUpdate cmd(String ios, String android, String min,
            String iosUrl, String androidUrl) {
        return new AppVersionConfigUpdate(ios, android, min, iosUrl, androidUrl);
    }

    private void assertRejected(AppVersionConfigUpdate c, String code) {
        assertThatThrownBy(() -> svc.update(c, 7L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getMessageCode()).isEqualTo(code);
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        verify(repo, never()).save(any());
    }

    @Test
    void validInput_savesAllFiveValues_trimmed_andStampsAdmin() {
        AppVersionConfig saved = svc.update(cmd(" 1.3.4 ", "1.3.3\t", " 1.0.0",
                "  " + IOS_URL, ANDROID_URL + " "), 42L);

        assertThat(saved).isSameAs(row);
        assertThat(saved.getIosLatestVersion()).isEqualTo("1.3.4");
        assertThat(saved.getAndroidLatestVersion()).isEqualTo("1.3.3");
        assertThat(saved.getMinSupportedVersion()).isEqualTo("1.0.0");
        assertThat(saved.getIosStoreUrl()).isEqualTo(IOS_URL);
        assertThat(saved.getAndroidStoreUrl()).isEqualTo(ANDROID_URL);
        assertThat(saved.getUpdatedBy()).isEqualTo(42L);
        assertThat(saved.getUpdatedAt()).isNotNull();
        verify(repo).save(row);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.3", "v1.3.3", "1.3.3-beta", "01.2.3", "", "1.3.10000"})
    void badVersionFormat_rejected(String bad) {
        assertRejected(cmd(bad, "1.3.3", "1.0.0", IOS_URL, ANDROID_URL), "admin.err.config.version.format");
    }

    @Test
    void badFormatInAndroidOrMin_rejected() {
        assertRejected(cmd("1.3.3", "1.3", "1.0.0", IOS_URL, ANDROID_URL), "admin.err.config.version.format");
        assertRejected(cmd("1.3.3", "1.3.3", "v1.0.0", IOS_URL, ANDROID_URL), "admin.err.config.version.format");
    }

    @Test
    void nullVersion_treatedAsBlank_rejectedAsFormat() {
        assertRejected(cmd(null, "1.3.3", "1.0.0", IOS_URL, ANDROID_URL), "admin.err.config.version.format");
    }

    @Test
    void nonHttpsStoreUrl_rejected() {
        assertRejected(cmd("1.3.3", "1.3.3", "1.0.0", "http://apps.apple.com/app/id1", ANDROID_URL),
                "admin.err.config.store-url.scheme");
        assertRejected(cmd("1.3.3", "1.3.3", "1.0.0", IOS_URL, "HTTPS://play.google.com/x"),
                "admin.err.config.store-url.scheme");
    }

    /** 只要高于任一端最新版本就拒。 */
    @Test
    void minGreaterThanEitherLatest_rejected() {
        assertRejected(cmd("1.3.3", "1.5.0", "1.4.0", IOS_URL, ANDROID_URL),
                "admin.err.config.version.min-gt-latest");
    }

    @Test
    void raisedMinWithBlankStoreUrl_rejected() {
        assertRejected(cmd("1.3.3", "1.3.3", "1.1.0", "", ANDROID_URL), "admin.err.config.store-url.blank");
        assertRejected(cmd("1.3.3", "1.3.3", "1.1.0", IOS_URL, null), "admin.err.config.store-url.blank");
    }

    /** 校验顺序：格式先于链接 scheme，scheme 先于 min-gt-latest，min-gt-latest 先于 blank。 */
    @Test
    void validationOrder() {
        assertRejected(cmd("1.3", "1.3.3", "9.0.0", "http://x", ""), "admin.err.config.version.format");
        assertRejected(cmd("1.3.3", "1.3.3", "9.0.0", "http://x", ""), "admin.err.config.store-url.scheme");
        assertRejected(cmd("1.3.3", "1.3.3", "9.0.0", "", ""), "admin.err.config.version.min-gt-latest");
    }

    @Test
    void defaultMinWithBlankUrls_passes() {
        AppVersionConfig saved = svc.update(cmd("1.3.3", "1.3.3", "1.0.0", "", ""), 7L);
        assertThat(saved.getIosStoreUrl()).isEmpty();
        assertThat(saved.getAndroidStoreUrl()).isEmpty();
    }

    @Test
    void minEqualToLatest_passes() {
        AppVersionConfig saved = svc.update(cmd("1.3.3", "1.3.3", "1.3.3", IOS_URL, ANDROID_URL), 7L);
        assertThat(saved.getMinSupportedVersion()).isEqualTo("1.3.3");
    }

    /** 数值逐段比较，不是字符串比较：1.10.0 > 1.9.0。 */
    @Test
    void numericComparison_notLexicographic() {
        AppVersionConfig saved = svc.update(cmd("1.10.0", "1.10.0", "1.9.0", IOS_URL, ANDROID_URL), 7L);
        assertThat(saved.getMinSupportedVersion()).isEqualTo("1.9.0");
    }

    @Test
    void missingRow_throwsIllegalState() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.update(cmd("1.3.3", "1.3.3", "1.0.0", IOS_URL, ANDROID_URL), 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("app_version_config 缺失");
        verify(repo, never()).save(any());
    }

    @Test
    void current_readsSingletonRow() {
        assertThat(svc.current()).containsSame(row);
        verify(repo).findById(AppVersionConfig.SINGLETON_ID);
    }

    private static <T> T instantiate(Class<T> cls) {
        try {
            var ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object o, String name, Object value) {
        try {
            var f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
