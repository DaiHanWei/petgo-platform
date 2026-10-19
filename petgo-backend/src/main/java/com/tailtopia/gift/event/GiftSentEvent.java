package com.tailtopia.gift.event;

/**
 * 礼物送达领域事件（V1.4.0，过去式）。
 *
 * <p>本类由 Story 0.1 地基定义：发布方 ③（{@code feat/1.4.0-gift}）、消费方 ①（{@code feat/1.4.0-tailrent-level}，
 * 送礼人 {@code GIFT_SENT} / 收礼宠物主人 {@code GIFT_RECEIVED} 成长值）<b>只引用、不重定义、不改字段</b>
 * （字段已在 ① PRD §6.4、③ PRD §6.3 约定）。经 {@code ApplicationEventPublisher} 进程内发布。
 *
 * @param giftOrderId     送礼订单 id
 * @param senderId        送礼人 id
 * @param receiverOwnerId 收礼宠物的主人 id
 * @param petId           收礼宠物 id
 * @param totalAmount     实付总额（IDR，现金 + PawCoin 抵扣）
 */
public record GiftSentEvent(long giftOrderId, long senderId, long receiverOwnerId, long petId, long totalAmount) {
}
