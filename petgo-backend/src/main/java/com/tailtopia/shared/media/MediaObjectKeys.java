package com.tailtopia.shared.media;

import com.tailtopia.shared.error.AppException;
import java.util.List;

/**
 * 客户端提交上来的 objectKey 的**归属校验**。
 *
 * <h2>为什么需要这个（D-10，2026-09-02 stag，P0）</h2>
 * 直传拿到的 key 由客户端回传，业务侧再存进自己的表。此前**没有任何一处校验它是不是真的**：
 * 退货凭证那条链路上，App 端压根没调相册，只往列表里塞了字面量
 * {@code return-evidence-1/2/…}，服务端照单全收、原样入库 ——
 * 运营在退货审核页无图可看，而页面文案还写着「拍到封口和保质期标签，这是质检要看的」。
 *
 * <p>🔴 校验的依据是 key 的生成规则本身（见 {@link PresignedUploadService#issue}）：
 * <pre>&lt;keyPrefix&gt;&lt;scope&gt;/&lt;userId&gt;/&lt;randomToken&gt;.&lt;ext&gt;</pre>
 * 前缀里**带着 userId**，所以一条前缀判定同时挡住两件事：
 * <ul>
 *   <li>编造的字符串（`return-evidence-1` 这种压根不成形的）；</li>
 *   <li><b>别人的对象</b> —— 拿到他人 key 也塞不进自己的单子。</li>
 * </ul>
 *
 * <p>⚠️ <b>这不是「对象真的存在」的证明</b>：那要打一次 OSS HEAD，
 * 会把一次本地校验变成一次外部往返，且预签名成功≠客户端真的 PUT 完。
 * 本类只保证「这个 key 的形状与归属是对的」—— 挡住的是伪造与越权，
 * 「签了没传」留给后台看图时自然暴露（缺图比看到别人的图安全得多）。
 *
 * <p>⚠️ 同样的洞在**工单附件**（{@code attachmentObjectKeys}）上也存在，本次未一并改 ——
 * 那条链路不在本轮测试范围内，改动应连同它自己的用例一起做。
 */
public final class MediaObjectKeys {

    private MediaObjectKeys() {
    }

    /** 该 key 是否是这个用户在该隐私域下直传产生的。 */
    public static boolean belongsTo(MediaProperties props, MediaScope scope, long userId,
            String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        String expected = props.getOss().normalizedKeyPrefix() + scope.prefix() + "/" + userId + "/";
        // 必须是**前缀 + 还有内容**：只等于前缀本身说明没有对象名。
        return objectKey.startsWith(expected) && objectKey.length() > expected.length();
    }

    /**
     * 逐个校验，任一不合格即拒。
     *
     * @param what 用于错误文案的字段名（如「凭证图」）
     */
    public static void requireAllOwned(MediaProperties props, MediaScope scope, long userId,
            List<String> objectKeys, String what) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        for (String k : objectKeys) {
            if (!belongsTo(props, scope, userId, k)) {
                // ⚠️ 不把用户传的原串回显进错误信息 —— 那等于把一个可控字符串送回响应体。
                throw AppException.validation(what + "无效，请重新上传");
            }
        }
    }
}
