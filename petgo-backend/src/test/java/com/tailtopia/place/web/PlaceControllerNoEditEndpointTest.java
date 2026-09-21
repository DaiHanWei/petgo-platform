package com.tailtopia.place.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * L0（纯反射，无 Spring/DB）：**服务端不提供任何场所编辑端点**
 * （V1.3.0 batch-b1 Story 1.3 · AC5）。
 *
 * <h2>为什么值得为这条写一个测试</h2>
 * 「用户不可修改场所」（2026-09-15 拍板）在产品上只有一层意思，但在实现上有三层：
 * 前端前置告知、<b>服务端硬拒</b>、纠错走后台 AB-17A。
 * 只做前端那层等于留了个半开的口子 —— 有人直接调接口就改了。
 * 而"没有写某个端点"这件事，**没有测试的话没人会注意到它被加上了**：
 * 加一个 {@code @PatchMapping} 是三行代码，评审时看起来像是在补功能。
 *
 * <p>既有先例：宠物档案的「物种创建后不可修改」也是前端置灰 + 服务端硬拒两层。
 *
 * <p>⚠️ 真要开放编辑能力时，**先回决策日志改口径**，然后才是删这条测试 ——
 * 顺序反了的话，改的就是一条没人讨论过的产品行为。
 */
class PlaceControllerNoEditEndpointTest {

    private static final List<Class<? extends java.lang.annotation.Annotation>> WRITE_ANNOTATIONS =
            List.of(PutMapping.class, PatchMapping.class, DeleteMapping.class);

    @Test
    void placeControllerHasNoPutPatchOrDeleteMapping() {
        for (Method m : PlaceController.class.getDeclaredMethods()) {
            for (var ann : WRITE_ANNOTATIONS) {
                assertThat(m.isAnnotationPresent(ann))
                        .as("PlaceController.%s() 带了 @%s —— 用户不可修改/删除场所（AC5），"
                                + "纠错走后台 AB-17A。要开放先回决策日志改口径。",
                                m.getName(), ann.getSimpleName())
                        .isFalse();
            }
        }
    }

    /** 也不许用 {@code @RequestMapping(method = PUT/PATCH/DELETE)} 绕过去。 */
    @Test
    void placeControllerHasNoWriteMethodViaGenericRequestMapping() {
        for (Method m : PlaceController.class.getDeclaredMethods()) {
            RequestMapping rm = m.getAnnotation(RequestMapping.class);
            if (rm == null) {
                continue;
            }
            assertThat(Arrays.asList(rm.method()))
                    .as("PlaceController.%s() 经 @RequestMapping 暴露了写方法（AC5）", m.getName())
                    .doesNotContain(RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);
        }
    }

    /** 类级 {@code @RequestMapping} 也不许限定成写方法（那会让所有方法都变成写端点）。 */
    @Test
    void classLevelMappingDoesNotDeclareWriteMethods() {
        RequestMapping rm = PlaceController.class.getAnnotation(RequestMapping.class);
        assertThat(rm).isNotNull();
        assertThat(Arrays.asList(rm.method()))
                .doesNotContain(RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);
    }

    /**
     * 服务层同样没有编辑入口 —— 端点没有但 service 有一个 {@code update(...)} 的话，
     * 下一个人加端点时会以为"后端本来就支持，只是没暴露"。
     */
    @Test
    void placeServiceExposesNoMutatingMethodBeyondMark() {
        List<String> suspicious = Arrays.stream(
                        com.tailtopia.place.service.PlaceService.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .filter(n -> n.startsWith("update") || n.startsWith("edit") || n.startsWith("patch")
                        || n.startsWith("delete") || n.startsWith("remove"))
                .toList();

        assertThat(suspicious)
                .as("PlaceService 出现了修改/删除方法：%s —— 用户不可编辑场所（AC5）", suspicious)
                .isEmpty();
    }
}
