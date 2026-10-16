package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.MediaType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.VideoPayload;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：专题线 video Story 1.3 —— AD-5 校验表逐行（等级 / 形状 / 归属 / HEAD）。 */
class VideoPostValidatorTest {

    private static final long UID = 9L;
    private static final String KEY = "video/9/abcdefghijklmnopqrstuv.mp4";
    private static final String COVER = "https://cdn.test/public/9/cover.jpg";

    private VipLevelService levels;
    private AliyunOssClient oss;
    private MediaProperties props;
    private VideoPostValidator validator;

    @BeforeEach
    void setUp() {
        levels = mock(VipLevelService.class);
        oss = mock(AliyunOssClient.class);
        props = new MediaProperties();
        props.getOss().setCdnBaseUrl("https://cdn.test");
        props.getOss().setPublicBucket("bkt");
        validator = new VideoPostValidator(levels, oss, props);
        when(levels.canPublishVideo(UID)).thenReturn(true);
        // 封面反解走真实逻辑。
        when(oss.objectKeyOfPublicUrl(anyString())).thenAnswer(inv ->
                new AliyunOssClient(props).objectKeyOfPublicUrl(inv.getArgument(0)));
        when(oss.headObject("bkt", KEY)).thenReturn(Optional.of(new AliyunOssClient.ObjectHead("video/mp4", 25_000_000L)));
    }

    private static ContentPostCreateRequest req(ContentType type, Long petId, LocalDate date, String checkin,
            List<String> images, List<Double> crops, VideoPayload v) {
        return new ContentPostCreateRequest(type, petId, "t", images, date, null, null, null, checkin, crops,
                MediaType.VIDEO, v);
    }

    private static ContentPostCreateRequest ok(VideoPayload v) {
        return req(ContentType.DAILY, null, null, null, List.of(COVER), null, v);
    }

    private static VideoPayload v(String key, Integer ms, Integer w, Integer h) {
        return new VideoPayload(key, ms, w, h);
    }

