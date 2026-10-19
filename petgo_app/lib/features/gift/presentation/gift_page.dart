import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/letter_avatar.dart';
import '../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../data/gift_repository.dart';
import '../domain/gift_send_result.dart';
import '../domain/gift_showcase.dart';
import 'gift_send_flow.dart';
import 'gift_style.dart';
import 'gift_supporters_controller.dart';

/// 礼物页：礼物墙 + 粉丝榜同一页（V1.4.0 gift Story 2.2 · AC3 · decision-log ③ Q1 / Q2 · UI 稿 E1 ~ E7）。
///
/// 自上而下：汇总卡（头像 + 完整金额 + 「N hadiah dari M pendukung」）→ 礼物墙（4 列，默认两行 8 格，
/// 「Lihat semua ▾」展开）→ 粉丝榜（分页）→ 访客视角吸底「Kirim Hadiah」（主人无）。
///
/// 🛡 客户端**不做任何过滤**：主人隐藏的送礼人由服务端剔除；403（我拉黑了主人）/ 404（宠物已删 /
/// 主人拉黑了我）一律 E6，不区分原因、不提示「你被拉黑」。
///
/// ⚠️ 本 story 单宠：礼物墙随宠物切换（A7 / AC13）在对齐 story 4.1 做。
class GiftPage extends ConsumerStatefulWidget {
  const GiftPage({super.key, required this.petId, this.wallInitiallyExpanded = false});

  /// 宠物 id；路由里解析失败传 0 → 直接 E6（不发请求、不崩）。
  final int petId;

  /// `?wall=expanded`（收礼通知深链，AC6.4）：墙初始即展开全部。
  final bool wallInitiallyExpanded;

  /// 独立顶层路由（与 `/pets/:petId` 访客视图并列；go_router 按整段匹配，`/pets/5/gifts` 不会落到访客视图）。
  /// ⚠️ 不进 `_controlledLocations`：登录门控在入口处（`requireLogin`）+ 服务端（仅 role=USER）。
  static const String routePattern = '/pets/:petId/gifts';

  /// `?wall=` 的取值。
  static const String wallExpandedQuery = 'expanded';

  static String routeFor(int petId, {bool wallExpanded = false}) =>
      '/pets/$petId/gifts${wallExpanded ? '?wall=$wallExpandedQuery' : ''}';

  /// 墙默认露出的格数（两行 × 4 列）。
  static const int collapsedWallCount = 8;

  @override
  ConsumerState<GiftPage> createState() => _GiftPageState();
}

class _GiftPageState extends ConsumerState<GiftPage> {
  final ScrollController _scroll = ScrollController();
  final GlobalKey _viewportKey = GlobalKey(debugLabel: 'giftPageViewport');
  final GlobalKey _supportersKey = GlobalKey(debugLabel: 'giftSupportersSection');
  late bool _wallExpanded = widget.wallInitiallyExpanded;
  bool _wallReported = false;
  bool _supportersReported = false;
  bool _sending = false;

