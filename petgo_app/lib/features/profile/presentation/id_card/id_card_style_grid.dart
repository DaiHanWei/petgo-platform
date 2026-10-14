import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../domain/id_card_style.dart';

/// 「Pilih Kartu / Create new」样式网格（V1.3.4 ktp Story 1.5，FR-142B.2，UI 稿 B1）。
///
/// 标题句 → 「Kartu Identitas」4 格 → 「Kartu Pelajar」4 格；分组、组内顺序、名称、缩略图、new 全部取自
/// 注册表 [IdCardStyle]（AD-6），页面不另写列表。缩略图是静态图（D-2 / AD-8），不按当前宠物实时渲染。
/// 不依赖卡列表接口：列表加载中 / 失败时网格照常显示。
class IdCardStyleGrid extends StatelessWidget {
  const IdCardStyleGrid({super.key, required this.onTap, this.clock});

  final void Function(IdCardStyle style) onTap;

  /// 测试注入当前时间（判定 `new` 角标是否已过截止）；生产为 null = `DateTime.now()`。
  @visibleForTesting
  final DateTime Function()? clock;

  /// 某组按注册表顺序的卡款（枚举顺序 = 网格顺序）。
  static List<IdCardStyle> stylesOf(IdCardStyleGroup group) =>
      IdCardStyle.values.where((s) => s.group == group).toList();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final now = clock?.call();
    return ColoredBox(
      color: AppColors.cream2,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 24),
        children: [
          // T-13：标题句放在第一个组标题上方（UI 稿 B1 未画）。
          Text(l10n.idCardStyleGridTitle,
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink)),
          const SizedBox(height: 14),
          _groupTitle(l10n.idCardStyleGroupIdentity),
          _grid(stylesOf(IdCardStyleGroup.identity), l10n, now),
          const SizedBox(height: 18),
          _groupTitle(l10n.idCardStyleGroupStudent),
          _grid(stylesOf(IdCardStyleGroup.student), l10n, now),
        ],
      ),
    );
  }

  Widget _groupTitle(String text) => Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Text(text,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.ink2)),
      );

  Widget _grid(List<IdCardStyle> styles, AppLocalizations l10n, DateTime? now) {
    final rows = <Widget>[];
    for (var i = 0; i < styles.length; i += 2) {
      if (rows.isNotEmpty) rows.add(const SizedBox(height: 10));
      rows.add(IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(child: _cell(styles[i], l10n, now)),
            const SizedBox(width: 10),
            Expanded(child: i + 1 < styles.length ? _cell(styles[i + 1], l10n, now) : const SizedBox()),
          ],
        ),
      ));
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: rows);
  }

  Widget _cell(IdCardStyle style, AppLocalizations l10n, DateTime? now) {
    final showNew = idCardStyleShowsNewBadge(style, now: now);
    return Stack(
      clipBehavior: Clip.none,
      fit: StackFit.passthrough,
      children: [
        Container(
          decoration: BoxDecoration(
            color: AppColors.card,
            borderRadius: BorderRadius.circular(16),
            boxShadow: const [
              BoxShadow(color: Color(0x0F2E2A45), blurRadius: 2, offset: Offset(0, 1)),
              BoxShadow(color: Color(0x0A2E2A45), blurRadius: 12, offset: Offset(0, 4)),
            ],
          ),
          child: Material(
            color: Colors.transparent,
            borderRadius: BorderRadius.circular(16),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              key: ValueKey('idCardStyleCell_${style.name}'),
              onTap: () => onTap(style),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(8, 8, 8, 10),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    AspectRatio(aspectRatio: 994 / 600, child: _thumbnail(style)),
                    const SizedBox(height: 8),
                    Text(
                      style.label(l10n),
                      textAlign: TextAlign.center,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
        if (showNew)
          Positioned(
            top: -5,
            right: 8,
            child: IgnorePointer(
              child: Container(
                key: ValueKey('idCardStyleNewBadge_${style.name}'),
                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                decoration: BoxDecoration(
                  color: AppColors.danger,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  l10n.insightNewBadge,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 10,
                    fontWeight: FontWeight.w600,
                    height: 1.2,
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }

  /// 缩略图：8 圆角 + 1px 低透明内描边；护照画布非 16:10，按区高等高、水平居中（UX-DR2）。
  /// 素材缺失回落为同尺寸 mintTint 圆角块，不崩。
  Widget _thumbnail(IdCardStyle style) {
    final fallback = Container(
      decoration: BoxDecoration(color: AppColors.mintTint, borderRadius: BorderRadius.circular(8)),
    );
    final aspect = style.canvas.width / style.canvas.height;
    return Center(
      child: AspectRatio(
        aspectRatio: aspect,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: Stack(
            fit: StackFit.expand,
            children: [
              Image.asset(style.thumbnailAsset, fit: BoxFit.cover, errorBuilder: (_, _, _) => fallback),
              IgnorePointer(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: const Color(0x14000000)),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
