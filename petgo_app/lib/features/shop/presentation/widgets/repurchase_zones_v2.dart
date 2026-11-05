/// 复购触发卡与档案推荐区 —— **设计稿版式**（V1.4.0 · `03_screens_recommendation.md` 屏 1/2）。
///
/// ⚠️ 2026-08-28：v1 版式已整体删除，本文件是该页唯一实现（`_v2` 后缀保留以免制造纯改名 diff）。
///
/// ## 🔴 这两屏是「记录的出口」，不是「商品的入口」
///
/// 设计稿原话。它们复用既有的宠物档案与结构化健康记录，把已有数据转成购买触发点。
/// 因此触发卡长在 **Diary 时间线里**（参与排序、可被划走），不是一条横幅。
///
/// ## 🔴 触发卡：推算依据缺一不可
///
/// 「日均用量 · 剩余量 · 购买日期」三个数是用户信任这条推荐的**唯一凭据**，
/// 也是它区别于普通广告位的地方。任一算不出来 → [RepurchaseCard.hasBasis] 为 false
/// → **整卡不渲染**。与其给一条没有依据的推荐，不如不给。
///
/// （这三个数是本批新加的：服务端原先只下发商品名与剩余天数，
/// 见 `RepurchaseScanService.basisFor` 与 `RepurchaseCardView` 的追加字段。）
///
/// ## 🔴 档案推荐区：来源标签是硬要求，取不到来源的商品不能进个性化区
///
/// 设计稿要求每张个性化卡的来源标签**能指回一条具体记录（含日期）**，
/// 例如 `Gigi berkarang · 15 Agu`。
///
/// ⚠️ 当前 `RecommendationItem.reason` 给的是**规则维度**
/// （`Untuk anjing dewasa 10–25 kg` —— 年龄段/体型），**不是某条记录 + 日期**。
/// 按设计稿的规则「取不到来源的商品不能进个性化区」，本页因此**全部落到降级态
/// （MODE DASAR）**，并给出设计稿要求的脱困路径（还差几条记录）。
///
/// 这不是偷懒：把规则维度的理由挂上「来自你的记录」的标签，就是把泛推荐伪装成个性化 ——
/// 设计稿点名不允许。要开个性化区，需要推荐接口下发「命中的记录 + 日期」。
///
/// ⚠️ v1.4.2 shop Story 8.1（FR-136 / D-17）：档案推荐区 `ProfileRecoZoneV2` 已删除（lib 内无引用），
/// Toko 推荐改由 `shop_reco_grid.dart` 的 `ShopRecoGrid` 承担；本文件只剩 Diary 复购触发卡（9.4 收尾）。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/shop_tokens.dart';
import '../../../../l10n/app_localizations.dart';
import '../../data/shop_repurchase_repository.dart';
import '../../domain/shop_product.dart';
import '../../domain/shop_repurchase.dart';
import 'shop_buttons.dart';
import 'shop_decor.dart';

/// 屏 1：Diary 复购触发卡。
///
/// 墨底满宽、插在时间线流内 —— 与白底的记录行区分，但**仍在流内**（不是浮层横幅）。
class RepurchaseTriggerCardV2 extends ConsumerWidget {
  const RepurchaseTriggerCardV2({super.key, this.source = 'diary'});

  /// 埋点用：卡片出现在哪（`diary` / `home`）。
  final String source;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cards = ref.watch(repurchaseCardsProvider).maybeWhen(
          data: (v) => v,
          orElse: () => const <RepurchaseCard>[],
        );
    // 🔴 依据不全的卡**整卡不渲染**（见文件头）。
    final renderable = cards.where((c) => c.hasBasis).toList();
    if (renderable.isEmpty) return const SizedBox.shrink();
    return Column(
      children: [for (final c in renderable) _Card(card: c, source: source)],
    );
  }
}

class _Card extends ConsumerStatefulWidget {
  const _Card({required this.card, required this.source});

  final RepurchaseCard card;
  final String source;

  @override
  ConsumerState<_Card> createState() => _CardState();
}

