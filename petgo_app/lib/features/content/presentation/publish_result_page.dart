import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import 'publish_compose_page.dart';

/// 发布结果三屏（P-39 成功 / P-39b 审核中 / P-39c 被拒）的共享参数。
///
/// 仅传渲染所需的轻量预览数据（无 PII / 无后端模型）；[reasons] 仅被拒页用。
class PublishResultArgs {
  const PublishResultArgs({
    required this.excerpt,
    required this.typeLabel,
    required this.photoCount,
    this.petEmoji = '🐱',
    this.reasons = const <String>[],
  });

  final String excerpt;
  final String typeLabel;
  final int photoCount;
  final String petEmoji;
  final List<String> reasons;

  PublishResultArgs withReasons(List<String> r) => PublishResultArgs(
        excerpt: excerpt,
        typeLabel: typeLabel,
        photoCount: photoCount,
        petEmoji: petEmoji,
        reasons: r,
      );

  /// DEV 直达样例（深链无 extra 时用，供逐屏视觉验收）。
  static const PublishResultArgs sample = PublishResultArgs(
    excerpt: 'Oyen akhirnya mau makan lagi setelah seminggu...',
    typeLabel: 'Momen Bahagia',
    photoCount: 2,
  );

  static const PublishResultArgs sampleRejected = PublishResultArgs(
    excerpt: 'Oyen akhirnya mau makan lagi setelah seminggu...',
    typeLabel: 'Momen Bahagia',
    photoCount: 2,
    reasons: <String>['__text__', '__image__'],
  );
}

// ─────────────────────────────────────────────────────────────────────────
// P-39 发布成功
// ─────────────────────────────────────────────────────────────────────────

class PublishDonePage extends StatelessWidget {
  const PublishDonePage({super.key, required this.args});

