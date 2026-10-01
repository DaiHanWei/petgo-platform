import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../keepsake/data/keepsake_repository.dart';
import '../../keepsake/presentation/keepsake_pay_flow.dart';
import '../../../shared/widgets/pay_channel_picker.dart';
import '../../../shared/widgets/price_load_retry.dart';
import '../../place/presentation/place_list_page.dart';
import '../../profile/presentation/pet_insights_page.dart';
import '../data/pet_passport_repository.dart';
import '../domain/pet_passport.dart';
import 'passport_layout.dart';
import 'share/passport_share_card.dart';
import 'widgets/passport_snapshot_sheet.dart';
import 'passport_page_face.dart';

/// 宠物护照页（V1.3.2 batch-a Story 1.2 · AC5 · UI 稿 B1 / B2 / B2b）。
///
/// - **B1 空态**：与 B2 同尺寸同版心的内页块 +「Belum ada cap」；页脚「0 cap」；无 ⊞、无翻页箭头；
///   吸底「Cari Tempat」。
/// - **B2 单章页**（有章时默认）：`PageView` 一页一枚章；页脚「Cap i / 总章数」（分母 = 已集章数，不是上限）。
/// - **B2b 纵览**：3 列 × 4 行一页、横向分页；空格**不画**未到访占位、不写总数上限；点章回 B2 停在那一页。
///
/// V1.3.2 Story 3.4：**只有 B2b 纵览**吸底出「Buka versi ini · Rp{价}」（当前版本未买）/ 禁用态「Versi ini sudah kebuka」
/// （已买）；B2 单章页不出任何付费按钮。内页按 `currentVersionUnlocked` 叠水印；AppBar 在有已买版本时多一个「已购版本」入口。
///
/// V1.3.2 Story 4.3：**B2 单章页**吸底「Bagikan」→ 护照卡预览（水印同内页：当前版本未买则带）；
/// B2b 吸底仍是快照 CTA、B1 空态不出（没有章可晒）。
///
/// 🔴 AppBar **无 ⋯**。B2 章本体 → B5 章详情（1.3）。
class PetPassportPage extends ConsumerStatefulWidget {
  const PetPassportPage({super.key, this.focus});

  /// 进入时停在哪枚章（场所 token）；找不到停第 1 页。
  final String? focus;

  @override
  ConsumerState<PetPassportPage> createState() => _PetPassportPageState();
}

/// 纵览每页格数（3 列 × 4 行）。
const int kPassportGridPageSize = 12;

class _PetPassportPageState extends ConsumerState<PetPassportPage> {
  bool _grid = false;
  PageController? _single;
  PageController? _gridPages;
  int _index = 0;
  int _gridPage = 0;
  bool _focusApplied = false;
  bool _buying = false;