  bool get _valid => widget.petId > 0;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    if (_valid) {
      // AC7.1：`gift_wall_opened` 在首个 data 回调报一次（不放 build：build 随每次数据到达重跑）。
      ref.listenManual<AsyncValue<PetGiftShowcase>>(petGiftShowcaseProvider(widget.petId), (_, next) {
        final data = next.value;
        if (data == null || _wallReported) return;
        _wallReported = true;
        Analytics.capture('gift_wall_opened', {'is_owner': data.viewerIsOwner});
      }, fireImmediately: true);
    }
  }

  @override
  void dispose() {
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    super.dispose();
  }

  bool _seenCheckScheduled = false;

  void _onScroll() {
    _maybeReportSupportersSeen();
    // 滚动回调发生在本帧布局之前：区块可能这一帧才被建出来 / 位置才更新 → 布局后再判一次。
    if (!_supportersReported && !_seenCheckScheduled) {
      _seenCheckScheduled = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _seenCheckScheduled = false;
        _maybeReportSupportersSeen();
      });
    }
    if (!_scroll.hasClients) return;
    if (_scroll.position.extentAfter < 400) _autoLoadMore();
  }

  void _autoLoadMore() {
    final s = ref.read(giftSupportersProvider(widget.petId)).value;
    if (s == null || !s.hasMore || s.loadingMore || s.loadMoreFailed) return;
    ref.read(giftSupportersProvider(widget.petId).notifier).loadMore();
  }

  /// AC7.2：粉丝榜区块**首次进入视口**时报一次（页面 ScrollController + 区块 GlobalKey 判位置；首屏可见则首帧报）。
  void _maybeReportSupportersSeen() {
    if (_supportersReported || !mounted) return;
    final data = ref.read(petGiftShowcaseProvider(widget.petId)).value;
    if (data == null) return;
    final section = _supportersKey.currentContext?.findRenderObject();
    final viewport = _viewportKey.currentContext?.findRenderObject();
    if (section is! RenderBox || viewport is! RenderBox || !section.attached || !viewport.attached) return;
    final top = section.localToGlobal(Offset.zero).dy;
    final bottom = viewport.localToGlobal(Offset(0, viewport.size.height)).dy;
    if (top >= bottom) return;
    _supportersReported = true;
    Analytics.capture('gift_sender_list_opened', {'is_owner': data.viewerIsOwner});
  }

  void _afterFrame() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _maybeReportSupportersSeen();
      // 首页行数不足一屏（不可滚）时滚动监听永不触发 —— 这里补一次加载更多。
      if (_scroll.hasClients && _scroll.position.maxScrollExtent <= 0) _autoLoadMore();
    });
  }

  void _retryAll() {
    ref.invalidate(petGiftShowcaseProvider(widget.petId));
    ref.invalidate(giftSupportersProvider(widget.petId));
  }

  Future<void> _send() async {
    if (_sending) return;
    _sending = true;
    try {
      await runGiftSendFlow(
        context: context,
        ref: ref,
        recipient: GiftRecipientRef.pet(widget.petId),
        source: giftSourceGiftPage,
        onDelivered: () {
          if (!mounted) return;
          ref.invalidate(petGiftShowcaseProvider(widget.petId));
          ref.invalidate(giftSupportersProvider(widget.petId));
        },
      );
    } finally {
      _sending = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    if (!_valid) {
      return Scaffold(
        backgroundColor: AppColors.cream,
        appBar: _appBar(l10n.giftStatLabel),
        body: _error(l10n),
      );
    }
    final async = ref.watch(petGiftShowcaseProvider(widget.petId));
    final data = async.value;
    final title = data == null ? l10n.giftStatLabel : l10n.giftPageTitle(data.pet.name);
    final Widget body;
    if (data != null) {
      body = data.giftCount <= 0 ? _empty(l10n, data) : _content(l10n, data);
    } else if (async.hasError) {
      body = _error(l10n);
    } else {
      body = const Center(
          key: ValueKey('giftPageLoading'), child: CircularProgressIndicator(color: AppColors.mint));
    }
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: _appBar(title),
      body: body,
      // E1 / E3 访客吸底「Kirim Hadiah」；主人（E2 / E4）无；加载中 / 失败无。
      bottomNavigationBar: data != null && !data.viewerIsOwner ? _sendBar(l10n) : null,
    );
  }

  PreferredSizeWidget _appBar(String title) => AppBar(
        backgroundColor: AppColors.cream,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
        title: Text(title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
      );

  /// E6：加载失败（含 403 / 404，同形态）。
  Widget _error(AppLocalizations l10n) => Center(
        key: const ValueKey('giftPageError'),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off, size: 48, color: AppColors.muted),
              const SizedBox(height: AppSpacing.md),
              Text(l10n.detailNetworkError,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 14, color: AppColors.ink2)),
              if (_valid) ...[
                const SizedBox(height: AppSpacing.md),
                OutlinedButton(
                  key: const ValueKey('giftPageRetry'),
                  onPressed: _retryAll,
                  child: Text(l10n.commonRetry),
                ),
              ],
            ],
          ),
        ),
      );

  /// E3（访客）/ E4（主人）：还没收到礼物。
  Widget _empty(AppLocalizations l10n, PetGiftShowcase data) => EmptyState(
        key: const ValueKey('giftPageEmpty'),
        icon: Icons.redeem_rounded,
        iconBackground: AppColors.cream2,
        title: l10n.giftPageEmptyTitle(data.pet.name),
        message: data.viewerIsOwner
            ? l10n.giftPageEmptyOwnerHint
            : l10n.giftPageEmptyVisitorHint(data.pet.name),
      );

  Widget _sendBar(AppLocalizations l10n) => SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, AppSpacing.md),
          child: SizedBox(
            height: 48,
            child: FilledButton.icon(
              key: const ValueKey('giftPageSendButton'),
              onPressed: _send,
              icon: const Icon(Icons.redeem, size: 18, color: Colors.white),
              label: Text(l10n.giftSendButton,
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Colors.white)),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
            ),
          ),
        ),
      );

  Widget _content(AppLocalizations l10n, PetGiftShowcase data) {
    final supporters = ref.watch(giftSupportersProvider(widget.petId));
    _afterFrame();
    final lang = Localizations.localeOf(context).languageCode;
    return CustomScrollView(
      key: _viewportKey,
      controller: _scroll,
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, 0),
          sliver: SliverList.list(children: [
            _SummaryCard(data: data),
            const SizedBox(height: AppSpacing.xl),
            _sectionHeader(l10n.giftWallTitle, l10n.giftWallKinds(data.wall.length),
                key: const ValueKey('giftWallHeader')),
            const SizedBox(height: AppSpacing.md),
            _wall(l10n, data.wall, lang),
            const SizedBox(height: AppSpacing.xl),
            KeyedSubtree(
              key: _supportersKey,
              child: _sectionHeader(l10n.giftSupportersTitle, l10n.giftSupportersCount(data.supporterCount),
                  key: const ValueKey('giftSupportersHeader')),
            ),
            const SizedBox(height: AppSpacing.sm),
          ]),
        ),
        ..._supporterSlivers(l10n, supporters),
        const SliverToBoxAdapter(child: SizedBox(height: AppSpacing.xl)),
      ],
    );
  }

  Widget _sectionHeader(String title, String trailing, {Key? key}) => Row(
        key: key,
        children: [
          Text(title, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(width: 6),
          Text('· $trailing', style: const TextStyle(fontSize: 13, color: AppColors.muted)),
        ],
      );

  Widget _wall(AppLocalizations l10n, List<GiftWallItem> wall, String lang) {
    final overflow = wall.length > GiftPage.collapsedWallCount;
    final shown = (_wallExpanded || !overflow) ? wall : wall.take(GiftPage.collapsedWallCount).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        LayoutBuilder(builder: (context, c) {
          const gap = 8.0;
          final w = (c.maxWidth - gap * 3) / 4;
          return Wrap(
            spacing: gap,
            runSpacing: gap,
            children: [
              for (var i = 0; i < shown.length; i++)
                SizedBox(
                  width: w,
                  child: _WallCell(key: ValueKey('giftWallCell_$i'), item: shown[i], lang: lang),
                ),
            ],
          );
        }),
        if (overflow && !_wallExpanded)
          Center(
            child: TextButton(
              key: const ValueKey('giftWallShowAll'),
              onPressed: () => setState(() => _wallExpanded = true),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(l10n.giftWallShowAll,
                      style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.mint)),
                  const Icon(Icons.expand_more, size: 18, color: AppColors.mint),
                ],
              ),
            ),
          ),
      ],
    );
  }

  List<Widget> _supporterSlivers(AppLocalizations l10n, AsyncValue<GiftSupportersState> async) {
    final notifier = ref.read(giftSupportersProvider(widget.petId).notifier);
    final s = async.value;
    if (s == null) {
      if (async.hasError) {
        // 榜首页失败：区块内行内重试（与 E7 同组件），墙照常。
        return [
          SliverToBoxAdapter(
            child: _InlineRetry(key: const ValueKey('giftSupportersRetry'), onRetry: notifier.retry),
          ),
        ];
      }
      return const [
        SliverToBoxAdapter(
          child: Padding(
            padding: EdgeInsets.symmetric(vertical: AppSpacing.lg),
            child: Center(
                child: SizedBox(
                    width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.mint))),
          ),
        ),
      ];
    }
    return [
      SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        sliver: SliverList.builder(
          itemCount: s.items.length,
          itemBuilder: (context, i) => _SupporterRow(row: s.items[i]),
        ),
      ),
      if (s.loadMoreFailed)
        SliverToBoxAdapter(
          child: _InlineRetry(key: const ValueKey('giftSupportersLoadMoreRetry'), onRetry: notifier.loadMore),
        )
      else if (s.loadingMore)
        const SliverToBoxAdapter(
          child: Padding(
            padding: EdgeInsets.symmetric(vertical: AppSpacing.md),
            child: Center(
                child: SizedBox(
                    width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.mint))),
          ),
        ),
    ];
  }
}

