/// 复购触发卡与档案推荐区 —— **设计稿版式**（V1.4.0 · `03_screens_recommendation.md` 屏 1/2）。
///
/// 与 `repurchase_zones.dart`（v1 版式）并存，由 `shopUiVariantProvider` 二选一。
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
import 'shop_surface.dart';

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
                  child: Text(c.productName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w600,
                          color: ShopColors.surface)),
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

/// 屏 2：档案推荐区。
///
/// 🔴 **两级推荐同页共存**：数据够时上区（个性化）在前；数据不足时上区整块不渲染，
/// 只留下区（MODE DASAR）—— **不允许把泛推荐伪装成个性化**。
///
/// 当前推荐接口不下发「命中的记录 + 日期」，故恒走降级态（见文件头）。
class ProfileRecoZoneV2 extends ConsumerWidget {
  const ProfileRecoZoneV2({super.key, this.zone = 'profile_reco'});

  final String zone;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final r = ref.watch(recommendationsProvider).maybeWhen<Recommendations?>(
          data: (v) => v,
          orElse: () => null,
        );
    if (r == null || r.isGuest) return const SizedBox.shrink();
    if (r.needsProfileCreation) return _createProfileCard(context, l10n);
    if (r.items.isEmpty) return const SizedBox.shrink();

    final pet = r.petName ?? '';
    return ShopSection(
      padding: const EdgeInsets.fromLTRB(0, 12, 0, 13),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: kShopScreenEdge),
            child: Row(
              children: [
                Expanded(
                  // 🔴 降级态标题**转灰**并带 `MODE DASAR` 角标 —— 让用户一眼看出
                  //    这不是按他的记录挑的。假装成个性化才是真正的伤害。
                  child: Text(l10n.recoBasicTitle(pet),
                      style: ShopText.sectionTitle
                          .copyWith(fontSize: 13.5, color: ShopColors.text3)),
                ),
                Text(l10n.recoBasicBadge,
                    key: const ValueKey('recoBasicBadge'),
                    style: ShopText.badge
                        .copyWith(fontSize: 9.5, color: ShopColors.text4)),
              ],
            ),
          ),
          const SizedBox(height: 9),
          // 🔴 降级说明**必须给出脱困路径** —— 不写「数据不足」了事，
          //    写清还差几条记录。这同时也是回写 Diary 的引导。
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: kShopScreenEdge),
            child: ShopLeftAccentBlock.pawcoin(
              key: const ValueKey('recoDegradedHint'),
              child: Row(
                children: [
                  Container(
                    width: 13,
                    height: 13,
                    decoration: const BoxDecoration(
                        color: ShopColors.purple, shape: BoxShape.circle),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(l10n.recoDegradedBody(pet),
                        style: ShopText.body.copyWith(color: ShopColors.purpleText)),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 11),
          // 降级态用 2 列网格（设计稿），不是横滑 —— 横滑是个性化区的形态。
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: kShopScreenEdge),
            child: GridView.builder(
              // 🔴 `padding` **必须显式给**，哪怕是零。
              //    `BoxScrollView` 在 padding == null 时会自动把 `MediaQuery.padding`
              //    的主轴部分（这里是状态栏高度）当成自己的内边距。外层 ListView 自己设了
              //    padding，因此没有把 MediaQuery 的 padding 剥掉 —— 内层网格就白白顶出
              //    一整条状态栏的高度（2026-08-19 上机实测 54dp 空白，密度 420dpi）。
              padding: EdgeInsets.zero,
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                mainAxisSpacing: 9,
                crossAxisSpacing: 9,
                mainAxisExtent: _basicCardExtent(context),
              ),
              itemCount: r.items.length,
              itemBuilder: (c, i) => _BasicCard(item: r.items[i], zone: zone),
            ),
          ),
          const SizedBox(height: 12),
          // 底部总开关行 —— 设置页未落地，这里只给入口占位说明，不给假开关。
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: kShopScreenEdge),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(l10n.recoSettingsEntry,
                          style: ShopText.cardTitle.copyWith(fontSize: 11.5)),
                      Text(l10n.recoSettingsEntrySub, style: ShopText.meta),
                    ],
                  ),
                ),
                // ⚠️ 这里**刻意不放开关**：设置项没有服务端持久化，
                //    一个关了之后重装 app 又自己打开的开关，比没有更伤信任。
                //    见文件尾「未实现」说明。
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _createProfileCard(BuildContext context, AppLocalizations l10n) => ShopSection(
        key: const ValueKey('recoCreateProfileCardV2'),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: ShopColors.purpleTagBg,
                borderRadius: BorderRadius.circular(ShopShape.radiusField),
              ),
              child: const Icon(Icons.auto_awesome, size: 17, color: ShopColors.purple),
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(l10n.tokoRecoCreateProfileTitle,
                      style: ShopText.cardTitle.copyWith(fontSize: 12.5)),
                  Text(l10n.profileOnboardingBody, style: ShopText.meta),
                ],
              ),
            ),
            const SizedBox(width: 9),
            ShopButton(
              key: const ValueKey('recoCreateProfileCtaV2'),
              label: l10n.tokoRecoCreateProfileCta,
              variant: ShopButtonVariant.purple,
              dense: true,
              onTap: () => context.push('/profile/create'),
            ),
          ],
        ),
      );
}

class _BasicCard extends StatefulWidget {
  const _BasicCard({required this.item, required this.zone});

  final RecommendationItem item;
  final String zone;

  @override
  State<_BasicCard> createState() => _BasicCardState();
}

class _BasicCardState extends State<_BasicCard> {
  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_product_shown', {'zone': widget.zone});
  }

  @override
  Widget build(BuildContext context) {
    final it = widget.item;
    return InkWell(
      onTap: () {
        // 🔒 只带 zone 与 product_id。**不带 reco_reason**：理由文本里含宠物的
        //    年龄段与体型区间，等于把档案的粗化版本送进三方分析平台（NFR-5）。
        Analytics.capture(
            'toko_profile_reco_tapped', {'zone': widget.zone, 'product_id': it.productToken});
        context.push('/shop/products/${it.productToken}?from=PROFILE_RECO');
      },
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(
            height: 96,
            width: double.infinity,
            child: ShopImage(
                url: null, size: 96, fillWidth: true, radius: ShopShape.radiusButton),
          ),
          const SizedBox(height: 6),
          Expanded(
            child: Align(
              alignment: Alignment.topLeft,
              child: Text(it.name,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: ShopText.productNameCard),
            ),
          ),
          Text(formatIdr(it.minPrice),
              style: ShopText.priceRail.copyWith(color: ShopColors.accent)),
          // 🔴 降级态**不带来源标签**（设计稿）—— 它本来就不是按某条记录挑的。
        ],
      ),
    );
  }
}

/// 降级态卡片高度。文字部分随 textScaler 伸缩（同 Toko 首页的处置）。
double _basicCardExtent(BuildContext context) {
  final ts = MediaQuery.textScalerOf(context);
  return 96 + 6 + ts.scale(kCardProductNameHeight) + 4 + ts.scale(22);
}
