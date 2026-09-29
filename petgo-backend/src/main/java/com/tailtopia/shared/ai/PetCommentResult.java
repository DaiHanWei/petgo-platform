package com.tailtopia.shared.ai;

/**
 * 看图写评论的结果：要么给出评论 {@code comment}，要么 {@code skip=true} 并给出原因。
 *
 * @param skip       true = 不宜评论（非宠物图 / 看不清 / 悲伤内容等）
 * @param comment    评论正文（skip=false 时有值）
 * @param skipReason 跳过原因（skip=true 时有值，写进留档）
 */
public record PetCommentResult(boolean skip, String comment, String skipReason) {

    public static PetCommentResult of(String comment) {
        return new PetCommentResult(false, comment, null);
    }

    public static PetCommentResult skipped(String reason) {
        return new PetCommentResult(true, null, reason);
    }
}
