package com.tailtopia.shared.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.exceptions.ServerException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** L0（打桩 SDK 客户端）：专题线 video Story 3.4 AC1 —— 刷新走 RefreshObjectCaches(File)，失败一律抛出。 */
class AliyunCdnCacheRefresherTest {

    private static final String URL = "https://v.cdn.test/video/1/abcdefghijklmnopqrstu.mp4";

    private final IAcsClient client = mock(IAcsClient.class);
    private final AliyunCdnCacheRefresher refresher = new AliyunCdnCacheRefresher(new MediaProperties(), client);

    @Test
    void refreshSendsRefreshObjectCachesForTheFile() throws Exception {
        when(client.getCommonResponse(any(CommonRequest.class))).thenReturn(new CommonResponse());

        refresher.refresh(URL);

        ArgumentCaptor<CommonRequest> req = ArgumentCaptor.forClass(CommonRequest.class);
        verify(client).getCommonResponse(req.capture());
        assertThat(req.getValue().getSysAction()).isEqualTo("RefreshObjectCaches");
        assertThat(req.getValue().getSysDomain()).isEqualTo("cdn.aliyuncs.com");
        assertThat(req.getValue().getSysQueryParameters())
                .containsEntry("ObjectPath", URL).containsEntry("ObjectType", "File");
    }

    @Test
    void clientErrorIsRethrownWithoutUrl() throws Exception {
        when(client.getCommonResponse(any(CommonRequest.class)))
                .thenThrow(new ClientException("SDK.InvalidAccessKey", "bad key"));

        assertThatThrownBy(() -> refresher.refresh(URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SDK.InvalidAccessKey")
                .hasMessageNotContaining("https://");
    }

    @Test
    void serverErrorIsRethrown() throws Exception {
        when(client.getCommonResponse(any(CommonRequest.class)))
                .thenThrow(new ServerException("Throttling.User", "Request was denied due to user flow control."));

        assertThatThrownBy(() -> refresher.refresh(URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Throttling.User");
    }
}