    private void expect(ContentPostCreateRequest r, URI type) {
        assertThatThrownBy(() -> validator.verifyObjects(UID, r))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getType()).isEqualTo(type));
    }

    @Test
    void happyPathReturnsHeadSize() {
        var r = validator.verifyObjects(UID, ok(v(KEY, 75_000, 720, 1280)));
        assertThat(r.objectKey()).isEqualTo(KEY);
        assertThat(r.sizeBytes()).isEqualTo(25_000_000L);
    }

    @Test
    void levelBelow10IsForbiddenBeforeAnyOssCall() {
        when(levels.canPublishVideo(UID)).thenReturn(false);
        expect(ok(v(KEY, 5000, 720, 1280)), ErrorTypes.VIDEO_PERMISSION_DENIED);
        verify(oss, never()).headObject(anyString(), anyString());
    }

    @Test
    void mixedMedia() {
        expect(req(ContentType.DAILY, null, null, null, List.of(COVER, COVER), null, v(KEY, 5000, 720, 1280)),
                ErrorTypes.VIDEO_MIXED_MEDIA);
        expect(req(ContentType.DAILY, null, null, null, List.of(), null, v(KEY, 5000, 720, 1280)),
                ErrorTypes.VIDEO_MIXED_MEDIA);
        expect(req(ContentType.DAILY, null, null, null, List.of(COVER), null, null), ErrorTypes.VIDEO_MIXED_MEDIA);
        var imageWithVideo = new ContentPostCreateRequest(ContentType.DAILY, null, "t", List.of(COVER), null, null,
                null, null, null, null, null, v(KEY, 5000, 720, 1280));
        assertThatThrownBy(() -> validator.validateShape(imageWithVideo))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.VIDEO_MIXED_MEDIA));
    }

    @Test
    void typeAndAttachmentsAreValidationErrors() {
        VideoPayload good = v(KEY, 5000, 720, 1280);
        expect(req(ContentType.GROWTH_MOMENT, null, null, null, List.of(COVER), null, good), ErrorTypes.VALIDATION);
        expect(req(ContentType.DAILY, 3L, null, null, List.of(COVER), null, good), ErrorTypes.VALIDATION);
        expect(req(ContentType.DAILY, null, LocalDate.of(2026, 1, 1), null, List.of(COVER), null, good),
                ErrorTypes.VALIDATION);
        expect(req(ContentType.DAILY, null, null, "tok", List.of(COVER), null, good), ErrorTypes.VALIDATION);
        expect(req(ContentType.DAILY, null, null, null, List.of(COVER), java.util.Arrays.asList((Double) null), good),
                ErrorTypes.VALIDATION);
        // 科普允许。
        validator.verifyObjects(UID, req(ContentType.KNOWLEDGE, null, null, null, List.of(COVER), null, good));
    }

    @Test
    void durationAndDimensions() {
        expect(ok(v(KEY, 2999, 720, 1280)), ErrorTypes.VIDEO_OBJECT_INVALID);
        expect(ok(v(KEY, 90_001, 720, 1280)), ErrorTypes.VIDEO_OBJECT_INVALID);
        expect(ok(v(KEY, null, 720, 1280)), ErrorTypes.VIDEO_OBJECT_INVALID);
        expect(ok(v(KEY, 5000, 0, 1280)), ErrorTypes.VIDEO_OBJECT_INVALID);
        expect(ok(v(KEY, 5000, 721, 1280)), ErrorTypes.VIDEO_OBJECT_INVALID);
        expect(ok(v(KEY, 5000, 1280, 721)), ErrorTypes.VIDEO_OBJECT_INVALID);
        // 边界内：3000 / 90000 / 短边 720。
        validator.verifyObjects(UID, ok(v(KEY, 3000, 1280, 720)));
        validator.verifyObjects(UID, ok(v(KEY, 90_000, 720, 720)));
    }

    @Test
    void videoKeyMustBeOwnAndWellFormed() {
        for (String bad : List.of("video/8/abcdefghijklmnopqrstuv.mp4", "public/9/x.mp4", "video/9/x.mov",
                "video/9/../8/x.mp4", "video/9/a/b.mp4", "video/9/.mp4", "")) {
            expect(ok(v(bad, 5000, 720, 1280)), ErrorTypes.VIDEO_OBJECT_INVALID);
        }
        verify(oss, never()).headObject(anyString(), anyString());
    }

    @Test
    void keyPrefixIsHonoured() {
        props.getOss().setKeyPrefix("stag/");
        assertThat(validator.ownsVideoKey(UID, "stag/video/9/k.mp4")).isTrue();
        assertThat(validator.ownsVideoKey(UID, KEY)).isFalse();
    }

    @Test
    void coverMustBeOwnPublicObject() {
        for (String bad : List.of("https://evil.example/public/9/c.jpg", "https://cdn.test/public/8/c.jpg",
                "https://cdn.test/private/9/c.jpg")) {
            expect(req(ContentType.DAILY, null, null, null, List.of(bad), null, v(KEY, 5000, 720, 1280)),
                    ErrorTypes.VIDEO_OBJECT_INVALID);
        }
        verify(oss, never()).headObject(anyString(), anyString());
    }

    @Test
    void headMissingWrongTypeOrTooLarge() {
        when(oss.headObject("bkt", KEY)).thenReturn(Optional.empty());
        expect(ok(v(KEY, 5000, 720, 1280)), ErrorTypes.VIDEO_OBJECT_INVALID);
        when(oss.headObject("bkt", KEY)).thenReturn(Optional.of(new AliyunOssClient.ObjectHead("video/quicktime", 10)));
        expect(ok(v(KEY, 5000, 720, 1280)), ErrorTypes.VIDEO_OBJECT_INVALID);
        when(oss.headObject("bkt", KEY)).thenReturn(Optional.of(new AliyunOssClient.ObjectHead("video/mp4", 104_857_601L)));
        expect(ok(v(KEY, 5000, 720, 1280)), ErrorTypes.VIDEO_OBJECT_INVALID);
        when(oss.headObject("bkt", KEY)).thenReturn(Optional.of(new AliyunOssClient.ObjectHead("Video/MP4", 104_857_600L)));
        assertThat(validator.verifyObjects(UID, ok(v(KEY, 5000, 720, 1280))).sizeBytes()).isEqualTo(104_857_600L);
    }
}