/// 汇总卡：宠物头像 56 + 完整金额大字（不缩写）+ 「N hadiah dari M pendukung」。
class _SummaryCard extends StatelessWidget {
  const _SummaryCard({required this.data});

  final PetGiftShowcase data;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final avatar = AppImage.provider(data.pet.avatarUrl, thumbWidth: 200);
    final emoji = switch (data.pet.petType) {
      'CAT' => '🐱',
      'DOG' => '🐶',
      _ => '🐾',
    };
    return Container(
      key: const ValueKey('giftSummaryCard'),
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.mintTint2,
        borderRadius: BorderRadius.circular(18),
      ),
      child: Row(
        children: [
          CircleAvatar(
            radius: 28,
            backgroundColor: GiftStyle.avatarBg,
            backgroundImage: avatar,
            child: avatar == null ? Text(emoji, style: const TextStyle(fontSize: 26)) : null,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Rp${formatIdrAmount(data.totalValue)}',
                    key: const ValueKey('giftSummaryTotal'),
                    style: const TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink,
                        fontFeatures: [FontFeature.tabularFigures()])),
                const SizedBox(height: 2),
                Text(l10n.giftPageSummary(data.giftCount, data.supporterCount),
                    key: const ValueKey('giftSummaryLine'),
                    style: const TextStyle(fontSize: 12.5, color: AppColors.ink2)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 礼物墙一格：图标 + 按语言取的名字 + 右上紫色小胶囊「×N」。
class _WallCell extends StatelessWidget {
  const _WallCell({super.key, required this.item, required this.lang});

  final GiftWallItem item;
  final String lang;

  @override
  Widget build(BuildContext context) {
    return Stack(
      clipBehavior: Clip.none,
      children: [
        Container(
          height: 92,
          padding: const EdgeInsets.fromLTRB(4, 14, 4, 6),
          decoration: BoxDecoration(
            color: AppColors.card,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: GiftStyle.cellBorder),
          ),
          child: Column(
            children: [
              giftIcon(item.iconUrl, size: 36),
              const SizedBox(height: 6),
              Text(item.nameFor(lang),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 11, height: 1.2, color: AppColors.ink2)),
            ],
          ),
        ),
        Positioned(
          top: 4,
          right: 4,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
            decoration: BoxDecoration(color: AppColors.mint, borderRadius: BorderRadius.circular(999)),
            child: Text('×${item.count}',
                style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: Colors.white)),
          ),
        ),
      ],
    );
  }
}

