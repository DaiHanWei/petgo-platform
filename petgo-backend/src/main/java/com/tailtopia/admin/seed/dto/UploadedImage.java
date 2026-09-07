package com.tailtopia.admin.seed.dto;

/**
 * 后台上传一张图之后返回给页面的东西（V1.1.6 Story 12.2 · AC2/AC3）。
 *
 * @param url     公开桶 URL；页面把它按顺序拼进隐藏字段，**顺序就是首图顺序**
 * @param w       原始宽（0 = 测不出来）
 * @param h       原始高（0 = 测不出来）
 * @param warning   裁切预判文案；<b>无警告时为 {@code null}</b>。
 *                  🛡 已在服务端按请求语言本地化 —— 裁切量的算法只有一份
 *                  （{@code ImageRatioAdvisor}），不在前端重算一遍
 * @param objectKey 对象存储 key（Story 13.2 加）。批次素材要留着它才能将来回收；
 *                  ⚠️ 它本来就内嵌在 {@code url} 里，单列出来不构成额外泄漏
 * @param sizeBytes 字节数（Story 13.2 加）。单批总量上限按它累计
 */
public record UploadedImage(String url, int w, int h, String warning, String objectKey,
        long sizeBytes) {
}
