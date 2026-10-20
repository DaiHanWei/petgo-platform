package com.tailtopia.notify.port;

import java.time.Instant;
import java.util.Map;

/**
 * 通知列表取「这一条收礼通知送的是什么」的读端口（V1.4.0 gift L2 第二轮 · PRD「XX 给你的 Mochi 送了 Tulang ×5」·
 * 架构 P-4：用礼物名、不用 emoji）。<b>提供方无关</b>：notify 只认本接口，实现由送礼模块给出。
 *
 * <p>只问「单个可显示送礼人」的条目（多人合并照稿 G1 说「Andi dan 2 lainnya mengirim hadiah」，不列礼物）。
 * 一页通知<b>一次</b>批量调用（AD-11 无 N+1）。缺 bean（切片测试）或异常时按旧的「mengirim hadiah」文案渲染。
 */
public interface NotificationGiftLines {

    /**
     * 一条收礼通知的查询条件：该送礼人在本条通知合并期间（{@code since} 起）送给该宠物、已送达的订单。
     *
     * @param since 送礼人并入本条通知的时刻（{@code notification_actors.acted_at}）减去容差 —— 送达时刻略早于它
     * @param until 本条通知最后一次刷新的时刻（{@code notifications.created_at}，每次有人送礼都会 touch 刷新）。
     *              它之后送达的礼必然落在另一条通知上（本条已读后再送 → 新建 N2），不能再算进本条
     */
    record Query(long petId, long senderId, Instant since, Instant until) {
    }

    /**
     * 本条通知送的礼物：同一种礼物（含同一人未读期间多次送同一种）→ 数量累加；送了不止一种 → 不给（调用方回落通用文案）。
     * 名称取订单快照（EN / ID），由调用方按收件人语言选。
     */
    record GiftLine(String nameEn, String nameId, int quantity) {
    }

    /**
     * @param queries 通知 id → 查询条件；空入参返回空 Map
     * @return 通知 id → 礼物行；查不到 / 混合礼物的 id 不出现
     */
    Map<Long, GiftLine> linesOf(Map<Long, Query> queries);
}
