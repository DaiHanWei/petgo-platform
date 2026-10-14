package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.ActorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.service.InteractionCopyRenderer.ActorSummary;
import com.tailtopia.social.read.UserHideRelationReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/**
 * L0：互动通知文案（V1.3.4 follow · Story 3.1 · AC5）—— 触发人过滤（注销 / 封号 / 被接收者隐藏不计入，
 * 最新触发人被滤后顺延）、{@code .one / .many} 选择、评论 / 回复、{@code {1}} 不带千分位。
 *
 * <p>文案用<b>真实的</b> {@code i18n/messages*.properties}（与 {@code AdminLocaleConfig} 同配置），守住「值里不出现单引号」。
 */
class InteractionCopyRendererTest {

    private static final Locale ID = Locale.forLanguageTag("id");
    private static final Locale EN = Locale.ENGLISH;

    private AccountQueryService accounts;
    private UserHideRelationReader hide;
    private InteractionCopyRenderer renderer;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        accounts = mock(AccountQueryService.class);
        hide = mock(UserHideRelationReader.class);
        renderer = new InteractionCopyRenderer(accounts, hide, source);
    }

    private static ActorView view(long id, String nickname) {
        return new ActorView(id, nickname, "tok" + id, true);
    }

    private static ActorView inactive(long id) {
        return new ActorView(id, null, null, false);
    }

    @Test
    void summarizeSkipsInactiveHiddenNamelessAndMissingThenFallsThrough() {
        Map<Long, ActorView> views = Map.of(
                1L, inactive(1L),
                2L, view(2L, "Hidden"),
                3L, view(3L, null),
                4L, view(4L, "Rani"),
                5L, view(5L, "Budi"),
                6L, view(6L, "Sari"));

        Optional<ActorSummary> s = InteractionCopyRenderer.summarize(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), views,
                Set.of(2L, 6L));

        assertThat(s).contains(new ActorSummary(4L, "Rani", "tok4", 1));
        assertThat(s.get().total()).isEqualTo(2);
    }

    @Test
    void summarizeIsEmptyWhenEveryoneIsFilteredOut() {
        assertThat(InteractionCopyRenderer.summarize(List.of(1L, 2L),
                Map.of(1L, inactive(1L), 2L, view(2L, "X")), Set.of(2L))).isEmpty();
    }

    /** D-8：封号（非 active）与已注销同等处理；T-30：接收者隐藏的人不计入。固定 2 次批量查询。 */
    @Test
    void resolveExcludesInactiveAndHiddenWithTwoBatchQueries() {
        when(accounts.findActorViews(anyCollection()))
                .thenReturn(Map.of(4L, inactive(4L), 5L, view(5L, "Hidden"), 6L, view(6L, "Budi"),
                        7L, view(7L, "Sari")));
        when(hide.hiddenByHolder(anyLong(), anyCollection())).thenReturn(Set.of(5L));

        assertThat(renderer.resolve(9L, List.of(4L, 5L, 6L, 7L)))
                .contains(new ActorSummary(6L, "Budi", "tok6", 1));
        org.mockito.Mockito.verify(accounts, org.mockito.Mockito.times(1)).findActorViews(anyCollection());
        org.mockito.Mockito.verifyNoMoreInteractions(accounts);
    }

    @Test
    void resolveOfNoActorsIsEmpty() {
        assertThat(renderer.resolve(9L, List.of())).isEmpty();
    }

    @Test
    void likedPicksOneOrManyAndFormatsInRecipientLanguage() {
        assertThat(renderer.title(NotificationType.CONTENT_LIKED, null, new ActorSummary(1L, "Rani", "t", 0), ID))
                .isEqualTo("Rani menyukai postinganmu");
        assertThat(renderer.title(NotificationType.CONTENT_LIKED, null, new ActorSummary(1L, "Rani", "t", 2), ID))
                .isEqualTo("Rani dan 2 lainnya menyukai postinganmu");
        assertThat(renderer.title(NotificationType.CONTENT_LIKED, null, new ActorSummary(1L, "Rani", "t", 2), EN))
                .isEqualTo("Rani and 2 others liked your post");
    }

    @Test
    void othersCountHasNoThousandsSeparator() {
        assertThat(renderer.title(NotificationType.CONTENT_LIKED, null, new ActorSummary(1L, "Rani", "t", 1234), ID))
                .isEqualTo("Rani dan 1234 lainnya menyukai postinganmu");
        assertThat(renderer.title(NotificationType.PLACE_COMMENTED, null, new ActorSummary(1L, "Pet Walker", "t", 1234),
                EN)).isEqualTo("Pet Walker and 1234 others commented on the place you added");
    }

    @Test
    void commentReplyAndFollowTemplates() {
        ActorSummary s = new ActorSummary(1L, "Rani", "t", 0);
        assertThat(renderer.title(NotificationType.CONTENT_COMMENTED, null, s, ID))
                .isEqualTo("Rani mengomentari postinganmu");
        assertThat(renderer.title(NotificationType.CONTENT_COMMENTED, Notification.VARIANT_REPLY, s, ID))
                .isEqualTo("Rani membalas komentarmu");
        assertThat(renderer.title(NotificationType.USER_FOLLOWED, null, s, ID)).isEqualTo("Rani mulai mengikutimu");
        assertThat(renderer.title(NotificationType.PLACE_COMMENTED, null, s, ID))
                .isEqualTo("Rani mengomentari tempat yang kamu tambahkan");
        assertThat(renderer.title(NotificationType.USER_FOLLOWED, null, s, EN)).isEqualTo("Rani started following you");
    }

    /** 昵称里的单引号 / 花括号原样输出（MessageFormat 只解析模板，不解析实参）。 */
    @Test
    void nicknameIsNotInterpretedAsPattern() {
        assertThat(renderer.title(NotificationType.CONTENT_LIKED, null, new ActorSummary(1L, "D'Arcy {0}", "t", 0), EN))
                .isEqualTo("D'Arcy {0} liked your post");
    }

    @Test
    void commentDeletedCopyFollowsLocale() {
        assertThat(renderer.commentDeleted(ID)).isEqualTo("Komentar telah dihapus");
        assertThat(renderer.commentDeleted(EN)).isEqualTo("Comment deleted");
    }

    @Test
    void nonInteractionTypeIsRejected() {
        assertThatThrownBy(() -> renderer.title(NotificationType.VET_REPLY, null, new ActorSummary(1L, "x", "t", 0), ID))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
