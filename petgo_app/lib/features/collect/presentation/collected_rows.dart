import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/dashed_rect.dart';
import '../../../shared/widgets/initial_avatar.dart';
import '../../../shared/widgets/post_cover.dart';
import '../../../shared/widgets/post_grid_tile.dart';
import '../../place/domain/place_summary.dart';
import '../../place/presentation/place_labels.dart';
import '../domain/collected_item.dart';

/// 缩略图边长与圆角（UI 稿 E2 / E6）。
const double _thumb = 64;
const double _thumbRadius = 12;

/// UI 稿 `.lk2`：不可见场所灰色占位底。
const Color _greyPlaceholder = Color(0xFFF4F3F7);

/// 「我的 → Disimpan」帖子行（V1.4.0 ④ Story 2.4 · UI 稿 E2 / E3）。
///
/// 🔴 不可见态**不显示**原正文 / 作者 / 封面 / 类型（接口本就不给，也不得从其它缓存补）。
class CollectedPostRowView extends StatelessWidget {
  const CollectedPostRowView({super.key, required this.item, required this.onTap});

  final CollectedPost item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final p = item.showsAsAvailable ? item.post : null;
    return _RowShell(
      key: ValueKey('collectedPostRow_${item.postId}'),
      onTap: onTap,
      thumb: p == null ? const _UnavailablePostThumb() : _PostThumb(summary: p),
      body: p == null
          ? _UnavailableText(title: l10n.collectUnavailablePost, titleWeight: FontWeight.w500)
          : _PostText(summary: p),
    );
  }
}

/// 「我的 → Disimpan」场所行（V1.4.0 ④ Story 2.4 · UI 稿 E6 / E7）。不显示距离、标签、计数。
class CollectedPlaceRowView extends StatelessWidget {
  const CollectedPlaceRowView({super.key, required this.item, required this.onTap});

  final CollectedPlace item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final type = PlaceType.fromApi(item.type);
    final address = item.addressText?.trim();
    return _RowShell(
      key: ValueKey('collectedPlaceRow_${item.placeToken}'),
      onTap: onTap,
      thumb: item.available ? _PlaceThumb(url: item.coverUrl) : const _UnavailablePlaceThumb(),
      body: item.available
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(item.name,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink)),
                // 未知类型整行省掉，不兜底「其他」（同 place_summary 的约定）。
                if (type != null) ...[
                  const SizedBox(height: 3),
                  Text(type.label(l10n), style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
                ],
                if (address != null && address.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(address,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                ],
              ],
            )
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                // 不可见场所保留名称（置灰）。
                Text(item.name,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500, color: AppColors.muted)),
                const SizedBox(height: 3),
                _UnavailableText(title: l10n.collectUnavailablePlace, titleSize: 12, titleWeight: FontWeight.w400),
              ],
            ),
    );
  }
}

class _RowShell extends StatelessWidget {
  const _RowShell({super.key, required this.onTap, required this.thumb, required this.body});

  final VoidCallback onTap;
  final Widget thumb;
  final Widget body;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Container(
        decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: AppColors.line2))),
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          children: [
            SizedBox(width: _thumb, height: _thumb, child: thumb),
            const SizedBox(width: 12),
            Expanded(child: body),
          ],
        ),
      ),
    );
  }
}

/// 缩略图外框：圆角 12 + 1px `lineViolet` 前景描边（照场所列表 `_Thumb`）。
Widget _framed(Widget child) => ClipRRect(
      borderRadius: BorderRadius.circular(_thumbRadius),
      child: Container(
        foregroundDecoration: BoxDecoration(
          borderRadius: BorderRadius.circular(_thumbRadius),
          border: Border.all(color: AppColors.lineViolet),
        ),
        child: child,
      ),
    );

class _PostThumb extends StatelessWidget {
  const _PostThumb({required this.summary});

  final CollectedPostSummary summary;

