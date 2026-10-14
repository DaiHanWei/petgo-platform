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
import '../../place/presentation/widgets/place_stamp_view.dart';
import 'share/passport_share_card.dart';
import 'widgets/passport_snapshot_sheet.dart';
import 'passport_page_face.dart';

/// 宠物护照页（V1.3.2 batch-a Story 1.2 · AC5 · UI 稿 B1 / B2 / B2b）。
///
/// - **B1 空态**：与 B2 同尺寸同版心的内页块 +「Belum ada cap」；页脚「0 cap」；无 ⊞、无翻页箭头；
///   吸底「Cari Tempat」。
/// - **B2 单章页**（带 focus 进入时默认；否则从纵览点章进入）：`PageView` 一页一枚章；页脚「Cap i / 总章数」（分母 = 已集章数，不是上限）。
/// - **B2b 纵览**（有章时默认，2026-10-06）：3 列 × 3 行一页、横向分页；空格画空虚线框（设计稿），不写总数上限、
///   不暗示未到访场所；点章回 B2 停在那一页。
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

/// 纵览每页格数（3 列 × 3 行，2026-10-06 设计稿 Artboard 3）。
const int kPassportGridPageSize = 9;

class _PetPassportPageState extends ConsumerState<PetPassportPage> {
  /// 默认先看纵览（2026-10-06 产品）；带 [PetPassportPage.focus]（如落章页「Lihat Paspor」）时直接停在那枚章的单章页。
  late bool _grid = widget.focus == null;
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
        purchasePurpose: 'PASSPORT_SNAP',
        cashPriceIdr: () => price,
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
    if (_grid && _gridPages == null) {
      _gridPage = _index ~/ kPassportGridPageSize;
      _gridPages = PageController(initialPage: _gridPage);
    }
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
                onPressed: () => openPassportSharePreview(context, ref,
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
        _ => const PassportFrame(child: PassportBook(petName: '', passportNo: '', content: SizedBox())),
      },
    );
  }

  Widget _content(BuildContext context, AppLocalizations l10n, PetPassport p) {
    if (p.stamps.isEmpty) return PassportFrame(child: _emptyBook(l10n, p));
    _ensureControllers(p);
    return PassportFrame(child: _grid ? _gridBook(context, l10n, p) : _singleBook(context, l10n, p));
  }

  /// B1 空态（设计稿 Artboard 1）：虚线圆章位 +「Belum ada cap」+ 说明；页脚「0 Cap」、无箭头。
  Widget _emptyBook(AppLocalizations l10n, PetPassport p) {
    return PassportBook(
      key: const ValueKey('passportEmpty'),
      petName: p.petName,
      passportNo: p.passportNo,
      footer: PassportBookFooter(label: l10n.passportStampCount(0), showArrows: false),
      content: Stack(
        children: [
          Positioned(
            left: 208,
            top: 301,
            width: 413,
            height: 413,
            child: Image.asset('assets/passport_book/stamp_slot.png', fit: BoxFit.contain),
          ),
          Positioned(
            left: 0,
            right: 0,
            top: 801,
            child: Text(l10n.passportEmptyTitle,
                textAlign: TextAlign.center, style: passportRubik(38, FontWeight.w700, PassportInk.dark)),
          ),
          Positioned(
            left: 134,
            width: 560,
            top: 855,
            child: Text(l10n.passportEmptyBody,
                textAlign: TextAlign.center,
                style: passportRubik(30, FontWeight.w400, PassportInk.dark, height: 1.25)),
          ),
        ],
      ),
    );
  }

  /// B2 单章页（设计稿 Artboard 2）：一页一枚章；页脚「Cap i/总章数」（分母 = 已集章数，不是上限）+ 翻页箭头。
  Widget _singleBook(BuildContext context, AppLocalizations l10n, PetPassport p) {
    final total = p.stamps.length;
    return PassportBook(
      petName: p.petName,
      passportNo: p.passportNo,
      watermarked: false, // 单章页不叠水印（2026-10-06 产品）；水印只在纵览（B2b）
      footer: PassportBookFooter(
        label: l10n.passportPageFooter(_index + 1, total),
        labelKey: const ValueKey('passportPageFooter'),
        canPrev: _index > 0,
        canNext: _index < total - 1,
        onPrev: () => _single?.previousPage(duration: _flip, curve: Curves.easeOut),
        onNext: () => _single?.nextPage(duration: _flip, curve: Curves.easeOut),
      ),
      content: PageView.builder(
        key: const ValueKey('passportSinglePager'),
        controller: _single,
        itemCount: total,
        onPageChanged: (i) => setState(() => _index = i),
        // Story 1.3：章本体可点 → B5 章详情（B2b 纵览点章仍是回 B2，1.2 规则不变）。
        itemBuilder: (context, i) => PassportPageFace(
          stamp: p.stamps[i],
          pageIndex: i,
          onTapStamp: () => context.push(PetInsightsRoutes.passportStampFor(p.stamps[i].placeToken)),
        ),
      ),
    );
  }

  /// B2b 纵览（设计稿 Artboard 3）：顶部「N Cap」；3 列 × 3 行虚线格一页、横向分页（空格画空虚线格，
  /// 不写总数上限、不暗示未到访场所）；页脚「Halaman i」+ 翻页箭头。点章回 B2 停在那一页。
  Widget _gridBook(BuildContext context, AppLocalizations l10n, PetPassport p) {
    final pages = (p.stamps.length + kPassportGridPageSize - 1) ~/ kPassportGridPageSize;
    return PassportBook(
      petName: p.petName,
      passportNo: p.passportNo,
      watermarked: !p.currentVersionUnlocked,
      footer: PassportBookFooter(
        label: l10n.passportPageLabel(_gridPage + 1),
        labelKey: const ValueKey('passportGridFooter'),
        bold: false,
        canPrev: _gridPage > 0,
        canNext: _gridPage < pages - 1,
        onPrev: () => _gridPages?.previousPage(duration: _flip, curve: Curves.easeOut),
        onNext: () => _gridPages?.nextPage(duration: _flip, curve: Curves.easeOut),
      ),
      content: Stack(
        children: [
          Positioned(
            left: 0,
            right: 0,
            top: 24,
            child: Text(l10n.passportStampCount(p.stamps.length),
                key: const ValueKey('passportGridCount'),
                textAlign: TextAlign.center,
                style: passportRubik(40, FontWeight.w700, PassportInk.footer)),
          ),
          Positioned(
            left: 0,
            right: 0,
            top: 87,
            bottom: 0,
            child: PageView.builder(
              key: const ValueKey('passportGridPager'),
              controller: _gridPages,
              itemCount: pages,
              onPageChanged: (i) => setState(() => _gridPage = i),
              itemBuilder: (context, page) => Stack(
                children: [
                  for (var j = 0; j < kPassportGridPageSize; j++)
                    Positioned(
                      left: const [65.0, 304.0, 542.0][j % 3],
                      top: (j ~/ 3) * 324.0,
                      width: 220,
                      height: 294,
                      child: _gridCell(p, page * kPassportGridPageSize + j),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// 一格：虚线圆角框；有章 → 章面 150 + 场所名（Rubik Medium，两行）+ 右上 xN；无章 → 空框。
  Widget _gridCell(PetPassport p, int i) {
    final has = i < p.stamps.length;
    final frame = CustomPaint(painter: const _DashedCellPainter(), child: const SizedBox.expand());
    if (!has) return frame;
    final s = p.stamps[i];
    return GestureDetector(
      key: ValueKey('passportGridCell_$i'),
      behavior: HitTestBehavior.opaque,
      onTap: () => _showSingle(i),
      child: Stack(
        children: [
          Positioned.fill(child: frame),
          Positioned(
            left: 35,
            top: 45,
            width: 150,
            height: 150,
            child: PlaceStampView(placeType: s.placeType, imageUrl: s.stampImageUrl, size: 150),
          ),
          Positioned(
            left: 10,
            right: 10,
            top: 221,
            child: Text(s.placeName,
                maxLines: 2,
                textAlign: TextAlign.center,
                overflow: TextOverflow.ellipsis,
                style: passportRubik(19, FontWeight.w500, PassportInk.dark, height: 1.16)),
          ),
          if (s.visitCount >= 2) Positioned(left: 163, top: 9, child: PassportVisitBadge(count: s.visitCount)),
        ],
      ),
    );
  }

  static const Duration _flip = Duration(milliseconds: 260);
}

/// 纵览格的虚线圆角框（设计稿：棕色细虚线、圆角约 18）。
class _DashedCellPainter extends CustomPainter {
  const _DashedCellPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = PassportInk.cellBorder
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2;
    final path = Path()..addRRect(RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(18)));
    for (final metric in path.computeMetrics()) {
      var d = 0.0;
      while (d < metric.length) {
        canvas.drawPath(metric.extractPath(d, d + 10), paint);
        d += 16;
      }
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
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
