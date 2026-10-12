package com.tailtopia.profile.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.PetType;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * L0 守卫（V1.3.4 Story 4.1 复审）：分享请求 {@code collectionCodes} 的上限必须 ≥ 最长里程碑清单。
 *
 * <p>🔴 App 提交的是「全部已完成 code」；清单扩容而上限没跟上时，完成数超过上限的宠物分享会被 400，
 * App 静默退化成不带链接 —— 不报错、没人发现。
 */
class MilestoneShareRequestLimitTest {

    @Test
    void collectionCodesLimitCoversTheLongestCatalog() throws NoSuchFieldException {
        int longest = Arrays.stream(PetType.values())
                .mapToInt(t -> MilestoneCatalog.forType(t).size()).max().orElseThrow();
        var component = Arrays.stream(MilestoneShareRequest.class.getRecordComponents())
                .filter(c -> c.getName().equals("collectionCodes")).findFirst().orElseThrow();
        Size size = component.getAccessor().getDeclaringClass()
                .getDeclaredField("collectionCodes").getAnnotation(Size.class);
        if (size == null) {
            size = component.getAnnotation(Size.class);
        }

        assertThat(longest).isEqualTo(33);
        assertThat(size).isNotNull();
        assertThat(size.max()).isGreaterThanOrEqualTo(longest);
    }
}
