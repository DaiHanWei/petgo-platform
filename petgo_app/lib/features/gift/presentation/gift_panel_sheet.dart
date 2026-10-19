import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/pay_channel_picker.dart';
import '../data/gift_repository.dart';
import '../domain/gift_catalog.dart';
import '../domain/gift_send_result.dart';
import 'gift_style.dart';

/// 面板里选定的一份礼物（礼物 + 数量）。
class GiftSelection {
  const GiftSelection({required this.catalog, required this.item, required this.quantity});

  final GiftCatalog catalog;
  final GiftItem item;
  final int quantity;

  int get total => item.price * quantity;
}

/// 送礼面板的结局（null = 用户关掉面板）。
sealed class GiftPanelResult {
  const GiftPanelResult();
}

/// 点了「Kirim」。
class GiftPanelSend extends GiftPanelResult {
  const GiftPanelSend(this.selection);
  final GiftSelection selection;
}

/// 点了锁区块（B1 / B2，gift Story 4.1 · AC7.1）：面板已关，调用方进 ① 等级页 `/me/growth`。
class GiftPanelOpenLevelPage extends GiftPanelResult {
  const GiftPanelOpenLevelPage();
}

/// 目录接口拒绝（`gift-pet-gone` / `gift-own-pet` / `gift-blocked`）：调用方关面板 + C9 Toast，不进 B7。
class GiftPanelCannotSend extends GiftPanelResult {
  const GiftPanelCannotSend();
}

/// 目录接口的确定性拒绝（三种同一句文案，不暴露拉黑关系，C9）。
const Set<String> giftCannotSendSlugs = {'gift-pet-gone', 'gift-own-pet', 'gift-blocked'};

/// 数量上下限（PRD：×1 / ×5 / ×10 + 微调，最多 ×99）。
const int giftMinQuantity = 1;
const int giftMaxQuantity = 99;

/// 弹送礼面板（V1.4.0 gift Story 1.3 · AC2，UI 稿 B1 ~ B7）。
///
/// [initialGiftToken] / [initialQuantity]：从选渠道抽屉回来时保留上次的选择（S1）。
/// [onCatalogShown]：目录第一次显示出来时回调（流程据此报 `gift_panel_opened`）。
Future<GiftPanelResult?> showGiftPanelSheet(
  BuildContext context, {
  required GiftRecipientRef recipient,
  String? initialGiftToken,
  int initialQuantity = 1,
  ValueChanged<GiftCatalog>? onCatalogShown,
}) {
  return showModalBottomSheet<GiftPanelResult>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.card,
    barrierColor: GiftStyle.barrier,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => GiftPanelSheet(
      recipient: recipient,
      initialGiftToken: initialGiftToken,
      initialQuantity: initialQuantity,
      onCatalogShown: onCatalogShown,
    ),
  );
}

/// 送礼面板主体（高约屏幕 88%；网格内部滚动，底部数量 / 合计 / 按钮固定）。
class GiftPanelSheet extends ConsumerStatefulWidget {
  const GiftPanelSheet({
    super.key,
    required this.recipient,
    this.initialGiftToken,
    this.initialQuantity = 1,
    this.onCatalogShown,
  });

  final GiftRecipientRef recipient;
  final String? initialGiftToken;
  final int initialQuantity;
  final ValueChanged<GiftCatalog>? onCatalogShown;

  @override
  ConsumerState<GiftPanelSheet> createState() => _GiftPanelSheetState();
}

class _GiftPanelSheetState extends ConsumerState<GiftPanelSheet> {
  String? _token;
  late int _qty;
  bool _closing = false;
  bool _shownReported = false;

  @override
  void initState() {
    super.initState();
    _token = widget.initialGiftToken;
    _qty = widget.initialQuantity.clamp(giftMinQuantity, giftMaxQuantity);
  }

