package com.tailtopia.growth.service;

/**
 * 扣回结果（V1.4.2 exp-reverse AD-3）。{@code amount} 是实扣额，可能因用户累计不足而小于请求额；非 {@code DONE} 时为 0。
 */
public record ReverseOutcome(Status status, int amount, int levelBefore, int levelAfter) {

    public enum Status { DONE, ALREADY_REVERSED, NOTHING_TO_REVERSE }

    public static ReverseOutcome done(int amount, int levelBefore, int levelAfter) {
        return new ReverseOutcome(Status.DONE, amount, levelBefore, levelAfter);
    }

    public static ReverseOutcome alreadyReversed() {
        return new ReverseOutcome(Status.ALREADY_REVERSED, 0, 0, 0);
    }

    public static ReverseOutcome nothingToReverse() {
        return new ReverseOutcome(Status.NOTHING_TO_REVERSE, 0, 0, 0);
    }

    public boolean done() {
        return status == Status.DONE;
    }
}
