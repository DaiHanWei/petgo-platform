package com.tailtopia.admin.pin.dto;

import java.util.List;

/**
 * 话题抽屉「话题页顶置」区块（V1.4.2 social-topic · Story 6.2 · AC8 / UI F4 / F5）。
 *
 * @param active  当前生效的一条，无 = {@code null}
 * @param pending 接下来最多 {@code 3} 条排期中，按开始时间升序
 */
public record TopicPinPreview(PinRow active, List<PinRow> pending) {
}
