package com.petgo.shared.im.web;

import com.petgo.shared.error.AppException;
import com.petgo.shared.im.TencentImClient;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 腾讯 IM 服务端回调骨架（Story 5.5）。{@code POST /im/callback}：接收 IM 消息/会话事件。
 *
 * <p>本故事仅落<b>签名/token 校验骨架</b>（非法回调拒绝）；存档触发（IM→OSS）在 Story 5.6 接，
 * 封禁中断在 5.7。回调内网/白名单 + token 校验，绝不信任未校验来源。
 */
@RestController
public class ImCallbackController {

    private final TencentImClient imClient;

    public ImCallbackController(TencentImClient imClient) {
        this.imClient = imClient;
    }

    @PostMapping("/im/callback")
    public Map<String, Object> callback(@RequestParam(value = "token", required = false) String token,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!imClient.verifyCallback(token)) {
            throw AppException.forbidden("非法回调");
        }
        // 事件分发占位：存档(5.6)/中断(5.7) 在此接。腾讯 IM 期望 ActionStatus=OK 应答。
        return Map.of("ActionStatus", "OK", "ErrorCode", 0);
    }
}
