import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/pay_channel_picker.dart' show formatIdrAmount;
import '../data/gift_repository.dart';
import '../domain/gift_catalog.dart';
import '../domain/gift_send_result.dart' show GiftRecipientRef;
import 'gift_panel_sheet.dart' show giftCannotSendSlugs;
import 'gift_style.dart';

/// 选礼物页的结局（V1.5.0 gift-pack Story 2-1；null = 关闭）。
sealed class GiftCatalogResult {
  const GiftCatalogResult();
}

/// 点了某件礼物：本屏不再有选中态，点礼物直接进包装页（2-2）。
class GiftCatalogPicked extends GiftCatalogResult {
  const GiftCatalogPicked(this.catalog, this.item);
  final GiftCatalog catalog;
  final GiftItem item;
}

/// 点了锁区块：调用方进 `/me/growth`。
class GiftCatalogOpenLevelPage extends GiftCatalogResult {
  const GiftCatalogOpenLevelPage();
}

/// 目录接口拒绝（宠物已删 / 自己的宠物 / 被拉黑）：调用方 C9 Toast。
class GiftCatalogCannotSend extends GiftCatalogResult {
  const GiftCatalogCannotSend();
}

/// 目录分组（分类 / 兜底价格档 / 专属等级）。
class GiftCatalogSection {
  const GiftCatalogSection({required this.key, required this.title, required this.gifts, this.exclusiveLevel});

  /// tab 与分组锚点的稳定键：`cat-{id}` / `tier-{price}` / `ex-{level}`。
  final String key;
  final String title;
  final List<GiftItem> gifts;
  final int? exclusiveLevel;
}

/// 分组顺序 = 后端顺序：分类（空分类不渲染）→ 专属（按等级）。后端未部署（`categories` 为空）→ 价格档 + `giftTierName` 兜底。
@visibleForTesting
List<GiftCatalogSection> giftCatalogSections(GiftCatalog c, AppLocalizations l10n, Locale locale) {
  final out = <GiftCatalogSection>[];
  if (c.categories.isNotEmpty) {
    for (final g in c.categories) {
      if (g.gifts.isEmpty) continue;
      out.add(GiftCatalogSection(key: 'cat-${g.categoryId}', title: g.nameFor(locale), gifts: g.gifts));
    }
  } else {
    for (final t in c.sortedTiers) {
      if (t.gifts.isEmpty) continue;
      out.add(GiftCatalogSection(
          key: 'tier-${t.price}', title: giftTierName(l10n, t.price) ?? 'Rp${formatIdrAmount(t.price)}', gifts: t.gifts));
    }
  }
  for (final e in c.sortedExclusiveGroups) {
    if (e.gifts.isEmpty) continue;
    out.add(GiftCatalogSection(
        key: 'ex-${e.minLevel}', title: l10n.giftExclusiveGroup(e.minLevel), gifts: e.gifts, exclusiveLevel: e.minLevel));
  }
  return out;
}

/// 「dari …」一行：最多 3 人 + 「+N」；注销送礼人显示 `feedDeletedUser`。
@visibleForTesting
String? giftReceivedFromText(GiftReceivedFrom? rf, AppLocalizations l10n) {
  if (rf == null || rf.senders.isEmpty) return null;
  final names = rf.senders.map((s) => s.deleted || s.nickname == null ? l10n.feedDeletedUser : s.nickname!).join(', ');
  return rf.more > 0 ? l10n.giftReceivedFromMore(names, rf.more) : l10n.giftReceivedFrom(names);
}

/// 选礼物页（V1.5.0 gift-pack Story 2-1 · UI 稿 B1 ~ B5，整页）。
///
/// 结局（pop 值）：[GiftCatalogResult] 或 null（关闭）。
class GiftCatalogPage extends ConsumerStatefulWidget {
  const GiftCatalogPage({super.key, required this.recipient, this.onCatalogShown});

