import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/empty_state.dart';
import '../data/gift_repository.dart';
import '../domain/gift_order_browse.dart';
import '../domain/gift_send_result.dart';
import 'gift_order_actions.dart';
import 'gift_send_ceremony.dart' show kGiftBoxClosedAsset;
import 'gift_send_flow.dart';
import 'gift_style.dart';

/// 礼物页（V1.5.0 gift-pack Story 2-4 · UI 稿 A4 ~ A6 / F2 ~ F4）：逐份礼物列表，取代 1.4.0 的汇总卡 + 礼物墙 + 粉丝榜。
///
/// - 顶栏「Hadiah {petName}」；主人有未拆时右侧「Buka Semua」（→ 2-5 批量拆）。
/// - 筛选胶囊：Semua / 主人才有 Belum dibuka / 后台分类（首页响应 `categories`）。
/// - 3 列：主人未拆 = 礼盒图 +「Masih terbungkus」+「Baru」角标（后端已排在最前）；其余 = 礼物本身 +「dari {nama}」。
/// - 访客吸底「Kirim Hadiah」，主人无。点卡片：主人未拆 → 拆（2-5）；其余 → 详情抽屉（2-6）。
/// - 🛡 403 / 404 一律 A6 加载失败（不区分原因）。
class GiftPage extends ConsumerStatefulWidget {
  const GiftPage({super.key, required this.petId, this.wallInitiallyExpanded = false, this.openLatest = false});

  /// 宠物 id；路由里解析失败传 0 → 直接 A6（不发请求、不崩）。
  final int petId;

  /// 旧版 `?wall=expanded`（1.4.x 收礼通知深链）：新版忽略。
  final bool wallInitiallyExpanded;

  /// `?open=latest`（收礼通知，D-9）：主人有未拆 → 拆最新一笔未拆；否则打开最新一笔详情。行为由 Story 2-5 实现。
  final bool openLatest;

  static const String routePattern = '/pets/:petId/gifts';
  static const String wallExpandedQuery = 'expanded';
  static const String openLatestQuery = 'latest';

  static String routeFor(int petId, {bool wallExpanded = false, bool openLatest = false}) =>
      '/pets/$petId/gifts${openLatest ? '?open=$openLatestQuery' : ''}';

  @override
  ConsumerState<GiftPage> createState() => _GiftPageState();
}

class _GiftPageState extends ConsumerState<GiftPage> {
  final ScrollController _scroll = ScrollController();
  GiftOrderFilter _filter = const GiftOrderFilter.all();

  // 首页元数据（宠物 / 视角 / 分类）：只在「全部」首页取到时更新，切筛选不丢。
  String? _petName;
  bool? _owner;
  List<GiftCategoryChip> _categories = const [];

  List<GiftOrderCard> _items = const [];
  String? _next;
  int? _unopenedCount;
  bool _loading = true;
  bool _failed = false;
  bool _loadingMore = false;
  bool _loadMoreFailed = false;
  bool _sending = false;
  bool _reported = false;
  bool _senderListReported = false;

  /// `?open=latest` 只执行一次（返回礼物页不再自动弹，2-5 AC3.3）。
  bool _latestHandled = false;
  int _generation = 0;

  bool get _valid => widget.petId > 0;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    if (_valid) _reload();
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _reload() async {
    final gen = ++_generation;
    setState(() {
      _loading = true;
      _failed = false;
      // 换筛选 / 刷新时作废在途翻页（2-4 复审 #1 #5：否则 _loadingMore 永久为 true、翻页卡死）
      _loadingMore = false;
      _loadMoreFailed = false;
    });
    try {
      final page = await ref.read(giftRepositoryProvider).fetchPetGiftOrders(widget.petId, filter: _filter);
      if (!mounted || gen != _generation) return;
      setState(() {
        _items = page.items;
        _next = page.nextCursor;
        _unopenedCount = page.unopenedCount;
        if (page.pet != null) _petName = page.pet!.name;
        if (page.viewerIsOwner != null) _owner = page.viewerIsOwner;
        if (page.categories != null) _categories = page.categories!;
        _loading = false;
      });
      if (!_reported && _owner != null) {
        _reported = true;
        Analytics.capture('gift_wall_opened', {'is_owner': _owner!});
      }
      // 逐笔列表取代旧「送礼人列表」：沿用 1.4.0 口径事件，首次加载到非空列表报一次（不新增埋点，PRD A §4）。
      if (!_senderListReported && _owner != null && _items.isNotEmpty) {
        _senderListReported = true;
        Analytics.capture('gift_sender_list_opened', {'is_owner': _owner!});
      }
      _fillViewport();
      if (widget.openLatest && !_latestHandled) {
        _latestHandled = true;
        if (_owner == true) WidgetsBinding.instance.addPostFrameCallback((_) => _openLatest());
      }
    } catch (_) {
      if (!mounted || gen != _generation) return;
      setState(() {
        _loading = false;
        _failed = true;
      });
    }
  }

