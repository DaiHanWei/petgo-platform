package com.tailtopia.shared.media.dto;

import java.util.Map;

/**
 * 预签名上传票据（返回给客户端直传 OSS 用）。Jackson NON_NULL：null 字段省略。
 *
 * <p>客户端用 {@code method}（PUT）把字节发到 {@code uploadUrl}，并**原样带上 {@code headers}**
 * （至少含签入的 {@code Content-Type}；公开域还含 {@code x-oss-object-acl:public-read}）。
 * 漏发/改动这些头会导致 OSS {@code SignatureDoesNotMatch}。真 AccessKey 始终只在后端，
 * 客户端只拿到这张限定对象 + 限定头 + 短 TTL 的票据。
 *
 * @param uploadUrl  预签名上传 URL（含 Expires/Signature）
 * @param objectKey  服务端生成的不可枚举对象 key（收窄在该用户该前缀下）
 * @param method     HTTP 方法（固定 {@code PUT}）
 * @param headers    客户端 PUT 必须原样携带的请求头
 * @param publicUrl  公开域对外 URL（私密域为 null，只能后续走读签名 URL）
 */
public record UploadUrlResponse(
        String uploadUrl,
        String objectKey,
        String method,
        Map<String, String> headers,
        String publicUrl) {
}