  final GiftRecipientRef recipient;
  final ValueChanged<GiftCatalog>? onCatalogShown;

  @override
  ConsumerState<GiftCatalogPage> createState() => _GiftCatalogPageState();
}

class _GiftCatalogPageState extends ConsumerState<GiftCatalogPage> {
  final _scroll = ScrollController();
  final Map<String, GlobalKey> _anchors = {};
  String? _activeTab;
  bool _closing = false;
  bool _shownReported = false;
  bool _jumping = false;

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  GlobalKey _anchor(String key) => _anchors.putIfAbsent(key, GlobalKey.new);

  void _pop(GiftCatalogResult? r) {
    if (_closing) return;
    _closing = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) Navigator.of(context).pop(r);
    });
  }

  Future<void> _jumpTo(String key) async {
    setState(() => _activeTab = key);
    final ctx = _anchors[key]?.currentContext;
    if (ctx == null) return;
    _jumping = true;
    await Scrollable.ensureVisible(ctx, duration: const Duration(milliseconds: 250), alignment: 0);
    _jumping = false;
  }

  /// 手动滑动时 tab 跟随当前可见分组：取顶部已越过视口顶的最后一个分组。
  void _syncTab(List<GiftCatalogSection> sections) {
    if (_jumping || sections.isEmpty) return;
    String current = sections.first.key;
    for (final s in sections) {
      final ctx = _anchors[s.key]?.currentContext;
      final box = ctx?.findRenderObject() as RenderBox?;
      if (box == null || !box.attached) continue;
      final viewport = context.findRenderObject() as RenderBox?;
      final dy = box.localToGlobal(Offset.zero, ancestor: viewport).dy;
      if (dy <= 160) current = s.key;
    }
    if (current != _activeTab) setState(() => _activeTab = current);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(giftCatalogProvider(widget.recipient));
    GiftRecipient? recipient;
    final Widget body;
    if (async.hasValue && !async.isLoading) {
      final catalog = async.requireValue;
      recipient = catalog.recipient;
      final sections = giftCatalogSections(catalog, l10n, Localizations.localeOf(context));
      if (sections.isEmpty) {
        body = _empty(l10n);
      } else {
        if (!_shownReported) {
          _shownReported = true;
          widget.onCatalogShown?.call(catalog);
        }
        body = _loaded(l10n, catalog, sections);
      }
    } else if (async.hasError && !async.isLoading) {
      final err = async.error;
      final slug = err is DioException ? ProblemDetail.fromDioException(err)?.typeSlug : null;
      if (slug != null && giftCannotSendSlugs.contains(slug)) {
        _pop(const GiftCatalogCannotSend());
        body = const SizedBox.shrink();
      } else {
        body = _failed(l10n);
      }
    } else {
      body = const Center(
        key: ValueKey('giftCatalogLoading'),
        child: SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 3, color: AppColors.mint)),
      );
    }
    return Scaffold(
      backgroundColor: AppColors.card,
      appBar: AppBar(
        backgroundColor: AppColors.card,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        leading: IconButton(
          key: const ValueKey('giftCatalogClose'),
          icon: const Icon(Icons.close, color: AppColors.ink),
          onPressed: () => Navigator.of(context).pop(),
        ),
        titleSpacing: 0,
        title: recipient == null ? null : _title(l10n, recipient),
        actions: [if (recipient != null) _avatar(recipient)],
      ),
      body: body,
    );
  }

  Widget _title(AppLocalizations l10n, GiftRecipient r) {
    final species = switch (r.petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
    final sub = [?species, if (r.breed != null && r.breed!.isNotEmpty) r.breed!].join(' · ');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(l10n.giftPanelTitle(r.name),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
        if (sub.isNotEmpty)
          Text(sub, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
      ],
    );
  }

  Widget _avatar(GiftRecipient r) => Padding(
        padding: const EdgeInsets.only(right: 16),
        child: Container(
          width: 32,
          height: 32,
          padding: const EdgeInsets.all(2),
          decoration: const BoxDecoration(shape: BoxShape.circle, color: AppColors.mint),
          child: Container(
            padding: const EdgeInsets.all(1.5),
            decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.white),
            child: CircleAvatar(
              backgroundColor: GiftStyle.tint,
              backgroundImage: AppImage.provider(r.avatarUrl, thumbWidth: 96),
            ),
          ),
        ),
      );

  Widget _loaded(AppLocalizations l10n, GiftCatalog catalog, List<GiftCatalogSection> sections) {
    final locale = Localizations.localeOf(context);
    final active = _activeTab ?? sections.first.key;
    return Column(
      key: const ValueKey('giftCatalogLoaded'),
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          height: 40,
          child: ListView(
            key: const ValueKey('giftCatalogTabs'),
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            children: [
              for (final s in sections)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
                  child: ChoiceChip(
                    key: ValueKey('giftCatalogTab-${s.key}'),
                    label: Text(s.title),
                    selected: s.key == active,
                    showCheckmark: false,
                    selectedColor: AppColors.mintTint,
                    labelStyle: TextStyle(
                        fontSize: 12.5,
                        fontWeight: s.key == active ? FontWeight.w700 : FontWeight.w500,
                        color: s.key == active ? AppColors.mint : AppColors.ink),
                    onSelected: (_) => _jumpTo(s.key),
                  ),
                ),
            ],
          ),
        ),
        Expanded(
          child: NotificationListener<ScrollUpdateNotification>(
            onNotification: (_) {
              _syncTab(sections);
              return false;
            },
            child: SingleChildScrollView(
              key: const ValueKey('giftCatalogGrid'),
              controller: _scroll,
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 24),
              child: LayoutBuilder(builder: (context, c) {
                final cellW = ((c.maxWidth - 8 * 3) / 4).floorToDouble();
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (final s in sections) ...[
                      _header(s),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: [
                          for (final g in s.gifts)
                            SizedBox(
                              width: cellW,
                              child: GiftCatalogCell(
                                key: ValueKey('giftCell-${g.giftToken}'),
                                item: g,
                                name: g.nameFor(locale),
                                exclusiveLevel: s.exclusiveLevel,
                                onTap: () => Navigator.of(context).pop(GiftCatalogPicked(catalog, g)),
                              ),
                            ),
                        ],
                      ),
                    ],
                    if (catalog.lockBlock != null) _lockBlock(l10n, catalog.lockBlock!.requiredLevel),
                  ],
                );
              }),
            ),
          ),
        ),
      ],
    );
  }

  Widget _header(GiftCatalogSection s) {
    final level = s.exclusiveLevel;
    return Padding(
      key: _anchor(s.key),
      padding: const EdgeInsets.fromLTRB(2, 14, 2, 8),
      child: level == null
          ? Text(s.title,
              key: ValueKey('giftCatalogSection-${s.key}'),
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink))
          : Align(
              alignment: Alignment.centerLeft,
              child: Container(
                key: ValueKey('giftCatalogSection-${s.key}'),
                padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
                decoration: BoxDecoration(
                  gradient: GiftStyle.levelGradient(level),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  const Icon(Icons.workspace_premium, size: 12, color: Colors.white),
                  const SizedBox(width: 3),
                  Text(s.title,
                      style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Colors.white)),
                ]),
              ),
            ),
    );
  }

  /// 锁区块：点击 → 关页 → 调用方进 `/me/growth`（线上不变）。
  Widget _lockBlock(AppLocalizations l10n, int level) => Padding(
        padding: const EdgeInsets.fromLTRB(0, 16, 0, 4),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            key: const ValueKey('giftLockBlock'),
            borderRadius: BorderRadius.circular(16),
            onTap: () {
              Analytics.capture('gift_locked_block_tapped', {'required_level': level});
              Navigator.of(context).pop(const GiftCatalogOpenLevelPage());
            },
            child: Ink(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(16),
                gradient: const LinearGradient(colors: [Color(0xFFF3EDFF), Color(0xFFFBF8FF)]),
              ),
              child: Row(children: [
                const Icon(Icons.lock_outline, color: AppColors.mint),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(l10n.giftLockTitle(level),
                        style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink)),
                    Text(l10n.giftLockSubtitle, style: const TextStyle(fontSize: 11.5, color: AppColors.muted)),
                  ]),
                ),
                const Icon(Icons.chevron_right, color: AppColors.muted),
              ]),
            ),
          ),
        ),
      );

  /// B5：加载失败 + 重试（顶栏仍可关闭）。
  Widget _failed(AppLocalizations l10n) => Center(
        key: const ValueKey('giftCatalogFailed'),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          const Icon(Icons.cloud_off, size: 48, color: AppColors.muted),
          const SizedBox(height: 12),
          Text(l10n.giftLoadFailed,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600, color: AppColors.ink)),
          const SizedBox(height: 16),
          OutlinedButton(
            key: const ValueKey('giftCatalogRetry'),
            onPressed: () => ref.invalidate(giftCatalogProvider(widget.recipient)),
            style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.mint,
              side: const BorderSide(color: AppColors.mint, width: 1.5),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
            ),
            child: Text(l10n.commonRetry),
          ),
        ]),
      );

  Widget _empty(AppLocalizations l10n) => Center(
        key: const ValueKey('giftCatalogEmpty'),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Container(
              width: 80,
              height: 80,
              alignment: Alignment.center,
              decoration: const BoxDecoration(shape: BoxShape.circle, color: GiftStyle.tint),
              child: const Icon(Icons.redeem, size: 36, color: AppColors.muted),
            ),
            const SizedBox(height: 14),
            Text(l10n.giftCatalogEmptyTitle,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const SizedBox(height: 6),
            Text(l10n.giftCatalogEmptyHint,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, height: 1.5, color: AppColors.textSecondary)),
          ]),
        ),
      );
}

