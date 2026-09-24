package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.seed.dto.RowError;
import com.tailtopia.admin.seed.service.SeedRowErrorRenderer;
import com.tailtopia.admin.seed.service.SeedRowErrors;
import com.tailtopia.shared.error.AppException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * L0（bug 20260924-565）：批量内容行错误的存储格式 + 按后台语言渲染。
 *
 * <p>存库串是 {@code i18n:<key>|<arg>…}，多条换行分隔；🛡 无前缀的历史中文原样显示。
 */
class SeedRowErrorsTest {

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static SeedRowErrorRenderer renderer() {
        // 与 AdminLocaleConfig#messageSource 同配置（真实四份 messages 文件）。
        ReloadableResourceBundleMessageSource src = new ReloadableResourceBundleMessageSource();
        src.setBasename("classpath:i18n/messages");
        src.setDefaultEncoding(StandardCharsets.UTF_8.name());
        src.setFallbackToSystemLocale(false);
        src.setUseCodeAsDefaultMessage(true);
        return new SeedRowErrorRenderer(src);
    }

    // ——— 编码 / 解码 ———

    @Test
    void encodesKeyAndArgsWithPrefixAndRoundTrips() {
        String s = SeedRowErrors.encode("admin.err.seedBatch.row.speciesInvalid", "FISH", "[CAT, DOG]");
        assertThat(s).isEqualTo("i18n:admin.err.seedBatch.row.speciesInvalid|FISH|[CAT, DOG]");
        assertThat(SeedRowErrors.decode(s)).containsExactly(
                new RowError("admin.err.seedBatch.row.speciesInvalid", "FISH", "[CAT, DOG]"));
    }

    @Test
    void multipleErrorsAreNewlineSeparated() {
        String s = SeedRowErrors.encode(List.of(new RowError("admin.err.seedBatch.row.authorUnset"),
                new RowError("admin.err.seedBatch.row.assetNameMissing", "a.png")));
        assertThat(s).isEqualTo("i18n:admin.err.seedBatch.row.authorUnset\n"
                + "i18n:admin.err.seedBatch.row.assetNameMissing|a.png");
        assertThat(SeedRowErrors.decode(s)).extracting(RowError::key).containsExactly(
                "admin.err.seedBatch.row.authorUnset", "admin.err.seedBatch.row.assetNameMissing");
    }

    @Test
    void argsWithSeparatorsBackslashesAndNewlinesSurvive() {
        String nasty = "a|b\\c\nd";
        String s = SeedRowErrors.encode("admin.err.seedBatch.row.assetNameMissing", nasty);
        assertThat(s).doesNotContain("\n");
        assertThat(SeedRowErrors.decode(s)).singleElement()
                .satisfies(e -> assertThat(e.args()).containsExactly(nasty));
    }

    @Test
    void emptyListEncodesToNullAndNullDecodesToEmpty() {
        assertThat(SeedRowErrors.encode(List.of())).isNull();
        assertThat(SeedRowErrors.decode(null)).isEmpty();
        assertThat(SeedRowErrors.decode("  ")).isEmpty();
    }

    @Test
    void legacyChineseWithoutPrefixIsKeptVerbatimAsOneEntry() {
        String legacy = "未指定发布账号，且批次未设默认；素材「x.png」不在本批素材里";
        assertThat(SeedRowErrors.decode(legacy)).singleElement().satisfies(e -> {
            assertThat(e.isRaw()).isTrue();
            assertThat(e.rawText()).isEqualTo(legacy);
        });
    }

    @Test
    void overlongListIsTruncatedAtWholeEntries() {
        List<RowError> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new RowError("admin.err.seedBatch.row.assetNameMissing", "file-" + i + ".png"));
        }
        String s = SeedRowErrors.encode(many);
        assertThat(s.length()).isLessThanOrEqualTo(SeedRowErrors.MAX_LEN);
        List<RowError> back = SeedRowErrors.decode(s);
        assertThat(back).isNotEmpty().hasSizeLessThan(40);
        // 每一条都是完整的：最后一条的文件名没被截半。
        assertThat(back).allSatisfy(e -> assertThat(String.valueOf(e.args()[0])).matches("file-\\d+\\.png"));
        assertThat(back.get(back.size() - 1).args()[0]).isEqualTo("file-" + (back.size() - 1) + ".png");
    }

    @Test
    void singleOverlongEntryIsHardCutButStillDecodes() {
        String s = SeedRowErrors.encode("admin.err.seedBatch.row.assetNameMissing", "x".repeat(800));
        assertThat(s).hasSize(SeedRowErrors.MAX_LEN).startsWith("i18n:admin.err.seedBatch.row.assetNameMissing|");
        assertThat(SeedRowErrors.decode(s)).singleElement()
                .satisfies(e -> assertThat(e.key()).isEqualTo("admin.err.seedBatch.row.assetNameMissing"));
    }

    @Test
    void exceptionWithCodeStoresCodeAndArgsOtherwiseRawOrFallback() {
        AppException coded = AppException.validation("发布账号「阿花」已停用")
                .code("admin.err.seedBatch.authorDisabled", "阿花");
        assertThat(SeedRowErrors.fromException(coded, "admin.err.seedBatch.row.publishFailed"))
                .isEqualTo("i18n:admin.err.seedBatch.authorDisabled|阿花");
        assertThat(SeedRowErrors.fromException(new IllegalStateException("oss down"),
                "admin.err.seedBatch.row.publishFailed")).isEqualTo("oss down");
        assertThat(SeedRowErrors.fromException(new RuntimeException(),
                "admin.err.seedBatch.row.scheduledPublishFailed"))
                .isEqualTo("i18n:admin.err.seedBatch.row.scheduledPublishFailed");
    }

    // ——— 渲染 ———

    @Test
    void rendersInTheAdminLocale() {
        String stored = SeedRowErrors.encode(List.of(new RowError("admin.err.seedBatch.row.authorUnset"),
                new RowError("admin.err.seedBatch.row.assetNameMissing", "a.png")));
        SeedRowErrorRenderer r = renderer();

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(r.lines(stored)).containsExactly(
                "No publishing account specified, and the batch has no default",
                "Asset \"a.png\" is not in this batch");

        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertThat(r.lines(stored)).containsExactly("未指定发布账号，且批次未设默认", "素材「a.png」不在本批素材里");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("id"));
        assertThat(r.lines(stored)).first().asString().startsWith("Akun penerbit");
    }

    @Test
    void legacyChineseRendersVerbatimInAnyLocale() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(renderer().lines("发布失败：审核拦截")).containsExactly("发布失败：审核拦截");
        assertThat(renderer().render("发布失败：审核拦截")).isEqualTo("发布失败：审核拦截");
    }

    @Test
    void renderJoinsMultipleEntriesAndIsNullWhenEmpty() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        String stored = SeedRowErrors.encode(List.of(new RowError("admin.err.seedBatch.row.empty"),
                new RowError("admin.err.seedBatch.row.assetGone")));
        assertThat(renderer().render(stored)).isEqualTo(
                "Both the text and the images are empty · A referenced asset is no longer in this batch");
        assertThat(renderer().render(null)).isNull();
        assertThat(renderer().lines(null)).isEmpty();
    }

    @Test
    void textOfARawRowErrorIsItsOriginal() {
        assertThat(renderer().text(RowError.raw("旧原因"))).isEqualTo("旧原因");
    }
}