class _CardState extends ConsumerState<_Card> {
  @override
  void initState() {
    super.initState();
    // 🔒 只带 trigger_type 与来源，不带任何 PII（宠物名/体重/克数都不上报）。
    Analytics.capture('toko_repurchase_card_shown',
        {'trigger_type': widget.card.triggerType, 'card_source': widget.source});
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final c = widget.card;
    final pet = c.petName ?? '';

    return Container(
      width: double.infinity,
      color: ShopColors.ink,
      padding: kShopEmphasisPadding,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(l10n.recoTriggerEyebrow(pet).toUpperCase(),
                    style: ShopText.badge.copyWith(
                        fontSize: 9.5, color: ShopColors.accent, letterSpacing: .8)),
              ),
              // 🔴 可解释性入口用**文字不用 icon**（合规与信任要求）——
              //    一个问号图标传达不了「这里能看到算法口径和关闭入口」。
              InkWell(
                key: ValueKey('recoWhy_${c.triggerId}'),
                onTap: () {
                  Analytics.capture('toko_repurchase_card_why_tapped');
                  // ⚠️ 可解释性页（算法口径 + 关闭入口）尚未落地 —— 见文件尾说明。
                  //    在它到位前不给假入口：这里只埋点，不导航。
                },
                child: Text(l10n.recoTriggerWhy,
                    style: ShopText.meta.copyWith(color: ShopColors.onInk45)),
              ),
            ],
          ),
          const SizedBox(height: 6),
          // 结论。🔴 给估算而非断言：`±N 天`，不写成「已经吃完了」。
          Text(
            c.isOverdue
                ? l10n.recoTriggerConclusionOverdue(pet)
                : l10n.recoTriggerConclusion(pet, c.daysLeft.abs()),
            style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w700,
                height: 1.45,
                color: ShopColors.surface),
          ),
          const SizedBox(height: 5),
          // 🔴 推算依据 —— 三个数缺一不可（此处必然齐全，卡片才会渲染到这里）。
          Text(
            l10n.recoTriggerBasis(
              c.dailyGrams!,
              c.remainingGrams!,
              _shortDate(c.purchasedOn!),
            ),
            key: ValueKey('recoBasis_${c.triggerId}'),
            style: ShopText.meta.copyWith(color: ShopColors.onInk60, height: 1.6),
          ),
          const SizedBox(height: 11),
          // 商品条
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: ShopColors.onInk07,
              borderRadius: BorderRadius.circular(ShopShape.radiusButton),
            ),
            child: Row(
              children: [
                const ShopImage(
                    url: null, size: 48, radius: ShopShape.radiusChip, onInk: true),
                const SizedBox(width: 9),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(c.productName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontSize: 11.5,
                              fontWeight: FontWeight.w600,
                              color: ShopColors.surface)),
                      // 与 Toko 首页那张是**同一份数据的两个入口**，口径必须一致：
                      // 价格取触发 SKU，null 或 0 整行不画（不编造、不显示 0）。
                      // ⚠️ 本卡是深色底（ShopColors.ink），价格不能用 accent 玫红那套浅底色。
                      if (c.hasPrice) ...[
                        const SizedBox(height: 2),
                        Text(formatIdr(c.price!),
                            key: ValueKey('recoTriggerPrice_${c.triggerId}'),
                            style: ShopText.priceRail
                                .copyWith(color: ShopColors.surface)),
                      ],
                    ],
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 11),
          Row(
            children: [
              Expanded(
                flex: 16,
                child: ShopButton(
                  key: ValueKey('recoBuyAgain_${c.triggerId}'),
                  label: l10n.tokoBuyAgain,
                  variant: ShopButtonVariant.pay,
                  onTap: () {
                    Analytics.capture('toko_repurchase_card_tapped', {
                      'trigger_type': c.triggerType,
                      'product_id': c.productToken,
                      'card_source': widget.source,
                    });
                    // 🔴 设计稿要 `Beli Lagi` 直达结算（跳过详情与购物车）。
                    //    ⚠️ 当前无「单 SKU 直购」端点，前端自行加购再跳转会在失败时
                    //    留下脏购物车，故仍落详情页 —— 与 Toko 首页补货行同一处置。
                    context.push('/shop/products/${c.productToken}?from=REPURCHASE');
                  },
                ),
              ),
              const SizedBox(width: 9),
              Expanded(
                flex: 10,
                child: ShopButton(
                  key: ValueKey('recoLater_${c.triggerId}'),
                  label: l10n.recoTriggerLater,
                  variant: ShopButtonVariant.outlineOnInk,
                  onTap: () async {
                    Analytics.capture('toko_repurchase_card_dismiss_tapped',
                        {'trigger_type': c.triggerType});
                    await ref.read(shopRepurchaseRepositoryProvider).dismiss(c.triggerId);
                    ref.invalidate(repurchaseCardsProvider);
                  },
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  static String _shortDate(DateTime d) {
    const months = [
      'Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun',
      'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des',
    ];
    final l = d.toLocal();
    return '${l.day} ${months[l.month - 1]}';
  }
}
