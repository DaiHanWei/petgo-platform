package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** L0：OG 静态图渲染为合法 PNG（headless），尺寸 1200x630（AC2 预渲染基础）。 */
class OgImageServiceTest {

    private final OgImageService service =
            new OgImageService(
                    mock(AliyunOssClient.class), mock(ProfileService.class), new MediaProperties());

    @Test
    void rendersValidPngOfExpectedSize() throws IOException {
        byte[] png = service.render("Momo", "Shiba");
        assertThat(png).isNotEmpty();
        var img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(OgImageService.WIDTH);
        assertThat(img.getHeight()).isEqualTo(OgImageService.HEIGHT);
    }

    @Test
    void handlesNullNameGracefully() {
        byte[] png = service.render(null, null);
        assertThat(png).isNotEmpty();
    }
}
