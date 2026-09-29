package com.tailtopia.content.autocomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.content.species.ContentSpeciesResolver;
import com.tailtopia.content.species.ResolvedSpecies;
import com.tailtopia.content.species.SpeciesSource;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.ai.GeminiException;
import com.tailtopia.shared.ai.PetCommentGenerator;
import com.tailtopia.shared.ai.PetCommentResult;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoCommentServiceTest {

    private static final long POST_ID = 42L;
    private static final long AUTHOR = 7L;
    private static final long VIRTUAL = 3L;
    private static final String IMG = "https://tailtopia.oss-ap-southeast-5.aliyuncs.com/public/7/a.jpg";

    @Mock AutoCommentLogRepository logs;
    @Mock ContentPostRepository posts;
    @Mock UserRepository users;
    @Mock PetProfileRepository pets;
    @Mock ContentSpeciesResolver speciesResolver;
    @Mock VirtualIdentityPicker picker;
    @Mock CommentService commentService;
    @Mock PetCommentGenerator generator;
    @Mock TransactionTemplate tx;

    private AutoCommentService service;
    private ContentPost post;
    private User identity;

    @BeforeEach
    void setUp() {
        service = new AutoCommentService(logs, posts, users, pets, speciesResolver, picker, commentService,
                generator, new AutoCommentProperties(), tx);
        post = mock(ContentPost.class);
        when(post.getAuthorId()).thenReturn(AUTHOR);
        when(post.getStatus()).thenReturn(PostStatus.PUBLISHED);
        when(post.getVisibility()).thenReturn(ContentVisibility.PUBLIC);
        when(post.getImageUrls()).thenReturn(List.of(IMG, "https://x/2.jpg"));
        when(post.getText()).thenReturn("Main di taman");
        when(post.getCreatedAt()).thenReturn(Instant.parse("2026-09-29T01:00:00Z"));
        when(posts.findById(POST_ID)).thenReturn(Optional.of(post));
        when(speciesResolver.resolve(any(), any())).thenReturn(new ResolvedSpecies("DOG", SpeciesSource.PET_PROFILE));
        identity = mock(User.class);
        when(identity.getId()).thenReturn(VIRTUAL);
        when(identity.getNickname()).thenReturn("Budi");
        when(picker.pick(any(), eq(AUTHOR), eq("DOG"))).thenReturn(identity);
        when(logs.findByPostId(POST_ID)).thenReturn(Optional.empty());
        when(generator.model()).thenReturn("gemini-2.5-flash");
        when(generator.live()).thenReturn(true);
        lenient().when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    private AutoCommentLog savedLog() {
        ArgumentCaptor<AutoCommentLog> captor = ArgumentCaptor.forClass(AutoCommentLog.class);
        verify(logs).save(captor.capture());
        return captor.getValue();
    }

    private void aiReturns(PetCommentResult r) {
        when(generator.generate(anyString(), any(), any(), any())).thenReturn(r);
    }

    @Test
    void postsCommentWithFirstImageAndArchives() {
        aiReturns(PetCommentResult.of("Lucu banget! Suka main apa?"));
        CommentResponse created = mock(CommentResponse.class);
        when(created.id()).thenReturn(900L);
        when(commentService.createTopLevel(POST_ID, VIRTUAL, "Lucu banget! Suka main apa?")).thenReturn(created);

        AutoCommentStatus status = service.processOne(POST_ID, List.of(identity));

        assertThat(status).isEqualTo(AutoCommentStatus.POSTED);
        verify(generator).generate(eq(IMG), eq("Main di taman"), eq("DOG"), any());
        AutoCommentLog log = savedLog();
        assertThat(log.getStatus()).isEqualTo(AutoCommentStatus.POSTED);
        assertThat(log.getCommentId()).isEqualTo(900L);
        assertThat(log.getVirtualUserId()).isEqualTo(VIRTUAL);
        assertThat(log.getGeneratedText()).isEqualTo("Lucu banget! Suka main apa?");
        assertThat(log.getImageUrl()).isEqualTo(IMG);
        assertThat(log.getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(log.getPromptVersion()).isEqualTo(PetCommentGenerator.PROMPT_VERSION);
        assertThat(log.getAttempts()).isEqualTo(1);
    }

    @Test
    void noImage_archivedWithoutCallingAi() {
        when(post.getImageUrls()).thenReturn(List.of());

        assertThat(service.processOne(POST_ID, List.of(identity))).isEqualTo(AutoCommentStatus.NO_IMAGE);
        verify(generator, never()).generate(any(), any(), any(), any());
        assertThat(savedLog().getStatus()).isEqualTo(AutoCommentStatus.NO_IMAGE);
    }

    @Test
    void postTurnedPrivate_isPostGone() {
        when(post.getVisibility()).thenReturn(ContentVisibility.PRIVATE);

        assertThat(service.processOne(POST_ID, List.of(identity))).isEqualTo(AutoCommentStatus.POST_GONE);
        verify(generator, never()).generate(any(), any(), any(), any());
    }

    @Test
    void noUsableIdentity() {
        when(picker.pick(any(), eq(AUTHOR), eq("DOG"))).thenReturn(null);

        assertThat(service.processOne(POST_ID, List.of())).isEqualTo(AutoCommentStatus.NO_IDENTITY);
        verify(generator, never()).generate(any(), any(), any(), any());
    }

    @Test
    void aiSkip_archivesReasonAndDoesNotPost() {
        aiReturns(PetCommentResult.skipped("图片里没有宠物"));

        assertThat(service.processOne(POST_ID, List.of(identity))).isEqualTo(AutoCommentStatus.AI_SKIPPED);
        verify(commentService, never()).createTopLevel(anyLong(), anyLong(), anyString());
        assertThat(savedLog().getSkipReason()).isEqualTo("图片里没有宠物");
    }

    @Test
    void geminiFailure_isFailedForRetry() {
        when(generator.generate(anyString(), any(), any(), any())).thenThrow(new GeminiException("x"));

        assertThat(service.processOne(POST_ID, List.of(identity))).isEqualTo(AutoCommentStatus.FAILED);
        AutoCommentLog log = savedLog();
        assertThat(log.getErrorCode()).isEqualTo("GEMINI");
        verify(commentService, never()).createTopLevel(anyLong(), anyLong(), anyString());
    }

    @Test
    void tooLongComment_isFailedNotPosted() {
        aiReturns(PetCommentResult.of("a".repeat(201)));

        assertThat(service.processOne(POST_ID, List.of(identity))).isEqualTo(AutoCommentStatus.FAILED);
        verify(commentService, never()).createTopLevel(anyLong(), anyLong(), anyString());
        assertThat(savedLog().getErrorCode()).isEqualTo("AI_INVALID_LENGTH");
    }

    @Test
    void l1Blocked_isBlocked() {
        aiReturns(PetCommentResult.of("teks"));
        when(commentService.createTopLevel(POST_ID, VIRTUAL, "teks")).thenThrow(AppException.commentBlocked("x"));

        assertThat(service.processOne(POST_ID, List.of(identity))).isEqualTo(AutoCommentStatus.BLOCKED);
        assertThat(savedLog().getStatus()).isEqualTo(AutoCommentStatus.BLOCKED);
    }

    @Test
    void postDeletedDuringGeneration_isPostGone() {
        aiReturns(PetCommentResult.of("teks"));
        when(commentService.createTopLevel(POST_ID, VIRTUAL, "teks")).thenThrow(AppException.notFound("x"));

        assertThat(service.processOne(POST_ID, List.of(identity))).isEqualTo(AutoCommentStatus.POST_GONE);
    }

    @Test
    void someoneCommentedDuringGeneration_skipsWithoutArchive() {
        aiReturns(PetCommentResult.of("teks"));
        when(logs.hasLiveComment(POST_ID)).thenReturn(true);

        assertThat(service.processOne(POST_ID, List.of(identity))).isNull();
        verify(commentService, never()).createTopLevel(anyLong(), anyLong(), anyString());
        verify(logs, never()).save(any());
    }

    @Test
    void retryReusesExistingFailedRowAndIncrementsAttempts() {
        AutoCommentLog previous = AutoCommentLog.forPost(POST_ID);
        previous.beginAttempt();
        previous.setStatus(AutoCommentStatus.FAILED);
        previous.setErrorCode("GEMINI");
        when(logs.findByPostId(POST_ID)).thenReturn(Optional.of(previous));
        aiReturns(PetCommentResult.of("teks"));
        CommentResponse created = mock(CommentResponse.class);
        when(created.id()).thenReturn(901L);
        when(commentService.createTopLevel(POST_ID, VIRTUAL, "teks")).thenReturn(created);

        assertThat(service.processOne(POST_ID, List.of(identity))).isEqualTo(AutoCommentStatus.POSTED);
        AutoCommentLog log = savedLog();
        assertThat(log).isSameAs(previous);
        assertThat(log.getAttempts()).isEqualTo(2);
        assertThat(log.getErrorCode()).isNull();
    }

    @Test
    void runOnce_countsOutcomesAndKeepsGoingAfterFailure() {
        when(logs.findCandidatePostIds(any(), any(), eq(2), eq(200))).thenReturn(List.of(POST_ID, 43L));
        ContentPost gone = mock(ContentPost.class);
        when(posts.findById(43L)).thenReturn(Optional.empty());
        when(logs.findByPostId(43L)).thenReturn(Optional.empty());
        when(generator.generate(anyString(), any(), any(), any())).thenThrow(new GeminiException("x"));

        AutoCommentService.RunResult r = service.runOnce();

        assertThat(r.candidates()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.posted()).isZero();
    }

    @Test
    void preview_returnsCommentWithoutPostingOrArchiving() {
        aiReturns(PetCommentResult.of("Gemes! Umurnya berapa?"));

        AutoCommentPreview p = service.preview(POST_ID);

        assertThat(p.outcome()).isEqualTo("OK");
        assertThat(p.comment()).isEqualTo("Gemes! Umurnya berapa?");
        assertThat(p.virtualUserId()).isEqualTo(VIRTUAL);
        assertThat(p.imageUrl()).isEqualTo(IMG);
        assertThat(p.candidate()).isTrue();
        assertThat(p.aiLive()).isTrue();
        verify(commentService, never()).createTopLevel(anyLong(), anyLong(), anyString());
        verify(logs, never()).save(any());
    }

    @Test
    void preview_reportsNotCandidateWhenPostAlreadyHasComment() {
        when(logs.hasLiveComment(POST_ID)).thenReturn(true);
        aiReturns(PetCommentResult.of("x?"));

        assertThat(service.preview(POST_ID).candidate()).isFalse();
    }
}
