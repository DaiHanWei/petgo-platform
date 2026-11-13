package com.tailtopia.content.dto;

/**
 * 评论定位（修复线：通知点开「定位到这条评论」）。
 *
 * <p>{@code thread} 是目标评论所在的<b>一级评论</b>，形状与评论列表的一项完全相同：
 * <ul>
 *   <li>目标本身是一级评论 → {@code thread.replies} 照常是前 3 条回复；</li>
 *   <li>目标是二级回复 → {@code thread.replies} <b>只放目标那一条</b>（它很可能不在前 3 条里），
 *       {@code thread.replyCount} 仍是总数，「查看全部 X 条回复」照常可展开。</li>
 * </ul>
 * App 把 {@code thread} 临时置顶到评论区最上方，并高亮 {@code targetId}。
 *
 * @param postId   所属帖子 id
 * @param targetId 被定位的评论 id（可能是一级，也可能是 thread 里的那条二级）
 * @param thread   目标所在的一级评论
 */
public record CommentAnchorResponse(long postId, long targetId, CommentResponse thread) {
}
