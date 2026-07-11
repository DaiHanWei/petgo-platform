package com.tailtopia.pay.domain;

/**
 * 分录借贷方向（Story 1.2，落库 varchar(8) + CHECK）。一组分录 Σ(DEBIT)==Σ(CREDIT) 恒平衡。
 */
public enum LedgerDirection {
    DEBIT,
    CREDIT
}
