package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.species.ContentSpecies;
import org.junit.jupiter.api.Test;

/** L0：物种桶（Story 16.2 · AC2 / AC3）—— 🔴 含「推不出来 → GENERAL」那处口径。 */
class SpeciesBucketTest {

    @Test
    void sameAsViewerMainSpeciesIsMain() {
        assertThat(SpeciesBucket.of(ContentSpecies.CAT, ContentSpecies.CAT))
                .isEqualTo(SpeciesBucket.MAIN);
    }

    @Test
    void differentConcreteSpeciesIsOther() {
        assertThat(SpeciesBucket.of(ContentSpecies.DOG, ContentSpecies.CAT))
                .isEqualTo(SpeciesBucket.OTHER);
        assertThat(SpeciesBucket.of(ContentSpecies.OTHER, ContentSpecies.CAT))
                .isEqualTo(SpeciesBucket.OTHER);
    }

    @Test
    void explicitGeneralIsGeneral() {
        assertThat(SpeciesBucket.of(ContentSpecies.GENERAL, ContentSpecies.CAT))
                .isEqualTo(SpeciesBucket.GENERAL);
    }

    /**
     * 🔴 <b>本 story 最容易被实现错的一条</b>：14.1 的 resolver 对无信号内容返回<b>空</b>，
     * 引擎必须把它当 GENERAL —— 否则这些内容不属于任何桶、永远排不进去。
     *
     * <p>⚠️ 反过来<b>不改 14.1</b>：后台自查列要的就是「推不出来」这个区分。
     */
    @Test
    void unknownSpeciesFallsIntoGeneralPool() {
        assertThat(SpeciesBucket.of(null, ContentSpecies.CAT)).isEqualTo(SpeciesBucket.GENERAL);
    }

    /** 查看者无主物种（无档案 / 游客）：物种维度不生效，判桶只需给个确定答案。 */
    @Test
    void viewerWithoutMainSpeciesGetsDeterministicBucket() {
        assertThat(SpeciesBucket.of(ContentSpecies.DOG, null)).isEqualTo(SpeciesBucket.GENERAL);
        assertThat(SpeciesBucket.of(null, null)).isEqualTo(SpeciesBucket.GENERAL);
    }
}
