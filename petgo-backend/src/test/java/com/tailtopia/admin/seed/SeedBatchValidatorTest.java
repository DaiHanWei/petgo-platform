package com.tailtopia.admin.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.seed.domain.SeedBatch;
import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.dto.RowError;
import com.tailtopia.admin.seed.dto.RowValidation;
import com.tailtopia.admin.seed.repository.SeedBatchAssetRepository;
import com.tailtopia.admin.seed.service.SeedBatchAssetService;
import com.tailtopia.admin.seed.service.SeedBatchValidator;
import com.tailtopia.admin.virtual.repository.SeedContentHashRepository;
import com.tailtopia.admin.virtual.service.AdminPublishIdentityService;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0（bug 20260924-565）：校验结果是<b>文案码 + 实参</b>，不是中文句子 ——
 * 展示端才按后台语言渲染。🛡 录入阶段已记下的码原样带出（同一问题同一个码）。
 */
class SeedBatchValidatorTest {

    private SeedBatchValidator validator;
    private SeedBatch batch;

    @BeforeEach
    void setUp() {
        SeedBatchAssetRepository assets = mock(SeedBatchAssetRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(anyLong())).thenReturn(Optional.empty());
        when(assets.findByBatchIdAndOrphanedAtIsNull(anyLong())).thenReturn(List.of());
        validator = new SeedBatchValidator(assets, users, mock(AdminPublishIdentityService.class),
                mock(SeedContentHashRepository.class), mock(SeedBatchAssetService.class));
        batch = mock(SeedBatch.class);
        when(batch.getId()).thenReturn(1L);
    }

    private RowValidation one(SeedBatchRow row) {
        return validator.validate(batch, List.of(row)).get(0);
    }

    @Test
    void missingAuthorAndEmptyContentYieldKeysNotChinese() {
        RowValidation v = one(SeedBatchRow.draft(1L, 1, 0L, ContentType.DAILY, null, null, null, null));
        assertThat(v.passes()).isFalse();
        assertThat(v.errors()).extracting(RowError::key).containsExactly(
                "admin.err.seedBatch.row.authorUnset", "admin.err.seedBatch.row.empty");
        assertThat(v.errors()).allSatisfy(e -> assertThat(e.isRaw()).isFalse());
    }

    @Test
    void unknownAuthorGrowthMomentAndBadSpeciesCarryTheirArgs() {
        RowValidation v = one(SeedBatchRow.draft(1L, 1, 42L, ContentType.GROWTH_MOMENT, null, "x", null, null,
                "FISH"));
        assertThat(v.errors()).extracting(RowError::key).containsExactly(
                "admin.err.seedBatch.row.authorNotFound",
                "admin.err.seedBatch.row.growthMoment",
                "admin.err.seedBatch.row.speciesInvalid");
        assertThat(v.errors().get(0).args()).containsExactly("42");
        assertThat(v.errors().get(2).args()[0]).isEqualTo("FISH");
    }

    @Test
    void entryStageCodeIsCarriedOverAndSuppressesTheDuplicateAuthorError() {
        SeedBatchRow row = SeedBatchRow.draft(1L, 1, 0L, ContentType.DAILY, null, "x", null, null);
        row.setErrorMessage("i18n:admin.err.seedBatch.row.authorUnset\n"
                + "i18n:admin.err.seedBatch.row.assetNameMissing|a.png");
        RowValidation v = one(row);
        assertThat(v.errors()).containsExactly(
                new RowError("admin.err.seedBatch.row.authorUnset"),
                new RowError("admin.err.seedBatch.row.assetNameMissing", "a.png"));
    }

    @Test
    void legacyChineseErrorOnTheRowIsShownVerbatim() {
        SeedBatchRow row = SeedBatchRow.draft(1L, 1, 0L, ContentType.DAILY, null, "x", null, null);
        row.setErrorMessage("素材不在本批素材里：a.png");
        RowValidation v = one(row);
        assertThat(v.errors()).singleElement().satisfies(e -> {
            assertThat(e.isRaw()).isTrue();
            assertThat(e.rawText()).isEqualTo("素材不在本批素材里：a.png");
        });
    }
}
