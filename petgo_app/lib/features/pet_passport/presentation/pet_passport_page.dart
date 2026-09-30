import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../place/presentation/place_list_page.dart';
import '../../place/presentation/widgets/place_stamp_view.dart';
import '../data/pet_passport_repository.dart';
import '../domain/pet_passport.dart';

/// 宠物护照页（V1.3.2 batch-a Story 1.2 · AC5 · UI 稿 B1 / B2 / B2b）。
///
/// - **B1 空态**：与 B2 同尺寸同版心的内页块 +「Belum ada cap」；页脚「0 cap」；无 ⊞、无翻页箭头；
///   吸底「Cari Tempat」。
/// - **B2 单章页**（有章时默认）：`PageView` 一页一枚章；页脚「Cap i / 总章数」（分母 = 已集章数，不是上限）。
/// - **B2b 纵览**：3 列 × 4 行一页、横向分页；空格**不画**未到访占位、不写总数上限；点章回 B2 停在那一页。
///
/// 🔴 本 story **不显示**：付费按钮（3.4）、吸底「Bagikan」（4.3）、章详情跳转（1.3）；AppBar **无 ⋯**。
/// 这些不是「占位」—— 不要留 `enabled:false` 的按钮。
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
      bottomNavigationBar: (passport != null && passport.stamps.isEmpty)
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
        _ => _Frame(header: const SizedBox(height: 44), child: const _PageBlock(child: SizedBox())),
      },
    );
  }

  Widget _content(BuildContext context, AppLocalizations l10n, PetPassport p) {
    final header = _Header(petName: p.petName, passportNo: p.passportNo);
    if (p.stamps.isEmpty) {
      return _Frame(
        header: header,
        footer: Text(l10n.passportStampCount(0), style: _footerStyle),
        child: _PageBlock(
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
    return _Frame(
      header: header,
      footer: _Pager(
        label: l10n.passportPageFooter(_index + 1, total),
        labelKey: const ValueKey('passportPageFooter'),
        canPrev: _index > 0,
        canNext: _index < total - 1,
        onPrev: () => _single?.previousPage(duration: _flip, curve: Curves.easeOut),
        onNext: () => _single?.nextPage(duration: _flip, curve: Curves.easeOut),
      ),
      child: _PageBlock(
        child: PageView.builder(
          key: const ValueKey('passportSinglePager'),
          controller: _single,
          itemCount: total,
          onPageChanged: (i) => setState(() => _index = i),
          itemBuilder: (context, i) => _SingleStamp(stamp: p.stamps[i]),
        ),
      ),
    );
  }

  Widget _gridView(BuildContext context, AppLocalizations l10n, PetPassport p, Widget header) {
    final pages = (p.stamps.length + kPassportGridPageSize - 1) ~/ kPassportGridPageSize;
    return _Frame(
      header: header,
      footer: _Pager(
        label: l10n.passportGridFooter(p.stamps.length, _gridPage + 1),
        labelKey: const ValueKey('passportGridFooter'),
        canPrev: _gridPage > 0,
        canNext: _gridPage < pages - 1,
        onPrev: () => _gridPages?.previousPage(duration: _flip, curve: Curves.easeOut),
        onNext: () => _gridPages?.nextPage(duration: _flip, curve: Curves.easeOut),
      ),
      child: _PageBlock(
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
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        PlaceStampView(
                            placeType: s.placeType, imageUrl: s.stampImageUrl, size: stampSize),
                        const SizedBox(height: 4),
                        Text(s.placeName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 11, color: AppColors.ink2)),
                      ],
                    ),
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

/// 页眉 + 内页块 + 页脚的统一版心（B1 / B2 / B2b / 骨架同尺寸）。
class _Frame extends StatelessWidget {
  const _Frame({required this.header, required this.child, this.footer});

  final Widget header;
  final Widget child;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            header,
            const SizedBox(height: 16),
            child,
            const SizedBox(height: 12),
            if (footer != null) Center(child: footer!),
          ],
        ),
      ),
    );
  }
}

/// 护照内页块（纸面，3:4）。
class _PageBlock extends StatelessWidget {
  const _PageBlock({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 3 / 4,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: AppColors.cream2,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: AppColors.lineViolet),
        ),
        child: child,
      ),
    );
  }
}

/// 页眉：宠物名 + 完整 12 位护照号（单独一行、等宽数字、不截断）。
class _Header extends StatelessWidget {
  const _Header({required this.petName, required this.passportNo});

  final String petName;
  final String passportNo;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(petName,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
        const SizedBox(height: 2),
        Text(passportNo,
            key: const ValueKey('passportNo'),
            softWrap: false,
            overflow: TextOverflow.visible,
            style: const TextStyle(
                fontSize: 14,
                letterSpacing: 1.2,
                color: AppColors.ink2,
                fontFeatures: [FontFeature.tabularFigures()])),
      ],
    );
  }
}

/// B2 的一页：章面 + 场所名 +「{首次日期} · {n} kunjungan」；×N 仅 N≥2。
class _SingleStamp extends StatelessWidget {
  const _SingleStamp({required this.stamp});

  final PassportStamp stamp;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final date = stamp.firstVisitDate == null ? '' : formatDayMonthYear(context, stamp.firstVisitDate!);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          SizedBox(
            width: 150,
            height: 140,
            child: Stack(
              clipBehavior: Clip.none,
              alignment: Alignment.center,
              children: [
                PlaceStampView(placeType: stamp.placeType, imageUrl: stamp.stampImageUrl, size: 128),
                if (stamp.visitCount >= 2)
                  Positioned(
                    right: 0,
                    top: 0,
                    child: Container(
                      key: const ValueKey('passportVisitBadge'),
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: AppColors.popRed,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text('×${stamp.visitCount}',
                          style: const TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                              color: Colors.white,
                              fontFeatures: [FontFeature.tabularFigures()])),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          Text(stamp.placeName,
              textAlign: TextAlign.center,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: 6),
          Text(l10n.passportStampMeta(date, stamp.visitCount),
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
        ],
      ),
    );
  }
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
