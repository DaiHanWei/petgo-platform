package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.MilestoneShare;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.MilestoneShareRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V1.3.2 batch-a Story 5.2 · L1（需 postgres + redis）：/m 分享页按 code 出徽章、缺素材回落、旧分享原样；
 * {@code /milestone/**} 公开放行；补上的 {@code /brand/wordmark_brand.svg}。
 *
 * <p>测试 classpath 只有一张 {@code static/milestone/first_treat.webp}（test resources），对应 C-S8 / D-S8 / G-S6。
 * 素材入库（2026-10-05）后 main 也有整套 {@code static/milestone/}，但 {@code classpath:}（非 {@code classpath*:}）
 * 只取第一个命中的目录，而 surefire 把 test-classes 排在 classes 前 —— 测试里看到的仍只有这一张，用例口径不变。
 */
class MilestoneSharePageBadgeIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PetProfileRepository pets;
    @Autowired
    private MilestoneShareRepository shares;

    private String share(String code, String level, String levels, String codes) {
        User u = newUser();
        PetProfile p = pets.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        String token = "ms" + SEQ.incrementAndGet() + "x".repeat(20);
        shares.save(MilestoneShare.create(token, p.getId(), code, level, "Momo", "Judul", "Isi", "id",
                levels, codes, Instant.parse("2026-09-01T00:00:00Z")));
        return token;
    }

    private String html(String token) throws Exception {
        return mvc.perform(get("/m/" + token)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    void oldShareKeepsLevelStringPathAndNoMilestoneAssets() throws Exception {
        String page = html(share("C-S1", "S", "SML", null));
        assertThat(page).contains("data-levels=\"SML\"").doesNotContain("data-items")
                .doesNotContain("/milestone/");
        assertThat(page).contains("🏆");
    }

    @Test
    void newShareWithMissingAssetsFallsBackToTrophy() throws Exception {
        String page = html(share("C-S1", "S", "SS", "C-S1,C-S2"));
        assertThat(page).contains("data-items").doesNotContain("data-levels")
                .doesNotContain("/milestone/");
        assertThat(page).contains("<div class=\"badge\">🏆</div>");
    }

    @Test
    void newShareWithAssetReferencesItForBigBadgeAndCollection() throws Exception {
        String page = html(share("G-S6", "S", "SS", "G-S6,C-S1"));
        assertThat(page).contains("<img src=\"/milestone/first_treat.webp\" alt=\"\">");
        assertThat(page).contains("&quot;url&quot;:&quot;/milestone/first_treat.webp&quot;");
    }

    @Test
    void goneShareStillCardGone404() throws Exception {
        mvc.perform(get("/m/no-such-token-at-all")).andExpect(status().isNotFound());
    }

    @Test
    void milestoneAssetsArePublicGetOnly() throws Exception {
        mvc.perform(get("/milestone/does-not-exist.webp")).andExpect(status().isNotFound());
        mvc.perform(get("/milestone/first_treat.webp"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("image/webp")));
    }

    @Test
    void wordmarkExistsForContentSharePage() throws Exception {
        mvc.perform(get("/brand/wordmark_brand.svg")).andExpect(status().isOk());
    }
}