  void _closeCannotSend() {
    if (_closing) return;
    _closing = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) Navigator.of(context).pop(const GiftPanelCannotSend());
    });
  }

  void _reportShown(GiftCatalog catalog) {
    if (_shownReported) return;
    _shownReported = true;
    widget.onCatalogShown?.call(catalog);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(giftCatalogProvider(widget.recipient));
    final height = MediaQuery.sizeOf(context).height * 0.88;

    final Widget body;
    if (async.hasValue && !async.isLoading) {
      final catalog = async.requireValue;
      final items = catalog.orderedItems;
      if (items.isEmpty) {
        // Q6：礼物全下线不画空态，B7 兜底。
        body = _failed(l10n, title: null);
      } else {
        _reportShown(catalog);
        body = _loaded(l10n, catalog, items);
      }
    } else if (async.hasError && !async.isLoading) {
      final err = async.error;
      final slug = err is DioException ? ProblemDetail.fromDioException(err)?.typeSlug : null;
      if (slug != null && giftCannotSendSlugs.contains(slug)) {
        _closeCannotSend();
        body = _loading(l10n);
      } else {
        body = _failed(l10n, title: null);
      }
    } else {
      body = _loading(l10n);
    }
    return SizedBox(height: height, child: body);
  }

  Widget _handle() => Center(
        child: Container(
          width: 36,
          height: 4,
          margin: const EdgeInsets.fromLTRB(0, 10, 0, 14),
          decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(2)),
        ),
      );

  /// B6：标题行 + 居中转圈（收礼方未知时标题用通用「Kirim hadiah」）。
  Widget _loading(AppLocalizations l10n) => Column(
        key: const ValueKey('giftPanelLoading'),
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _handle(),
          _plainTitle(l10n.giftPayTitle),
          const Expanded(
            child: Center(
              child: SizedBox(
                width: 28,
                height: 28,
                child: CircularProgressIndicator(strokeWidth: 3, color: AppColors.mint),
              ),
            ),
          ),
        ],
      );

  /// B7：加载失败 + 重试。
  Widget _failed(AppLocalizations l10n, {String? title}) => Column(
        key: const ValueKey('giftPanelFailed'),
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _handle(),
          _plainTitle(title ?? l10n.giftPayTitle),
          Expanded(
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.cloud_off, size: 48, color: AppColors.muted),
                  const SizedBox(height: 12),
                  Text(l10n.giftLoadFailed,
                      style: const TextStyle(
                          fontSize: 18, fontWeight: FontWeight.w600, color: AppColors.ink)),
                  const SizedBox(height: 16),
                  OutlinedButton(
                    key: const ValueKey('giftPanelRetry'),
                    onPressed: () => ref.invalidate(giftCatalogProvider(widget.recipient)),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: AppColors.mint,
                      side: const BorderSide(color: AppColors.mint, width: 1.5),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
                    ),
                    child: Text(l10n.commonRetry),
                  ),
                ],
              ),
            ),
          ),
        ],
      );

  Widget _plainTitle(String text) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 6),
        child: Text(text,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
      );

  Widget _loaded(AppLocalizations l10n, GiftCatalog catalog, List<GiftItem> items) {
    final locale = Localizations.localeOf(context);
    final selected = catalog.itemByToken(_token) ?? items.first;
    final total = selected.price * _qty;
    return Column(
      key: const ValueKey('giftPanelLoaded'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _handle(),
        _titleRow(l10n, catalog.recipient),
        Expanded(
          child: SingleChildScrollView(
            key: const ValueKey('giftPanelGrid'),
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: LayoutBuilder(
              builder: (context, c) {
                final cellW = (c.maxWidth - 8 * 3) / 4;
                final children = <Widget>[];
                var first = true;
                for (final tier in catalog.sortedTiers) {
                  if (tier.gifts.isEmpty) continue;
                  children.add(_tierHeader(l10n, tier.price, first: first));
                  first = false;
                  children.add(_grid(tier.gifts, selected, cellW, locale));
                }
                for (final group in catalog.sortedExclusiveGroups) {
                  if (group.gifts.isEmpty) continue;
                  children.add(_exclusiveHeader(l10n, group.minLevel, first: first));
                  first = false;
                  children.add(_grid(group.gifts, selected, cellW, locale, exclusiveLevel: group.minLevel));
                }
                final lock = catalog.lockBlock;
                if (lock != null) children.add(_lockBlock(l10n, lock.requiredLevel));
                return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
              },
            ),
          ),
        ),
        _bottom(l10n, catalog, selected, total, locale),
      ],
    );
  }

  /// 标题行：头像 32（双环）+ 「Kirim hadiah untuk {petName}」+ 物种 · 品种。
  Widget _titleRow(AppLocalizations l10n, GiftRecipient r) {
    final species = switch (r.petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
    final sub = [?species, if (r.breed != null && r.breed!.isNotEmpty) r.breed!].join(' · ');
    final avatar = AppImage.provider(r.avatarUrl, thumbWidth: 96);
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 6),
      child: Row(
        children: [
          Container(
            width: 32,
            height: 32,
            clipBehavior: Clip.antiAlias,
            alignment: Alignment.center,
            decoration: const BoxDecoration(
              shape: BoxShape.circle,
              color: GiftStyle.avatarBg,
              boxShadow: [
                BoxShadow(color: AppColors.dashedViolet, spreadRadius: 3.5),
                BoxShadow(color: Colors.white, spreadRadius: 2),
              ],
            ),
            child: avatar == null
                ? const Icon(Icons.pets, size: 18, color: AppColors.mint)
                : Image(
                    image: avatar,
                    width: 32,
                    height: 32,
                    fit: BoxFit.cover,
                    errorBuilder: (_, _, _) => const Icon(Icons.pets, size: 18, color: AppColors.mint),
                  ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(l10n.giftPanelTitle(r.name),
                    key: const ValueKey('giftPanelTitle'),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
                if (sub.isNotEmpty)
                  Text(sub,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 12, color: AppColors.muted)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _tierHeader(AppLocalizations l10n, int price, {required bool first}) {
    final name = giftTierName(l10n, price);
    return Padding(
      padding: EdgeInsets.fromLTRB(2, first ? 6 : 14, 2, 8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text('Rp${formatIdrAmount(price)}',
              key: ValueKey('giftTierPrice-$price'),
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
          if (name != null)
            Text(name,
                style: const TextStyle(
                    fontSize: 11.5, fontWeight: FontWeight.w500, color: AppColors.muted)),
        ],
      ),
    );
  }

  /// S2：专属组标签「Eksklusif · Lv{N}+」（底色按段位）。
  Widget _exclusiveHeader(AppLocalizations l10n, int level, {required bool first}) => Padding(
        padding: EdgeInsets.fromLTRB(2, first ? 6 : 14, 2, 8),
        child: Align(
          alignment: Alignment.centerLeft,
          child: Container(
            key: ValueKey('giftExclusiveLabel-$level'),
            padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
            decoration: BoxDecoration(
              gradient: GiftStyle.levelGradient(level),
              borderRadius: BorderRadius.circular(999),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.workspace_premium, size: 12, color: Colors.white),
                const SizedBox(width: 3),
                Text(l10n.giftExclusiveGroup(level),
                    style: const TextStyle(
                        fontSize: 11, fontWeight: FontWeight.w700, color: Colors.white)),
              ],
            ),
          ),
        ),
      );

  Widget _grid(List<GiftItem> gifts, GiftItem selected, double cellW, Locale locale,
      {int? exclusiveLevel}) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final g in gifts)
          SizedBox(
            width: cellW,
            child: _GiftCell(
              key: ValueKey('giftCell-${g.giftToken}'),
              item: g,
              name: g.nameFor(locale),
              selected: g.giftToken == selected.giftToken,
              exclusiveLevel: exclusiveLevel,
              onTap: () => setState(() => _token = g.giftToken),
            ),
          ),
      ],
    );
  }

  /// B1 / B2：锁区块。点击 → 上报 → 关面板 → 调用方进 ① 等级页（gift Story 4.1 · AC7.1；不再出 Toast）。
  Widget _lockBlock(AppLocalizations l10n, int level) => Padding(
        padding: const EdgeInsets.fromLTRB(0, 14, 0, 4),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            key: const ValueKey('giftLockBlock'),
            borderRadius: BorderRadius.circular(16),
            onTap: () {
              Analytics.capture('gift_locked_block_tapped', {'required_level': level});
              Navigator.of(context).pop(const GiftPanelOpenLevelPage());
            },
            child: Ink(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(16),
                gradient: const LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [Color(0xFFF3EDFF), Color(0xFFFBF8FF)],
                ),
              ),
              child: Row(
                children: [
                  Container(
                    width: 36,
                    height: 36,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: Colors.white,
                      shape: BoxShape.circle,
                      border: Border.all(color: GiftStyle.levelRing(level), width: 2),
                    ),
                    child: Icon(Icons.lock, size: 18, color: GiftStyle.levelRing(level)),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(l10n.giftLockTitle(level),
                            style: const TextStyle(
                                fontSize: 13.5, fontWeight: FontWeight.w700, color: AppColors.ink)),
                        const SizedBox(height: 2),
                        Text(l10n.giftLockSubtitle,
                            style: const TextStyle(fontSize: 11.5, color: AppColors.ink2)),
                      ],
                    ),
                  ),
                  const Icon(Icons.chevron_right, size: 20, color: AppColors.muted),
                ],
              ),
            ),
          ),
        ),
      );

  void _setQty(int q) => setState(() => _qty = q.clamp(giftMinQuantity, giftMaxQuantity));

  Widget _bottom(
      AppLocalizations l10n, GiftCatalog catalog, GiftItem selected, int total, Locale locale) {
    const tabular = [FontFeature.tabularFigures()];
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      decoration: const BoxDecoration(
        color: AppColors.card,
        boxShadow: [BoxShadow(color: Color(0x0F162233), offset: Offset(0, -6), blurRadius: 18)],
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // B4 / B5：数量胶囊 + 步进器。
            Row(
              children: [
                for (final q in const [1, 5, 10]) ...[
                  _QtyChip(
                    key: ValueKey('giftQtyChip-$q'),
                    label: l10n.giftQuantityChip(q),
                    selected: _qty == q,
                    onTap: () => _setQty(q),
                  ),
                  const SizedBox(width: 6),
                ],
                const Spacer(),
                Container(
                  padding: const EdgeInsets.all(3),
                  decoration: BoxDecoration(
                      color: GiftStyle.tint, borderRadius: BorderRadius.circular(999)),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _StepButton(
                        key: const ValueKey('giftQtyMinus'),
                        icon: Icons.remove,
                        enabled: _qty > giftMinQuantity,
                        onTap: () => _setQty(_qty - 1),
                      ),
                      ConstrainedBox(
                        constraints: const BoxConstraints(minWidth: 24),
                        child: Text('$_qty',
                            key: const ValueKey('giftQtyValue'),
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w700,
                                color: AppColors.ink,
                                fontFeatures: tabular)),
                      ),
                      _StepButton(
                        key: const ValueKey('giftQtyPlus'),
                        icon: Icons.add,
                        enabled: _qty < giftMaxQuantity,
                        onTap: () => _setQty(_qty + 1),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            // 合计行。
            Row(
              children: [
                giftIcon(selected.iconUrl, size: 22),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(l10n.giftSummaryLine(selected.nameFor(locale), _qty),
                      key: const ValueKey('giftSummaryLine'),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
                ),
                Text('Rp${formatIdrAmount(total)}',
                    key: const ValueKey('giftTotal'),
                    style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink,
                        fontFeatures: tabular)),
              ],
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 48,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(14),
                  boxShadow: const [
                    BoxShadow(color: Color(0x47845EC9), offset: Offset(0, 6), blurRadius: 16),
                  ],
                ),
                child: FilledButton(
                  key: const ValueKey('giftSendButton'),
                  onPressed: () {
                    Analytics.capture('gift_send_tapped', {
                      'gift_id': selected.giftToken,
                      'quantity': _qty,
                      'total_amount': total,
                      'is_exclusive': selected.isExclusive,
                    });
                    Navigator.of(context).pop(GiftPanelSend(
                        GiftSelection(catalog: catalog, item: selected, quantity: _qty)));
                  },
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: Text(l10n.giftSendConfirm(formatIdrAmount(total)),
                      style: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w700)),
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(l10n.giftNoRefundNote,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 11, color: AppColors.muted)),
          ],
        ),
      ),
    );
  }
}

