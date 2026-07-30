package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.LedgerAccount;
import com.tailtopia.pay.domain.LedgerDirection;

/**
 * 一条待记分录（Story 1.2）。{@code amount} 恒 > 0，借贷方向由 {@link #direction} 表达；
 * {@code userId} 仅与用户相关的科目（如 {@code FLOAT_LIABILITY}）填，供对账。
 */
public record LedgerLine(LedgerAccount account, LedgerDirection direction, long amount,
        Long userId, String refType, Long refId) {

    public static LedgerLine debit(LedgerAccount account, long amount, Long userId, String refType, Long refId) {
        return new LedgerLine(account, LedgerDirection.DEBIT, amount, userId, refType, refId);
    }

    public static LedgerLine credit(LedgerAccount account, long amount, Long userId, String refType, Long refId) {
        return new LedgerLine(account, LedgerDirection.CREDIT, amount, userId, refType, refId);
    }
}