/// 礼物格（B1 / B3）：图标 + 名称 +「{n} PawCoin」+「+{n} EXP」（EXP 为 0 不显示）；收到过 → 紫色「dari …」（最多两行）。
class GiftCatalogCell extends StatelessWidget {
  const GiftCatalogCell({super.key, required this.item, required this.name, required this.onTap, this.exclusiveLevel});

  final GiftItem item;
  final String name;
  final VoidCallback onTap;
  final int? exclusiveLevel;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final from = giftReceivedFromText(item.receivedFrom, l10n);
    final level = exclusiveLevel;
    return Semantics(
      button: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.fromLTRB(2, 8, 2, 7),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: level != null ? GiftStyle.exclusiveBorder : GiftStyle.cellBorder,
                width: level != null ? 1.5 : 1),
          ),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Container(
              width: 46,
              height: 46,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: level == null ? GiftStyle.tint : null,
                gradient: level != null ? GiftStyle.exclusiveIconBg(level) : null,
              ),
              child: giftIcon(item.iconUrl, size: 32),
            ),
            const SizedBox(height: 4),
            Text(name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.ink)),
            const SizedBox(height: 1),
            Text(l10n.giftCatalogPrice(formatIdrAmount(item.price)),
                key: const ValueKey('giftCellPrice'),
                style: const TextStyle(
                    fontSize: 10.5, color: AppColors.muted, fontFeatures: [FontFeature.tabularFigures()])),
            if (from != null)
              Text(from,
                  key: const ValueKey('giftCellFrom'),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 10, color: AppColors.mint, fontWeight: FontWeight.w600))
            else if (item.senderExp > 0)
              Text(l10n.giftCatalogExp(item.senderExp),
                  key: const ValueKey('giftCellExp'),
                  style: const TextStyle(fontSize: 10, color: AppColors.mint, fontWeight: FontWeight.w600)),
          ]),
        ),
      ),
    );
  }
}
