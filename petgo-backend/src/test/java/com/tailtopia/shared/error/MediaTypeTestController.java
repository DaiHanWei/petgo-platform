package com.tailtopia.shared.error;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅测试用：一个 <b>只吃 JSON</b> 的端点，用来触发真实的
 * {@code HttpMediaTypeNotSupportedException}（Story 5-2 的 415 用例）。
 *
 * <p>⚠️ <b>不去改 {@code PingErrorController}</b>：那是 main 里的 dev-profile 诊断端点，
 * 为了测试给它加一个方法，等于让生产代码长出一块只有测试在用的东西。
 * 照 {@link H5BoomTestController} 的先例，桩留在 {@code src/test} 下。
 *
 * <p>405 那条用例不需要桩 —— 对 {@code PingErrorController} 的
 * {@code GET /api/v1/_ping-error} 发 POST 就会触发。
 */
@RestController
public class MediaTypeTestController {

    @PostMapping(value = "/api/v1/_media-test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String jsonOnly() {
        return "ok";
    }
}
