import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/letter_avatar.dart';
import '../data/shared_post_repository.dart';
import '../domain/content_type_badge.dart';
import '../domain/shared_post.dart';

/// 分享链接在 **App 内**的落地页（Story 9.3 · AD-15 Rule 5）。
///
/// 🔴 **只有被分享的那一条。** 页面上没有、也拿不到任何通往该宠物其它内容的路径：
/// 没有档案入口、没有作者主页、没有「看更多」。这不是靠"忍着不放链接"实现的 ——
/// [SharedPost] 这个投影里压根没有 postId / authorId / petId / cardToken。
///
/// 🔴 **与 `/pet/{token}`（名片分享落点）是两个不同的落地页**。那边是整本档案的只读视图；
/// 复用同一落点等于把「我只想分享一条」变成「我把整本都给你了」。
///
/// ⚠️ **不要求登录**（AC3）：未登录访客直接看。要求登录会把人推回浏览器。
/// 所以本路由**不能**进 `_controlledLocations`。
class SharedPostPage extends ConsumerStatefulWidget {
  const SharedPostPage({super.key, required this.shareToken});

  final String shareToken;

  @override
  ConsumerState<SharedPostPage> createState() => _SharedPostPageState();
}

class _SharedPostPageState extends ConsumerState<SharedPostPage> {
  Future<SharedPost>? _future;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 首帧建 future（需要 l10n 拿注销占位名，故不放 initState）。
    _future ??= ref
        .read(sharedPostRepositoryProvider)
        .getSharedPost(widget.shareToken, fallbackAuthorName: l10n.feedDeletedUser);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.sharedPostTitle)),
      body: FutureBuilder<SharedPost>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snap.hasError || !snap.hasData) {
            // 失效一律同一文案：不区分"不存在 / 已删 / 作者注销"（与服务端同口径，防枚举）。
            return Center(
              key: const ValueKey('sharedPostGone'),
              child: EmptyState(
                icon: Icons.link_off_rounded,
                title: l10n.sharedPostGone,
              ),
            );
          }
          return _view(context, snap.data!, l10n);
        },
      ),
    );
  }

  Widget _view(BuildContext context, SharedPost post, AppLocalizations l10n) {
    final badge = ContentTypeBadge.of(post.type, l10n);
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.lg),
      children: [
        Row(
          children: [
            // 🛡 头像**不可点** —— 点进去就是"由此进入别人的档案"，正是本 story 要挡的。
            LetterAvatar(
              url: post.authorAvatarUrl,
              name: post.authorName,
              deleted: post.authorDeleted,
            ),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(post.authorName,
                  maxLines: 1, overflow: TextOverflow.ellipsis, style: AppTypography.title),
            ),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
              decoration:
                  BoxDecoration(color: badge.bg, borderRadius: BorderRadius.circular(7)),
              child: Text(badge.label,
                  style: TextStyle(
                      fontSize: 11, fontWeight: FontWeight.w700, color: badge.fg)),
            ),
          ],
        ),
        if ((post.body ?? '').isNotEmpty) ...[
          const SizedBox(height: AppSpacing.md),
          Text(post.body!, style: AppTypography.body),
        ],
        for (final url in post.imageUrls) ...[
          const SizedBox(height: AppSpacing.md),
          ClipRRect(
            borderRadius: BorderRadius.circular(14),
            child: AppImage.widget(url, fit: BoxFit.cover),
          ),
        ],
      ],
    );
  }
}
