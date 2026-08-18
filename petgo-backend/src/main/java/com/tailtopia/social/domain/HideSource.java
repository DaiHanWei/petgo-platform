package com.tailtopia.social.domain;

/**
 * 隐藏关系的来源（Story 1.1，FR-94 / FR-58）。落库 varchar + UPPER_SNAKE。
 *
 * <p><b>两个来源的差别只有一处：主页访问。</b>其余五处生效点（Feed / 运营干预位 / 评论 R1·R2 /
 * 搜索话题列表 / 互动通知抑制）**一律不区分 source**。
 *
 * <ul>
 *   <li>{@link #BLOCK}：用户主动拉黑。<b>可解除</b>（黑名单页）、<b>禁止进入对方主页</b>、进黑名单页。</li>
 *   <li>{@link #REPORT}：用户提交账号举报后由系统自动建立。<b>永久不可解除</b>、
 *       <b>不禁止进入对方主页</b>（这是 FR-58 闭环需要的——「已举报」状态与重复举报入口都靠它）、
 *       不进黑名单页。</li>
 * </ul>
 *
 * <p>长度 &gt;1 规避 Hibernate6 {@code CHAR(1)} → {@code validate} 全红的历史坑。
 */
public enum HideSource {
    /** 用户主动拉黑：可解除，禁止进入对方主页。 */
    BLOCK,
    /** 举报自动产生：永不删除，不禁止进入对方主页。 */
    REPORT
}