  /// B7 → 选渠道 → 发起 → PawCoin 即得 / QRIS 轮询（Story 3.4 · AC6.3）。
  Future<void> _buy(PetPassport p) async {
    if (_buying) return;
    final l10n = AppLocalizations.of(context);
    final price = ref.read(keepsakePricingProvider).value?.passportSnapshot;
    if (price == null) return;
    final go = await showPassportSnapshotSheet(context, stampCount: p.stampCount, price: price);
    if (go != true || !mounted) return;
    setState(() => _buying = true);
    try {
      final outcome = await runKeepsakePurchase(
        context: context,
        ref: ref,
        sheet: (balance) => PassportSnapshotPayPicker(passport: p, balance: balance),
        start: (channel) => ref.read(petPassportRepositoryProvider).startSnapshot(channel),
        // 🔴 不能只看 currentVersionUnlocked：买的是发起时冻结的版本（D-4），付款窗内又盖了新章时当前版本仍锁，
        // 那样付了钱面板却永远不认。已买版本数增加 = 这笔已发放（复审 #1）。
        pollPaid: () async {
          final now = await ref.refresh(petPassportProvider.future);
          return now.currentVersionUnlocked || now.purchasedVersionCount > p.purchasedVersionCount;
        },
      );
      if (!mounted || outcome == KeepsakeFlowOutcome.notCompleted) return;
      ref.invalidate(petPassportProvider);
      ref.invalidate(passportSnapshotsProvider);
      if (outcome == KeepsakeFlowOutcome.unlocked) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l10n.passportSnapshotUnlockedToast)));
      }
    } finally {
      if (mounted) setState(() => _buying = false);
    }
  }

  @override
  void dispose() {
    _single?.dispose();
    _gridPages?.dispose();
    super.dispose();
  }

  void _ensureControllers(PetPassport p) {
    if (!_focusApplied) {
      _focusApplied = true;
      _index = p.indexOfToken(widget.focus);
    }
    _single ??= PageController(initialPage: _index);
  }

  void _showGrid() {
    setState(() {
      _grid = true;
      _gridPage = _index ~/ kPassportGridPageSize;
      _gridPages?.dispose();
      _gridPages = PageController(initialPage: _gridPage);
    });
  }

  void _showSingle(int index) {
    setState(() {
      _grid = false;
      _index = index;
      _single?.dispose();
      _single = PageController(initialPage: index);
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(petPassportProvider);
    final passport = async.asData?.value;
    final hasStamps = passport != null && passport.stamps.isNotEmpty;
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        title: Text(l10n.passportPageTitle),
        // 🔴 AppBar 无 ⋯：只有 B2 / B2b 之间的切换。
        actions: [
          if (passport != null && passport.purchasedVersionCount > 0)
            IconButton(
              key: const ValueKey('passportPurchasedVersions'),
              tooltip: l10n.passportPurchasedVersions,
              icon: const Icon(Icons.collections_bookmark_outlined),
              onPressed: () => context.push(PetInsightsRoutes.passportVersions),
            ),
          if (hasStamps)
            _grid
                ? IconButton(
                    key: const ValueKey('passportToggleSingle'),
                    tooltip: l10n.passportViewSingle,
                    icon: const Icon(Icons.filter_center_focus_rounded),
                    onPressed: () => _showSingle(_index),
                  )
                : IconButton(
                    key: const ValueKey('passportToggleGrid'),
                    tooltip: l10n.passportViewAll,
                    icon: const Icon(Icons.grid_view_rounded),
                    onPressed: _showGrid,
                  ),
        ],
      ),
      bottomNavigationBar: (passport != null && passport.stamps.isNotEmpty && _grid)
          ? _SnapshotBar(passport: passport, busy: _buying, onBuy: () => _buy(passport))
          : (passport != null && passport.stamps.isNotEmpty)
          ? SafeArea(
              minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: FilledButton(
                key: const ValueKey('passportShareCta'),
                style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                    backgroundColor: AppColors.mint,
                    foregroundColor: AppColors.onAccent),
                onPressed: () => openPassportSharePreview(context,
                    data: PassportShareData.fromPassport(passport),
                    watermarked: !passport.currentVersionUnlocked),
                child: Text(l10n.cardShareImage),
              ),
            )
          : (passport != null && passport.stamps.isEmpty)
          ? SafeArea(
              minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: FilledButton(
                key: const ValueKey('passportFindPlaces'),
                style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48), backgroundColor: AppColors.mint),
                onPressed: () => context.push(PlaceListPage.routePath),
                child: Text(l10n.passportFindPlaces),
              ),
            )
          : null,
      body: switch (async) {
        AsyncData(:final value) => _content(context, l10n, value),
        AsyncError() => EmptyState(
            title: l10n.passportLoadFailed,
            icon: Icons.cloud_off_rounded,
            actionLabel: l10n.placeRetry,
            onAction: () => ref.invalidate(petPassportProvider),
          ),
        // 骨架与 B1 同版心，避免加载完跳动。
        _ => PassportFrame(header: const SizedBox(height: 44), child: const PassportPageBlock(child: SizedBox())),
      },
    );
  }

  Widget _content(BuildContext context, AppLocalizations l10n, PetPassport p) {
    final header = PassportHeader(petName: p.petName, passportNo: p.passportNo);
    if (p.stamps.isEmpty) {
      return PassportFrame(
        header: header,
        footer: Text(l10n.passportStampCount(0), style: _footerStyle),
        child: PassportPageBlock(
          key: const ValueKey('passportEmpty'),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(l10n.passportEmptyTitle,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
                const SizedBox(height: 8),
                Text(l10n.passportEmptyBody,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 13, height: 1.5, color: AppColors.ink2)),
              ],
            ),
          ),
        ),
      );
    }
    _ensureControllers(p);
    return _grid ? _gridView(context, l10n, p, header) : _singleView(context, l10n, p, header);
  }

  Widget _singleView(BuildContext context, AppLocalizations l10n, PetPassport p, Widget header) {
    final total = p.stamps.length;
    return PassportFrame(
      header: header,
      footer: _Pager(
        label: l10n.passportPageFooter(_index + 1, total),
        labelKey: const ValueKey('passportPageFooter'),
        canPrev: _index > 0,
        canNext: _index < total - 1,
        onPrev: () => _single?.previousPage(duration: _flip, curve: Curves.easeOut),
        onNext: () => _single?.nextPage(duration: _flip, curve: Curves.easeOut),
      ),
      child: PassportPageBlock(
        watermarked: !p.currentVersionUnlocked,
        child: PageView.builder(
          key: const ValueKey('passportSinglePager'),
          controller: _single,
          itemCount: total,
          onPageChanged: (i) => setState(() => _index = i),
          // Story 1.3：章本体可点 → B5 章详情（B2b 纵览点章仍是回 B2，1.2 规则不变）。
          itemBuilder: (context, i) => PassportPageFace(
            stamp: p.stamps[i],
            pageIndex: i,
            onTapStamp: () =>
                context.push(PetInsightsRoutes.passportStampFor(p.stamps[i].placeToken)),
          ),
        ),
      ),
    );
  }

  Widget _gridView(BuildContext context, AppLocalizations l10n, PetPassport p, Widget header) {
    final pages = (p.stamps.length + kPassportGridPageSize - 1) ~/ kPassportGridPageSize;
    return PassportFrame(
      header: header,
      footer: _Pager(
        label: l10n.passportGridFooter(p.stamps.length, _gridPage + 1),
        labelKey: const ValueKey('passportGridFooter'),
        canPrev: _gridPage > 0,
        canNext: _gridPage < pages - 1,
        onPrev: () => _gridPages?.previousPage(duration: _flip, curve: Curves.easeOut),
        onNext: () => _gridPages?.nextPage(duration: _flip, curve: Curves.easeOut),
      ),
      child: PassportPageBlock(
        watermarked: !p.currentVersionUnlocked,
        child: PageView.builder(
          key: const ValueKey('passportGridPager'),
          controller: _gridPages,
          itemCount: pages,
          onPageChanged: (i) => setState(() => _gridPage = i),
          itemBuilder: (context, page) {
            final start = page * kPassportGridPageSize;
            final end = (start + kPassportGridPageSize).clamp(0, p.stamps.length);
            // 🔴 格子比例按内页块的**实际可用高度**算：3 列 × 4 行正好铺满（复审：固定比例时第 4 行被裁掉）。
            return LayoutBuilder(builder: (context, box) {
              const pad = 12.0;
              const gap = 6.0;
              final cellW = (box.maxWidth - pad * 2 - gap * 2) / 3;
              final cellH = (box.maxHeight - pad * 2 - gap * 3) / 4;
              final stampSize = (cellH - 22).clamp(24.0, 62.0);
              return GridView.builder(
                padding: const EdgeInsets.all(pad),
                physics: const NeverScrollableScrollPhysics(),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 3,
                  childAspectRatio: cellW / cellH,
                  mainAxisSpacing: gap,
                  crossAxisSpacing: gap,
                ),
                // 🔴 只画已集的章：空格不画「未到访」占位、不暗示上限。
                itemCount: end - start,
                itemBuilder: (context, j) {
                  final i = start + j;
                  final s = p.stamps[i];
                  return InkWell(
                    key: ValueKey('passportGridCell_$i'),
                    onTap: () => _showSingle(i),
                    child: PassportStampCell(stamp: s, stampSize: stampSize),
                  );
                },
              );
            });
          },
        ),
      ),
    );
  }

  static const Duration _flip = Duration(milliseconds: 260);
  static const TextStyle _footerStyle = TextStyle(
      fontSize: 13,
      color: AppColors.ink2,
      fontFeatures: [FontFeature.tabularFigures()]);
}

