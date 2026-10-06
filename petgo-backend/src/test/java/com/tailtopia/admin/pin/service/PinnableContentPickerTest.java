package com.tailtopia.admin.pin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.pin.dto.PinnableContentRow;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：内容选择器候选（bug 20260924-562）—— 作者 / 首图 / 按 id 搜 / 不逐行查作者。 */
class PinnableContentPickerTest {

    private final ContentPostRepository posts = mock(ContentPostRepository.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final PinnableContentPicker picker = new PinnableContentPicker(posts, accounts);

    private static ContentPost post(long id, long authorId, String text, List<String> images) {
        ContentPost p = ContentPost.publish(authorId, ContentType.KNOWLEDGE, null, text, images);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "createdAt", Instant.parse("2026-09-23T03:00:00Z"));
        return p;
    }

    @Test
    void rowsCarryAuthorNameAndFirstImageWithOneBatchAuthorLookup() {
        when(posts.searchPinnable(anyString(), any(Pageable.class))).thenReturn(List.of(
                post(450, 7, "hello", List.of("https://cdn/a.jpg", "https://cdn/b.jpg")),
                post(449, 8, "teks saja", List.of())));
        when(accounts.findAuthorViews(any())).thenReturn(Map.of(
                7L, new AuthorView(7, "Budi", null, false, List.of()),
                8L, AuthorView.anonymized(8)));

        List<PinnableContentRow> rows = picker.page(null, 0);

        assertThat(rows).extracting(PinnableContentRow::id).containsExactly(450L, 449L);
        assertThat(rows.get(0).authorName()).isEqualTo("Budi");
        assertThat(rows.get(0).thumbUrl()).isEqualTo("https://cdn/a.jpg");
        assertThat(rows.get(1).authorDeleted()).isTrue();
        assertThat(rows.get(1).authorName()).isNull();
        assertThat(rows.get(1).thumbUrl()).isNull();
        verify(accounts).findAuthorViews(any()); // 整页一次，不逐行查
    }

    @Test
    void numericKeywordAlsoMatchesContentIdFirst() {
        ContentPost byId = post(4159, 7, "tidak mengandung angka itu", List.of());
        when(posts.searchPinnable(anyString(), any(Pageable.class))).thenReturn(List.of(post(1, 7, "x 4159 y", List.of())));
        when(posts.findById(4159L)).thenReturn(Optional.of(byId));
        when(accounts.findAuthorViews(any())).thenReturn(Map.of());

        assertThat(picker.page(" 4159 ", 0)).extracting(PinnableContentRow::id).containsExactly(4159L, 1L);
    }

    @Test
    void idHitOnlyOnFirstPageAndNotDuplicated() {
        ContentPost same = post(4159, 7, "4159", List.of());
        when(posts.searchPinnable(anyString(), any(Pageable.class))).thenReturn(List.of(same));
        when(posts.findById(4159L)).thenReturn(Optional.of(same));
        when(accounts.findAuthorViews(any())).thenReturn(Map.of());

        assertThat(picker.page("4159", 0)).extracting(PinnableContentRow::id).containsExactly(4159L);
        picker.page("4159", 1);
        verify(posts, org.mockito.Mockito.times(1)).findById(4159L);
    }

    @Test
    void deletedOrNonPublicContentFoundByIdIsNotOffered() {
        ContentPost gone = post(77, 7, "dihapus", List.of());
        gone.softDelete();
        when(posts.searchPinnable(anyString(), any(Pageable.class))).thenReturn(List.of());
        when(posts.findById(77L)).thenReturn(Optional.of(gone));

        assertThat(picker.page("77", 0)).isEmpty();
        verify(accounts, never()).findAuthorViews(any());
    }
}
