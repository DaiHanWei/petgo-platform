package com.tailtopia.place.event;

import java.util.List;

/**
 * 补充的场所照片已提交（V1.3.0 batch-b1 Story 1.9 · AC3）。
 * 由 {@code PlacePhotoService.contribute} 在事务内发出，监听侧在 AFTER_COMMIT 后异步送审。
 *
 * <p>🔴 **一次提交一个事件、批量送审**，不是每张图一个事件：
 * 一次补 5 张就打 5 次三方 = 5 倍配额与 5 倍延迟，而审核接口本来就收图片数组。
 *
 * <p>⚠️ 两个列表**下标一一对应**（{@code photoIds[i]} 是 {@code urls[i]} 的行）。
 * 判定是整批一个结论（三方接口就是这么回的），所以两者的对应关系目前只用于日志定位；
 * 哪天改成逐张判定时，这个对应关系就是必须的 —— 不要把它拆散。
 *
 * <p>**不带上传者 id**：审核不需要它，带上只会让它更容易被顺手写进日志（NFR：严禁记录 PII）。
 */
public record PlacePhotosSubmittedEvent(List<Long> photoIds, List<String> urls) {
}
