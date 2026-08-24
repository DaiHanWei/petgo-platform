package com.tailtopia.admin.virtual.dto;

/**
 * 「发布账号」选择器里的一项（V1.1.6 Story 12.1 · AC6）。
 *
 * <p>🔴 <b>三处发布入口共用同一份数据与同一个 Thymeleaf 片段</b>
 * （12-2 单条 / 13-x 批量 / 13-5 定时）。以运营真实账号误发的后果**不可撤回** ——
 * 内容会出现在那个真人的个人主页并推送给他的粉丝，事后删除也已经推送过了。
 * 所以防呆必须是一份实现，而不是每个页面各画一遍下拉框。
 *
 * @param real     是否运营真实账号。模板据此① 分组② 加显著标记与后果说明
 *                 ③ **仅这一类**弹二次确认（虚拟账号不弹，避免拖慢常用路径）
 * @param disabled 账号已停用/被封 ⇒ 列出来但不可选（不列出来运营会以为号丢了）
 */
public record PublishIdentityOption(long userId, String nickname, boolean real, boolean disabled,
        /**
         * 该账号的「账号物种定位」（V1.1.6 Story 14.1 · AC4）。
         *
         * <p>🔴 <b>运营真实账号恒为 {@code null}</b> —— 它们没有这个字段，
         * 物种由作者宠物档案推导。单条发布页的物种下拉据此默认留空。
         */
        String accountSpecies) {

    /** 兼容旧调用（不关心物种的地方）。 */
    public PublishIdentityOption(long userId, String nickname, boolean real, boolean disabled) {
        this(userId, nickname, real, disabled, null);
    }
}
