package com.petgo.shared.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/** L0：IM→私密桶桥接——空操作、key 不可枚举、有 fetcher 时逐张复制（AC2 逻辑面，真实跨云 L2）。 */
class ImToOssArchiverTest {

    private AliyunOssClient oss;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ImMediaFetcher> fetcherProvider = Mockito.mock(ObjectProvider.class);
    private ImToOssArchiver archiver;

    @BeforeEach
    void setUp() {
        oss = Mockito.mock(AliyunOssClient.class);
        archiver = new ImToOssArchiver(oss, fetcherProvider);
    }

    @Test
    void emptyRefsIsNoop() {
        assertThat(archiver.archiveImImagesToPrivate(5L, null)).isEmpty();
        assertThat(archiver.archiveImImagesToPrivate(5L, List.of())).isEmpty();
        verify(oss, never()).putPrivateObject(anyString(), any());
    }

    @Test
    void withoutFetcherReturnsEmptyWithoutPutting() {
        when(fetcherProvider.getIfAvailable()).thenReturn(null);
        assertThat(archiver.archiveImImagesToPrivate(5L, List.of("im-1"))).isEmpty();
        verify(oss, never()).putPrivateObject(anyString(), any());
    }

    @Test
    void copiesEachImImageToPrivateBucket() {
        ImMediaFetcher fetcher = Mockito.mock(ImMediaFetcher.class);
        when(fetcherProvider.getIfAvailable()).thenReturn(fetcher);
        when(fetcher.fetch(anyString())).thenReturn(new byte[]{1, 2, 3});

        List<String> keys = archiver.archiveImImagesToPrivate(5L, List.of("im-1", "im-2"));

        assertThat(keys).hasSize(2);
        assertThat(keys).allMatch(k -> k.startsWith("private/health/5/") && k.endsWith(".jpg"));
        verify(oss, Mockito.times(2)).putPrivateObject(anyString(), any());
    }

    @Test
    void privateKeyIsScopedAndUnenumerable() {
        String k1 = archiver.buildPrivateKey(7L);
        String k2 = archiver.buildPrivateKey(7L);
        assertThat(k1).startsWith("private/health/7/");
        assertThat(k1).isNotEqualTo(k2);
    }
}
