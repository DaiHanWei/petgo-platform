package com.tailtopia.shared.ai;

/**
 * 打桩的看图写评论（{@code petgo.ai.gemini.mode=stub}，默认）。返回固定文案，供本地 / 测试跑通流程。
 *
 * <p>🔴 {@link #live()} 恒为 false：自动评论定时任务见到 stub 会整轮跳过，固定文案绝不会发到真实帖子下。
 */
public class StubPetCommentGenerator implements PetCommentGenerator {

    @Override
    public PetCommentResult generate(String imageUrl, String postText, String species, String petName) {
        return PetCommentResult.of("Lucu banget! 😍 Namanya siapa?");
    }

    @Override
    public boolean live() {
        return false;
    }

    @Override
    public String model() {
        return "stub";
    }
}