  final PublishResultArgs args;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(28, 24, 28, 28),
          child: Column(
            children: [
              const Spacer(),
              // 圆形对勾图标 + Pop Art 红色偏移层。
              SizedBox(
                width: 92,
                height: 92,
                child: Stack(
                  children: [
                    Transform.translate(
                      offset: const Offset(5, 5),
                      child: Container(
                        width: 84,
                        height: 84,
                        decoration: const BoxDecoration(
                            color: AppColors.popRed, shape: BoxShape.circle),
                      ),
                    ),
                    Container(
                      width: 84,
                      height: 84,
                      decoration: const BoxDecoration(
                          color: AppColors.mint, shape: BoxShape.circle),
                      child: const Icon(Icons.check_rounded, size: 46, color: Colors.white),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 22),
              Text(l10n.publishDoneTitle,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      fontSize: 21, fontWeight: FontWeight.w700, color: AppColors.ink, height: 1.3)),
              const SizedBox(height: 8),
              Text(l10n.publishDoneSubtitle,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
              const SizedBox(height: 24),
              _PreviewCard(
                args: args,
                meta: Row(
                  children: [
                    Text('❤ 0',
                        style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                    const SizedBox(width: 12),
                    Text('💬 0',
                        style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(l10n.publishDoneJustNow,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 11, color: AppColors.mint)),
                    ),
                  ],
                ),
              ),
              const Spacer(),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('publishDoneViewFeed'),
                  onPressed: () => context.go('/home'),
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint,
                    foregroundColor: AppColors.onAccent,
                    elevation: 4,
                    shadowColor: AppColors.mint.withValues(alpha: 0.30),
                    padding: const EdgeInsets.symmetric(vertical: 15),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: Text(l10n.publishDoneViewFeed,
                      style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                ),
              ),
              const SizedBox(height: 8),
              TextButton(
                key: const ValueKey('publishDoneBackHome'),
                onPressed: () => context.go('/home'),
                child: Text(l10n.publishDoneBackHome,
                    style: const TextStyle(fontSize: 13, color: AppColors.muted)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────
// P-39b 审核中（既作发布流程内嵌覆盖层，也作 DEV 直达路由页）
// ─────────────────────────────────────────────────────────────────────────

/// 审核中视觉体（环形进度 + 📋 + 文案 + 预览卡）。发布提交期间在 sheet 内覆盖展示。
class PublishReviewingView extends StatelessWidget {
  const PublishReviewingView({super.key, required this.args});

  final PublishResultArgs args;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      color: AppColors.base,
      padding: const EdgeInsets.fromLTRB(28, 24, 28, 28),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          SizedBox(
            width: 88,
            height: 88,
            child: Stack(
              alignment: Alignment.center,
              children: [
                const SizedBox(
                  width: 88,
                  height: 88,
                  child: CircularProgressIndicator(
                      strokeWidth: 5, color: AppColors.mint, backgroundColor: AppColors.line),
                ),
                const Text('📋', style: TextStyle(fontSize: 30)),
              ],
            ),
          ),
          const SizedBox(height: 22),
          Text(l10n.publishReviewingTitle,
              key: const ValueKey('publishReviewingTitle'),
              style: const TextStyle(
                  fontSize: 20, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: 8),
          Text(l10n.publishReviewingBody,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
          const SizedBox(height: 24),
          _PreviewCard(
            args: args,
            meta: Text('${args.typeLabel} · ${l10n.vetInboxImages(args.photoCount)}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 11, color: AppColors.muted)),
          ),
        ],
      ),
    );
  }
}

/// 审核中路由页（DEV 直达 / 完整性）。真实流程中用 [PublishReviewingView] 内嵌覆盖层。
class PublishReviewingPage extends StatelessWidget {
  const PublishReviewingPage({super.key, required this.args});

  final PublishResultArgs args;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(child: PublishReviewingView(args: args)),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────
// P-39c 内容被拒
// ─────────────────────────────────────────────────────────────────────────

class PublishRejectedPage extends StatelessWidget {
  const PublishRejectedPage({super.key, required this.args});

  final PublishResultArgs args;

  static const Color _danger = AppColors.popRed;
  static const Color _dangerTint = Color(0xFFFDE7EB);
  static const Color _dangerText = Color(0xFFC4263C);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 占位样例（DEV）里用哨兵标记解析为本地化拒因。
    final reasons = args.reasons
        .map((r) => switch (r) {
              '__text__' => l10n.publishRejectedReasonText,
              '__image__' => l10n.publishRejectedReasonImage,
              _ => r,
            })
        .toList();
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(22, 4, 22, 28),
          children: [
            // 顶栏：返回 + 标题。
            Row(
              children: [
                _backBtn(context),
                const SizedBox(width: 12),
                Text(l10n.publishRejectedTitle,
                    style: const TextStyle(
                        fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
              ],
            ),
            const SizedBox(height: 20),
            Column(
              children: [
                Container(
                  width: 72,
                  height: 72,
                  alignment: Alignment.center,
                  decoration: const BoxDecoration(color: _dangerTint, shape: BoxShape.circle),
                  child: const Text('❌', style: TextStyle(fontSize: 30)),
                ),
                const SizedBox(height: 12),
                Text(l10n.publishRejectedHeading,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
                const SizedBox(height: 6),
                Text(l10n.publishRejectedBody,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
              ],
            ),
            const SizedBox(height: 22),
            // 拒因清单（红浅底盒）。
            Container(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
              decoration:
                  BoxDecoration(color: _dangerTint, borderRadius: BorderRadius.circular(14)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(l10n.publishRejectedReasonsLabel,
                      style: const TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 0.4,
                          color: _dangerText)),
                  const SizedBox(height: 10),
                  for (final r in reasons) _reasonItem(r),
                ],
              ),
            ),
            const SizedBox(height: 16),
            _PreviewCard(
              args: args,
              meta: Row(
                children: [
                  Flexible(
                    child: Text('${args.typeLabel} · ${l10n.vetInboxImages(args.photoCount)} · ',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                  ),
                  Text(l10n.publishRejectedTag,
                      style: const TextStyle(
                          fontSize: 11, fontWeight: FontWeight.w700, color: _danger)),
                ],
              ),
            ),
            const SizedBox(height: 22),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const ValueKey('publishRejectedFix'),
                onPressed: () {
                  // 「Perbaiki Konten」：返回并重开发布编辑（V1 无持久草稿，重新填写）。
                  context.pop();
                  PublishComposePage.open(context);
                },
                style: FilledButton.styleFrom(
                  backgroundColor: _danger,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: Text('✏️ ${l10n.publishRejectedFix}',
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
              ),
            ),
            const SizedBox(height: 11),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton(
                onPressed: () => context.canPop() ? context.pop() : context.go('/home'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.textSecondary,
                  side: const BorderSide(color: AppColors.line, width: 1.5),
                  padding: const EdgeInsets.symmetric(vertical: 13),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: Text(l10n.publishCancel, style: const TextStyle(fontSize: 14)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _reasonItem(String text) => Padding(
        padding: const EdgeInsets.only(bottom: 7),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              margin: const EdgeInsets.only(top: 6),
              width: 6,
              height: 6,
              decoration: const BoxDecoration(color: _danger, shape: BoxShape.circle),
            ),
            const SizedBox(width: 9),
            Expanded(
              child: Text(text,
                  style: const TextStyle(fontSize: 13, height: 1.4, color: _dangerText)),
            ),
          ],
        ),
      );

  Widget _backBtn(BuildContext context) => Material(
        color: const Color(0xFFEFEDF3),
        borderRadius: BorderRadius.circular(11),
        child: InkWell(
          key: const ValueKey('publishRejectedBack'),
          borderRadius: BorderRadius.circular(11),
          onTap: () => context.canPop() ? context.pop() : context.go('/home'),
          child: const SizedBox(
            width: 36,
            height: 36,
            child: Icon(Icons.arrow_back, size: 18, color: AppColors.ink2),
          ),
        ),
      );
}

// ─────────────────────────────────────────────────────────────────────────
// 共享预览卡
// ─────────────────────────────────────────────────────────────────────────

class _PreviewCard extends StatelessWidget {
  const _PreviewCard({required this.args, required this.meta});

  final PublishResultArgs args;
  final Widget meta;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(14),
        boxShadow: const [
          BoxShadow(color: Color(0x14162233), blurRadius: 12, offset: Offset(0, 4)),
        ],
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 44,
            height: 44,
            alignment: Alignment.center,
            decoration: BoxDecoration(
                color: AppColors.cream2, borderRadius: BorderRadius.circular(10)),
            child: Text(args.petEmoji, style: const TextStyle(fontSize: 22)),
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(args.excerpt.isEmpty ? '—' : args.excerpt,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink)),
                const SizedBox(height: 5),
                meta,
              ],
            ),
          ),
        ],
      ),
    );
  }
}