/// 页脚：翻页箭头 + 文案（B1 空态不用它 —— 空态无箭头）。
class _Pager extends StatelessWidget {
  const _Pager({
    required this.label,
    required this.labelKey,
    required this.canPrev,
    required this.canNext,
    required this.onPrev,
    required this.onNext,
  });

  final String label;
  final Key labelKey;
  final bool canPrev;
  final bool canNext;
  final VoidCallback onPrev;
  final VoidCallback onNext;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          key: const ValueKey('passportPrev'),
          onPressed: canPrev ? onPrev : null,
          icon: const Icon(Icons.chevron_left_rounded),
        ),
        Text(label, key: labelKey, style: _PetPassportPageState._footerStyle),
        IconButton(
          key: const ValueKey('passportNext'),
          onPressed: canNext ? onNext : null,
          icon: const Icon(Icons.chevron_right_rounded),
        ),
      ],
    );
  }
}

/// B2b 吸底（Story 3.4 · AC6.1）：未买 → 主按钮「Buka versi ini · Rp{价}」；已买 → 禁用态「Versi ini sudah kebuka」。
/// 价格只从服务端读（`keepsakePricingProvider.passportSnapshot`），取价中「…」禁用，失败显示重试。
class _SnapshotBar extends ConsumerWidget {
  const _SnapshotBar({required this.passport, required this.busy, required this.onBuy});

  final PetPassport passport;
  final bool busy;
  final VoidCallback onBuy;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    Widget button;
    if (passport.currentVersionUnlocked) {
      button = FilledButton(
        key: const ValueKey('passportSnapshotOwned'),
        onPressed: null,
        style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
        child: Text(l10n.passportSnapshotOwned),
      );
    } else {
      final price = ref.watch(keepsakePricingProvider);
      if (price.hasError && !price.isLoading) {
        button = Center(
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: PriceLoadRetry(
                key: const ValueKey('passportSnapshotPriceRetry'),
                onRetry: () => ref.invalidate(keepsakePricingProvider)),
          ),
        );
      } else {
        final p = price.value?.passportSnapshot;
        button = FilledButton(
          key: const ValueKey('passportSnapshotCta'),
          onPressed: p == null || busy ? null : onBuy,
          style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(48),
              backgroundColor: AppColors.mint,
              foregroundColor: AppColors.onAccent),
          child: Text(p == null ? '…' : l10n.passportSnapshotCta(formatIdrAmount(p))),
        );
      }
    }
    return SafeArea(minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12), child: button);
  }
}
