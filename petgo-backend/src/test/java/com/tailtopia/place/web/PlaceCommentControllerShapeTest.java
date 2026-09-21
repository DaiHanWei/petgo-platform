package com.tailtopia.place.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.place.dto.PlaceCommentCreateRequest;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * L0（纯反射，无 Spring/DB）：**场所评论只有一级，服务端没有任何回复入口**
 * （V1.3.0 batch-b1 Story 1.7 · AC2）。
 *
 * <h2>为什么值得为这条写测试</h2>
 * 「不能盖楼」在 PRD 里是一句话，在实现上是三层：
 * 表里没有 {@code parent_id}、请求体里没有 {@code parentId}、端点里没有 {@code /replies}。
 * 前两层有 schema 兜着，第三层没有 —— 加一个 {@code @PostMapping("…/replies")} 是三行代码，
 * 评审时看起来像是在补功能。而 PRD ③ 明确排除盖楼（攻略提示性质，无对话需求）。
 *
 * <p>⚠️ 真要开放回复，**先回 PRD / 决策日志改口径**，然后才是删这条测试。
 */
class PlaceCommentControllerShapeTest {

    @Test
    void noReplyEndpointExists() {
        for (Method m : PlaceCommentController.class.getDeclaredMethods()) {
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (post == null) {
                continue;
            }
            assertThat(Arrays.asList(post.value()))
                    .as("PlaceCommentController.%s() 暴露了回复端点 —— 场所评论只有一级（AC2）",
                            m.getName())
                    .noneMatch(path -> path.contains("replies") || path.contains("reply"));
        }
    }

    /** 请求体里也不许出现 parentId —— 有它就等于"端点差一行就能盖楼"。 */
    @Test
    void createRequestHasNoParentId() {
        assertThat(Arrays.stream(PlaceCommentCreateRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .as("🔴 场所评论没有父级：AD-8 的独立建表理由之一就是「不能回复」不该靠代码自觉维持")
                .doesNotContain("parentId", "parentCommentId", "replyToId");
    }

    /** 实体里同样没有 parentId（表结构这一层）。 */
    @Test
    void entityHasNoParentIdField() {
        assertThat(Arrays.stream(com.tailtopia.place.domain.PlaceComment.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("parentId");
    }

    /**
     * 🔴 删除端点必须在**这个**类里，不能被搬进 {@code PlaceController}：
     * 那个类有一条反射测试钉着「一个 PUT/PATCH/DELETE 都不许有」（用户不可修改/删除场所）。
     * 搬过去会让那条测试变红，而它守的是另一件事。
     */
    @Test
    void deleteEndpointLivesHereNotInPlaceController() {
        assertThat(Arrays.stream(PlaceCommentController.class.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(
                        org.springframework.web.bind.annotation.DeleteMapping.class)))
                .as("AC7 的删除端点应当在 PlaceCommentController 上")
                .isTrue();
    }
}