  @override
  Widget build(BuildContext context) {
    final url = summary.coverUrl;
    final placeholder = PostCoverPlaceholder(type: summary.type, emojiSize: 24);
    return _framed(url == null || url.isEmpty
        ? placeholder
        : AppImage.widget(url, fit: BoxFit.cover, thumbWidth: 160, errorBuilder: (_, _, _) => placeholder));
  }
}

class _PlaceThumb extends StatelessWidget {
  const _PlaceThumb({required this.url});

  final String? url;

  @override
  Widget build(BuildContext context) {
    const placeholder = ColoredBox(
      color: AppColors.cream2,
      child: Center(child: Icon(Icons.place_outlined, size: 22, color: AppColors.textTertiary)),
    );
    final u = url;
    return _framed(u == null || u.isEmpty
        ? placeholder
        : AppImage.widget(u, fit: BoxFit.cover, errorBuilder: (_, _, _) => placeholder));
  }
}

/// 不可见帖子缩略图：白底 + 1.5px 虚线 + 居中「看不见」图标（UI 稿 E3）。
class _UnavailablePostThumb extends StatelessWidget {
  const _UnavailablePostThumb();

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      key: const ValueKey('collectedUnavailableThumb'),
      foregroundPainter: DashedRRectPainter(color: AppColors.lineViolet, radius: _thumbRadius, strokeWidth: 1.5),
      child: DecoratedBox(
        decoration: BoxDecoration(color: AppColors.card, borderRadius: BorderRadius.circular(_thumbRadius)),
        child: const Center(child: Icon(Icons.visibility_off_outlined, size: 22, color: AppColors.dashedViolet)),
      ),
    );
  }
}

/// 不可见场所缩略图：灰色半透明占位块（接口不下发封面——下架可能正因图片违规；用占位表达「图片置灰」）。
class _UnavailablePlaceThumb extends StatelessWidget {
  const _UnavailablePlaceThumb();

  @override
  Widget build(BuildContext context) {
    return Opacity(
      key: const ValueKey('collectedUnavailableThumb'),
      opacity: 0.45,
      child: DecoratedBox(
        decoration: BoxDecoration(color: _greyPlaceholder, borderRadius: BorderRadius.circular(_thumbRadius)),
        child: const Center(child: Icon(Icons.place_outlined, size: 22, color: AppColors.muted)),
      ),
    );
  }
}

class _PostText extends StatelessWidget {
  const _PostText({required this.summary});

  final CollectedPostSummary summary;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final (label, fg, bg) = PostGridTile.badgeStyle(summary.type, l10n);
    final excerpt = summary.excerpt?.trim();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          excerpt == null || excerpt.isEmpty ? label : excerpt,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink, height: 1.35),
        ),
        const SizedBox(height: 3),
        Row(
          children: [
            InitialAvatar(avatarUrl: summary.authorAvatarUrl, nickname: summary.authorNickname, radius: 9),
            const SizedBox(width: 6),
            Flexible(
              child: Text(summary.authorNickname,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
            ),
            const Text(' · ', style: TextStyle(fontSize: 12, color: AppColors.muted)),
            Container(
              key: const ValueKey('collectedPostTypeBadge'),
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(5)),
              child: Text(label, style: TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: fg)),
            ),
          ],
        ),
      ],
    );
  }
}

class _UnavailableText extends StatelessWidget {
  const _UnavailableText({required this.title, this.titleSize = 14, required this.titleWeight});

  final String title;
  final double titleSize;
  final FontWeight titleWeight;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(title, style: TextStyle(fontSize: titleSize, fontWeight: titleWeight, color: AppColors.muted)),
        const SizedBox(height: 3),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.bookmark_remove_outlined, size: 14, color: AppColors.mint),
            const SizedBox(width: 4),
            Text(l10n.collectTapToRemove,
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.mint)),
          ],
        ),
      ],
    );
  }
}