  /// 一页填不满视口时继续翻（每次加载后都查，2-4 复审 #6）。
  void _fillViewport() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _scroll.hasClients && _scroll.position.maxScrollExtent <= 0) _loadMore();
    });
  }

  void _onScroll() {
    if (_scroll.hasClients && _scroll.position.extentAfter < 400) _loadMore();
  }

  Future<void> _loadMore() async {
    final cursor = _next;
    if (cursor == null || _loadingMore || _loadMoreFailed) return;
    final gen = _generation;
    setState(() => _loadingMore = true);
    try {
      final page =
          await ref.read(giftRepositoryProvider).fetchPetGiftOrders(widget.petId, filter: _filter, cursor: cursor);
      if (!mounted || gen != _generation) return;
      final seen = {for (final c in _items) c.orderToken};
      setState(() {
        // 主人翻页期间拆过礼物会改变排序键：按 orderToken 去重（D-12，1-4 复审 #5）
        _items = [..._items, ...page.items.where((c) => !seen.contains(c.orderToken))];
        _next = page.nextCursor;
        _loadingMore = false;
      });
      _fillViewport();
    } catch (_) {
      if (!mounted || gen != _generation) return;
      setState(() {
        _loadingMore = false;
        _loadMoreFailed = true;
      });
    }
  }

  void _setFilter(GiftOrderFilter f) {
    if (f == _filter) return;
    _filter = f;
    _next = null;
    _loadMoreFailed = false;
    _reload();
  }

  Future<void> _refreshAll() {
    ref.invalidate(giftPreviewProvider(widget.petId));
    ref.invalidate(petGiftShowcaseProvider(widget.petId));
    return _reload();
  }

  Future<void> _tap(GiftOrderCard c) async {
    if (_owner == true && c.unopened) {
      final changed = await openGiftOrders(context, ref, petId: widget.petId, orderTokens: [c.orderToken]);
      if (changed && mounted) _refreshAll();
    } else {
      await showGiftOrderDetail(context, ref, orderToken: c.orderToken);
    }
  }

  /// 收礼通知点开（2-5 AC3.3 · D-9）：主人有未拆 → 直接拆最新一笔（G1）；没有 → 打开最新一笔详情（2-6 H1）。
  Future<void> _openLatest() async {
    if (!mounted) return;
    final repo = ref.read(giftRepositoryProvider);
    try {
      final unopened = await repo.fetchPetGiftOrders(widget.petId, filter: const GiftOrderFilter.unopened());
      if (!mounted) return;
      if (unopened.items.isNotEmpty) {
        final changed =
            await openGiftOrders(context, ref, petId: widget.petId, orderTokens: [unopened.items.first.orderToken]);
        if (changed && mounted) _refreshAll();
        return;
      }
      final latest = _filter == const GiftOrderFilter.all() && _items.isNotEmpty
          ? _items.first
          : (await repo.fetchPetGiftOrders(widget.petId)).items.firstOrNull;
      if (!mounted || latest == null) return;
      await showGiftOrderDetail(context, ref, orderToken: latest.orderToken);
    } on DioException catch (_) {
      // 拉不到就停在礼物页（列表本身有失败态）
    }
  }

  Future<void> _openAll() async {
    final changed = await openGiftOrders(context, ref, petId: widget.petId, all: true);
    if (changed && mounted) _refreshAll();
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
          if (mounted) _refreshAll();
        },
      );
    } finally {
      _sending = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final title = _petName == null ? l10n.giftStatLabel : l10n.giftPageTitle(_petName!);
    final Widget body;
    if (!_valid || (_failed && _items.isEmpty && _owner == null)) {
      body = _error(l10n);
    } else if (_loading && _owner == null) {
      body = const Center(key: ValueKey('giftPageLoading'), child: CircularProgressIndicator(color: AppColors.mint));
    } else {
      body = _content(l10n);
    }
    final showOpenAll = _owner == true && (_unopenedCount ?? 0) > 0;
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
        title: Text(title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
        actions: [
          if (showOpenAll)
            TextButton(
              key: const ValueKey('giftPageOpenAll'),
              onPressed: _openAll,
              child: Text(l10n.giftOpenAll,
                  style: const TextStyle(fontWeight: FontWeight.w700, color: AppColors.mint)),
            ),
        ],
      ),
      body: body,
      bottomNavigationBar: _owner == false ? _sendBar(l10n) : null,
    );
  }

  Widget _error(AppLocalizations l10n) => Center(
        key: const ValueKey('giftPageError'),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.cloud_off, size: 48, color: AppColors.muted),
            const SizedBox(height: AppSpacing.md),
            Text(l10n.giftLoadFailed,
                textAlign: TextAlign.center, style: const TextStyle(fontSize: 14, color: AppColors.ink2)),
            if (_valid) ...[
              const SizedBox(height: AppSpacing.md),
              OutlinedButton(key: const ValueKey('giftPageRetry'), onPressed: _reload, child: Text(l10n.commonRetry)),
            ],
          ]),
        ),
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

  Widget _chips(AppLocalizations l10n) {
    final locale = Localizations.localeOf(context);
    Widget chip(String key, String label, GiftOrderFilter f) => Padding(
          padding: const EdgeInsets.only(right: 8),
          child: ChoiceChip(
            key: ValueKey('giftPageFilter-$key'),
            label: Text(label),
            selected: _filter == f,
            showCheckmark: false,
            materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
            selectedColor: AppColors.mintTint,
            onSelected: (_) => _setFilter(f),
          ),
        );
    return SingleChildScrollView(
      key: const ValueKey('giftPageFilters'),
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.fromLTRB(16, 4, 8, 8),
      child: Row(children: [
        chip('all', l10n.orderFilterAll, const GiftOrderFilter.all()),
        if (_owner == true) chip('unopened', l10n.giftFilterUnopened, const GiftOrderFilter.unopened()),
        for (final c in _categories)
          chip('cat-${c.categoryId}', c.nameFor(locale), GiftOrderFilter.category(c.categoryId)),
      ]),
    );
  }

  Widget _content(AppLocalizations l10n) {
    final name = _petName ?? '';
    final Widget list;
    if (_loading) {
      list = const SliverFillRemaining(
          hasScrollBody: false, child: Center(child: CircularProgressIndicator(color: AppColors.mint)));
    } else if (_failed) {
      list = SliverFillRemaining(hasScrollBody: false, child: _error(l10n));
    } else if (_items.isEmpty) {
      list = SliverFillRemaining(
        hasScrollBody: false,
        child: EmptyState(
          key: const ValueKey('giftPageEmpty'),
          icon: Icons.redeem_rounded,
          iconBackground: AppColors.cream2,
          title: l10n.giftPageEmptyTitle(name),
          message: _owner == true ? l10n.giftPageEmptyOwnerHint : l10n.giftPageEmptyVisitorHint(name),
        ),
      );
    } else {
      list = SliverPadding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        sliver: SliverGrid.builder(
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 3, mainAxisSpacing: 10, crossAxisSpacing: 10, childAspectRatio: 0.78),
          itemCount: _items.length,
          itemBuilder: (_, i) => GiftOrderTile(
            key: ValueKey('giftOrderTile-${_items[i].orderToken}'),
            card: _items[i],
            onTap: () => _tap(_items[i]),
          ),
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _refreshAll,
      child: CustomScrollView(
        controller: _scroll,
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverToBoxAdapter(child: _chips(l10n)),
          list,
          if (_loadingMore)
            const SliverToBoxAdapter(
                child: Padding(
                    padding: EdgeInsets.all(16),
                    child: Center(child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.mint)))),
          if (_loadMoreFailed)
            SliverToBoxAdapter(
              child: Center(
                child: TextButton(
                  key: const ValueKey('giftPageLoadMoreRetry'),
                  onPressed: () {
                    setState(() => _loadMoreFailed = false);
                    _loadMore();
                  },
                  child: Text(l10n.commonRetry),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// 一份礼物格（礼物页 3 列；主人未拆 = 礼盒 +「Masih terbungkus」+「Baru」）。
class GiftOrderTile extends StatelessWidget {
  const GiftOrderTile({super.key, required this.card, required this.onTap});

  final GiftOrderCard card;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    final wrapped = card.unopened;
    final sender = card.sender.deleted || card.sender.nickname == null ? l10n.feedDeletedUser : card.sender.nickname!;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Stack(clipBehavior: Clip.none, children: [
        Container(
          width: double.infinity,
          padding: const EdgeInsets.fromLTRB(6, 12, 6, 8),
          decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(16)),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            SizedBox(
              width: 56,
              height: 56,
              child: wrapped
                  ? Image.asset(kGiftBoxClosedAsset,
                      key: const ValueKey('giftOrderTileBox'),
                      errorBuilder: (_, _, _) => const Icon(Icons.card_giftcard, color: AppColors.mint, size: 40))
                  : giftIcon(card.giftIconUrl, size: 48),
            ),
            const SizedBox(height: 6),
            Text(wrapped ? l10n.giftStillWrapped : card.nameFor(locale),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.ink)),
            const SizedBox(height: 2),
            Text(l10n.giftReceivedFrom(sender),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 10.5, color: AppColors.muted)),
          ]),
        ),
        if (wrapped)
          Positioned(
            top: 6,
            right: 6,
            child: Container(
              key: const ValueKey('giftOrderTileNew'),
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(color: AppColors.mint, borderRadius: BorderRadius.circular(999)),
              child: Text(l10n.giftNewBadge,
                  style: const TextStyle(fontSize: 9.5, fontWeight: FontWeight.w700, color: Colors.white)),
            ),
          ),
      ]),
    );
  }
}

/// 送礼人小头像（Diary 礼物行右下）。
Widget giftSenderAvatar(GiftOrderSender s, {double size = 16}) => CircleAvatar(
      radius: size / 2,
      backgroundColor: AppColors.mintTint,
      backgroundImage: s.deleted ? null : AppImage.provider(s.avatarUrl, thumbWidth: 64),
    );
