package com.tailtopia.content.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.dto.PostMedia;

/** 测试夹具（专题线 video Story 2.1）：不关心视频地址的单测用 —— 一律按图文原样投影。 */
public final class PostMediaFixtures {

    private PostMediaFixtures() {
    }

    public static PostMediaResolver passthrough() {
        PostMediaResolver r = mock(PostMediaResolver.class);
        when(r.resolve(any(), any())).thenAnswer(inv -> PostMedia.image(inv.<ContentPost>getArgument(0)));
        return r;
    }
}