/// 粉丝榜一行（不可点）：排名圆 + 头像 40 + 昵称 / 礼物 + 累计金额。
class _SupporterRow extends StatelessWidget {
  const _SupporterRow({required this.row});

  final GiftSupporter row;

  /// 前三名实色圆（金 / 银 / 铜 + 白字）；第 4 名起灰底灰字。不用奖牌 emoji（跨平台不一致）。
  static Color? podiumColor(int rank) => switch (rank) {
        1 => AppColors.gold,
        2 => AppColors.rankSilver,
        3 => AppColors.rankBronze,
        _ => null,
      };

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final lang = Localizations.localeOf(context).languageCode;
    final podium = podiumColor(row.rank);
    final name = row.deleted ? l10n.feedDeletedUser : (row.nickname ?? '');
    return Padding(
      key: ValueKey('giftSupporterRow_${row.rank}'),
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          Container(
            key: ValueKey('giftRankBadge_${row.rank}'),
            width: 24,
            height: 24,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: podium ?? AppColors.line2, shape: BoxShape.circle),
            child: Text('${row.rank}',
                style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                    color: podium != null ? Colors.white : AppColors.muted)),
          ),
          const SizedBox(width: 10),
          LetterAvatar(url: row.deleted ? null : row.avatarUrl, name: name, deleted: row.deleted, size: 40),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: row.deleted ? AppColors.muted : AppColors.ink)),
                const SizedBox(height: 2),
                _giftsLine(lang),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Text('Rp${formatIdrAmount(row.totalAmount)}',
              key: ValueKey('giftSupporterAmount_${row.rank}'),
              style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                  color: AppColors.ink,
                  fontFeatures: [FontFeature.tabularFigures()])),
        ],
      ),
    );
  }

  /// 「[图标]×n · [图标]×n」（后端已截到最多 3 种）+「+moreKinds」。
  Widget _giftsLine(String lang) {
    const style = TextStyle(fontSize: 11.5, color: AppColors.ink2);
    final parts = <Widget>[];
    for (var i = 0; i < row.gifts.length && i < 3; i++) {
      final g = row.gifts[i];
      if (i > 0) parts.add(const Text(' · ', style: style));
      parts.add(Semantics(
        label: giftNameFor(lang, nameEn: g.nameEn, nameId: g.nameId),
        child: giftIcon(g.iconUrl, size: 14),
      ));
      parts.add(Text('×${g.count}', style: style));
    }
    if (row.moreKinds > 0) parts.add(Text('  +${row.moreKinds}', style: style));
    return Wrap(crossAxisAlignment: WrapCrossAlignment.center, children: parts);
  }
}

/// E7 / 榜首页失败：行内「Gagal memuat · Coba lagi」，点击只重试（已加载行保留）。
class _InlineRetry extends StatelessWidget {
  const _InlineRetry({super.key, required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return InkWell(
      onTap: onRetry,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 44),
        child: Center(
          child: Text.rich(
            TextSpan(children: [
              TextSpan(text: '${l10n.giftLoadMoreFailed} · ', style: const TextStyle(color: AppColors.muted)),
              TextSpan(
                  text: l10n.commonRetry,
                  style: const TextStyle(fontWeight: FontWeight.w700, color: AppColors.mint)),
            ]),
            style: const TextStyle(fontSize: 13),
          ),
        ),
      ),
    );
  }
}
