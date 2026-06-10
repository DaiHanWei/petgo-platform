package com.tailtopia.shared.ai;

import java.util.List;

/**
 * Gemini 分诊调用抽象（Story 4.1，{@code shared/ai}）。Developer API、模型 gemini-2.5-flash、
 * <b>签名 URL 直拉私密图</b>（不三角绕行、不经后端下载再上传）；结构化输出约束模型回绿/黄/红 JSON。
 *
 * <p>接口抽象保留以便未来迁 Vertex（架构 G-3）。实现两态：{@code GeminiDeveloperApiClient}（live）
 * 与 {@code StubGeminiClient}（stub，L0/L1 免凭证验状态机）。
 *
 * <p>失败语义：超时 / 非 2xx / 解析失败抛 {@link GeminiException}（可重试），交 triage DB 状态机重试 ≤3。
 */
public interface GeminiClient {

    /**
     * 分析症状文字 + 私密图签名 URL，返回结构化分诊结果。
     *
     * @param symptomText     症状文字（健康数据，调用方负责不落日志）
     * @param signedImageUrls 私密桶图的短 TTL 签名 URL 列表（≤3，可空）
     * @return 结构化解析结果
     * @throws GeminiException 调用失败（超时 / 非 2xx / 响应不可解析），可重试
     */
    GeminiTriageResult analyze(String symptomText, List<String> signedImageUrls);
}
