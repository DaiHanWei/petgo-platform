package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L0：推荐序缓存键空间（Story 16.1 · AC4）—— 登录/游客分流 + 🔴 客户端串的键注入面。 */
class FeedRankCacheKeyTest {

    @Test
    void loggedInUserIsNotGuest() {
        FeedRankCacheKey k = FeedRankCacheKey.forUser(42L);
        assertThat(k.namespace()).isEqualTo("u:42");
        assertThat(k.guest()).isFalse();
    }

    @Test
    void guestKeyIsMarkedGuest() {
        FeedRankCacheKey k = FeedRankCacheKey.forGuest("abc-123_XYZ");
        assertThat(k.namespace()).isEqualTo("a:abc-123_XYZ");
        assertThat(k.guest()).isTrue();
    }

    /** 🔴 冒号能伪造别人的键空间 —— 必须被剥掉。 */
    @Test
    void colonInAnonIdCannotForgeAnotherKeyspace() {
        FeedRankCacheKey k = FeedRankCacheKey.forGuest("x:u:1");
        assertThat(k.namespace()).doesNotContain(":u:");
        assertThat(k.namespace()).isEqualTo("a:xu1");
    }

    @Test
    void overlongAnonIdIsTruncated() {
        String longId = "a".repeat(500);
        FeedRankCacheKey k = FeedRankCacheKey.forGuest(longId);
        assertThat(k.namespace()).hasSize("a:".length() + FeedRankCacheKey.MAX_ANON_LENGTH);
    }

    /** ⚠️ 整串都是非法字符 → 落兜底键空间，而不是抛错（游客刷首页不该因此 500）。 */
    @Test
    void allIllegalCharsFallBackInsteadOfThrowing() {
        assertThat(FeedRankCacheKey.forGuest("::::").namespace()).isEqualTo("a:anon");
        assertThat(FeedRankCacheKey.forGuest(null).namespace()).isEqualTo("a:anon");
    }

    @Test
    void ofDispatchesOnViewerId() {
        assertThat(FeedRankCacheKey.of(7L, "sess").guest()).isFalse();
        assertThat(FeedRankCacheKey.of(null, "sess").guest()).isTrue();
    }
}
