package com.tailtopia.account.web;

/**
 * 账号注销请求（Story 7.3）。双重确认第二步：客户端回传确认短语 {@code confirmation}（与前端高危确认输入一致），
 * 须等于固定英文短语 {@link #CONFIRM_PHRASE}（"DELETE"，locale 无关），缺失/不匹配 → 422。防误触发不可逆删除。
 */
public record DeleteAccountRequest(String confirmation) {

    /** 约定确认短语（前端要求用户输入 "DELETE" 后回传）。须与前端 delete_account_page 常量一致。 */
    public static final String CONFIRM_PHRASE = "DELETE";

    public boolean confirmed() {
        return CONFIRM_PHRASE.equals(confirmation);
    }
}
