package com.petgo.account.web;

/**
 * 账号注销请求（Story 7.3）。双重确认第二步：要求客户端回传确认短语（与前端高危确认一致），
 * 缺失/不匹配 → 422。防误触发不可逆删除。
 */
public record DeleteAccountRequest(String confirmation) {

    /** 约定确认短语（前端二次确认输入/高危按钮回传）。 */
    public static final String CONFIRM_PHRASE = "确认注销";

    public boolean confirmed() {
        return CONFIRM_PHRASE.equals(confirmation);
    }
}
