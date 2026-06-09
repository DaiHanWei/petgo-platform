package com.petgo.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.service.ContentService;
import com.petgo.content.service.GrowthMomentView;
import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.service.ProfileService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/** L0：名片直出 + 仅快乐时刻 + 多态失效统一 404 防枚举（AC1/AC4）。 */
class CardPageControllerTest {

    private ProfileService profileService;
    private ContentService contentService;
    private AccountQueryService accountQueryService;
    private CardPageController controller;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        contentService = mock(ContentService.class);
        accountQueryService = mock(AccountQueryService.class);
        controller = new CardPageController(profileService, contentService, accountQueryService,
                "https://dl", "https://h5.petgo");
    }

    private PetProfile profile() {
        PetProfile p = PetProfile.create(7L, com.petgo.profile.domain.PetType.CAT, "Momo",
                "https://cdn/a.jpg", "Shiba", LocalDate.of(2022, 1, 1), "好奇宝宝", "TOK");
        return p;
    }

    @Test
    void validTokenRendersCardWithStrippedImagesAndMoments() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        when(accountQueryService.isActive(7L)).thenReturn(true);
        when(contentService.findGrowthMoments(anyLong(), any(), anyInt()))
                .thenReturn(List.of(new GrowthMomentView(1L, Instant.now(), List.of("https://cdn/m.jpg"), "hi")));

        Model model = new ConcurrentModel();
        HttpServletResponse resp = mock(HttpServletResponse.class);
        String view = controller.card("TOK", model, resp);

        assertThat(view).isEqualTo("card");
        assertThat(model.getAttribute("name")).isEqualTo("Momo");
        // E4：对外图带去 EXIF process 参数
        assertThat(model.getAttribute("avatarUrl").toString()).contains("x-oss-process=image/");
        @SuppressWarnings("unchecked")
        List<CardPageController.CardMoment> moments =
                (List<CardPageController.CardMoment>) model.getAttribute("moments");
        assertThat(moments).hasSize(1);
        assertThat(moments.get(0).getImageUrls().get(0)).contains("x-oss-process=image/");
    }

    @Test
    void unknownTokenRendersGoneWith404() {
        when(profileService.findByCardToken("NOPE")).thenReturn(Optional.empty());
        Model model = new ConcurrentModel();
        HttpServletResponse resp = mock(HttpServletResponse.class);

        String view = controller.card("NOPE", model, resp);

        assertThat(view).isEqualTo("card_gone");
        verify(resp).setStatus(404);
    }

    @Test
    void deletedAccountRendersSameGonePageNoEnumerationLeak() {
        when(profileService.findByCardToken("TOK")).thenReturn(Optional.of(profile()));
        when(accountQueryService.isActive(7L)).thenReturn(false); // 账号注销
        Model model = new ConcurrentModel();
        HttpServletResponse resp = mock(HttpServletResponse.class);

        String view = controller.card("TOK", model, resp);

        // 与「不存在」完全一致：同视图 + 同 404，不泄漏 token 曾否存在
        assertThat(view).isEqualTo("card_gone");
        verify(resp).setStatus(404);
    }
}
