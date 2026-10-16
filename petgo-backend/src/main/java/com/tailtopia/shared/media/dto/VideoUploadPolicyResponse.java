package com.tailtopia.shared.media.dto;

import java.util.Map;

/**
 * 视频 PostObject 上传策略（{@code POST /api/v1/media/video-upload-policy}，专题线 video Story 1.2 · AD-3）。
 *
 * <p>客户端向 {@code host} 发 multipart/form-data POST：先按顺序放 {@code fields} 里的全部字段，
 * <b>{@code file} 必须是最后一个字段</b>。策略已锁死完整 key、大小、类型、ACL 与禁止覆盖，改任何一项都会被 OSS 拒绝。
 *
 * @param host   上传地址（{@code https://<bucket>.<endpoint 主机>}）
 * @param key    服务端生成的对象 key（{@code <keyPrefix>video/<userId>/<随机>.mp4}）
 * @param fields 表单字段（有序），含 policy 与 signature
 */
public record VideoUploadPolicyResponse(String host, String key, Map<String, String> fields) {
}