/// 礼物格（选中态：描边 + 浅紫底 + 阴影 + 白图标底 + 右上勾 + 价格变紫加粗 —— 不只靠颜色）。
class _GiftCell extends StatefulWidget {
  const _GiftCell({
    super.key,
    required this.item,
    required this.name,
    required this.selected,
    required this.onTap,
    this.exclusiveLevel,
  });

  final GiftItem item;
  final String name;
  final bool selected;
  final VoidCallback onTap;
  final int? exclusiveLevel;

  @override
  State<_GiftCell> createState() => _GiftCellState();
}

class _GiftCellState extends State<_GiftCell> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    final sel = widget.selected;
    final exclusive = widget.exclusiveLevel;
    final Border border = sel
        ? Border.all(color: AppColors.mint, width: 1.5)
        : exclusive != null
            ? Border.all(color: GiftStyle.exclusiveBorder, width: 1.5)
            : Border.all(color: GiftStyle.cellBorder);
    return Semantics(
      button: true,
      selected: sel,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        onTapDown: (_) => setState(() => _pressed = true),
        onTapUp: (_) => setState(() => _pressed = false),
        onTapCancel: () => setState(() => _pressed = false),
        child: AnimatedScale(
          scale: _pressed ? 0.95 : 1,
          duration: const Duration(milliseconds: 150),
          child: Stack(
            children: [
              Container(
                padding: const EdgeInsets.fromLTRB(2, 8, 2, 7),
                decoration: BoxDecoration(
                  color: sel ? GiftStyle.tint : Colors.white,
                  borderRadius: BorderRadius.circular(16),
                  border: border,
                  boxShadow: sel
                      ? const [
                          BoxShadow(color: Color(0x29845EC9), offset: Offset(0, 4), blurRadius: 12),
                        ]
                      : null,
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: 46,
                      height: 46,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: sel || exclusive != null ? (sel ? Colors.white : null) : GiftStyle.tint,
                        gradient: !sel && exclusive != null ? GiftStyle.exclusiveIconBg(exclusive) : null,
                      ),
                      child: giftIcon(widget.item.iconUrl, size: 32),
                    ),
                    const SizedBox(height: 4),
                    Text(widget.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                            fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.ink)),
                    const SizedBox(height: 1),
                    Text('Rp${formatIdrAmount(widget.item.price)}',
                        style: TextStyle(
                            fontSize: 10.5,
                            color: sel ? AppColors.mint : AppColors.muted,
                            fontWeight: sel ? FontWeight.w600 : FontWeight.w400,
                            fontFeatures: const [FontFeature.tabularFigures()])),
                  ],
                ),
              ),
              if (sel)
                Positioned(
                  top: 5,
                  right: 5,
                  child: Container(
                    key: const ValueKey('giftCellCheck'),
                    width: 16,
                    height: 16,
                    decoration: const BoxDecoration(color: AppColors.mint, shape: BoxShape.circle),
                    child: const Icon(Icons.check, size: 11, color: Colors.white),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 数量胶囊（×1 / ×5 / ×10）。
class _QtyChip extends StatelessWidget {
  const _QtyChip({super.key, required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
        button: true,
        selected: selected,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: onTap,
          child: Container(
            height: 36,
            constraints: const BoxConstraints(minWidth: 48),
            alignment: Alignment.center,
            padding: const EdgeInsets.symmetric(horizontal: 10),
            decoration: BoxDecoration(
              color: selected ? AppColors.mint : Colors.white,
              borderRadius: BorderRadius.circular(999),
              border: Border.all(color: selected ? AppColors.mint : AppColors.line, width: 1.5),
              boxShadow: selected
                  ? const [BoxShadow(color: Color(0x33845EC9), offset: Offset(0, 4), blurRadius: 10)]
                  : null,
            ),
            child: Text(label,
                style: TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w600,
                    color: selected ? Colors.white : AppColors.ink,
                    fontFeatures: const [FontFeature.tabularFigures()])),
          ),
        ),
      );
}

/// 步进器圆钮：视觉 32，点击热区补到 44。到边界置灰、无阴影、不可点。
class _StepButton extends StatelessWidget {
  const _StepButton(
      {super.key, required this.icon, required this.enabled, required this.onTap});

  final IconData icon;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
        button: true,
        enabled: enabled,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: enabled ? onTap : null,
          child: SizedBox(
            width: 44,
            height: 44,
            child: Center(
              child: Container(
                width: 32,
                height: 32,
                decoration: BoxDecoration(
                  color: Colors.white,
                  shape: BoxShape.circle,
                  boxShadow: enabled
                      ? const [BoxShadow(color: Color(0x14000000), offset: Offset(0, 2), blurRadius: 6)]
                      : null,
                ),
                child: Icon(icon, size: 18, color: enabled ? AppColors.ink : GiftStyle.disabled),
              ),
            ),
          ),
        ),
      );
}
