import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../profile/domain/card_link.dart';
import '../../profile/domain/share_service.dart';
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
    this.isPrivate = false,
    this.cardToken,
    this.petName,
  });

  final String excerpt;
  final String typeLabel;
  final int photoCount;
  final String petEmoji;
  final List<String> reasons;

  /// 宠物成长册的不可枚举对外 token（留存手册抓手 2）。
  ///
  /// 有它才渲染成功页的分享 CTA。手册指出的浪费就在这儿：106 人发布过内容，
  /// 只有 15 人触发过分享——分享链路根本没接上，用户得自己摸到成长档案页的 FAB 才找得到。
  /// 而分享落地页是**已验证 70% 注册转化**的唯一有效增长通道。
  /// 拿不到（无档案 / 取档案失败）→ 不渲染按钮，绝不阻断发布成功页。
  final String? cardToken;

  /// 宠物名，只用于分享 CTA 文案（"把 Mochi 的成长册分享给朋友"）。
  final String? petName;

  /// 私密保存（Diary 关掉「同步到 Moment」，PR#34 finding #11）：成功页不得再用
  /// 社区口吻 +「去 Social 看」CTA——该内容永远不进公开 Feed，用户会翻找无果误以为失败。
  final bool isPrivate;

  PublishResultArgs withReasons(List<String> r) => PublishResultArgs(
        excerpt: excerpt,
        typeLabel: typeLabel,
        photoCount: photoCount,
        petEmoji: petEmoji,
        reasons: r,
        isPrivate: isPrivate,
        cardToken: cardToken,
        petName: petName,
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

class PublishDonePage extends ConsumerWidget {
  const PublishDonePage({super.key, required this.args});

  final PublishResultArgs args;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    // 私密保存（finding #11）：换标题/副文案、CTA 改「去 Diary 看」；不渲染 ❤/💬 社区 meta。
    final private = args.isPrivate;
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
              Text(private ? l10n.publishDonePrivateTitle : l10n.publishDoneTitle,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      fontSize: 21, fontWeight: FontWeight.w700, color: AppColors.ink, height: 1.3)),
              const SizedBox(height: 8),
              Text(private ? l10n.publishDonePrivateSubtitle : l10n.publishDoneSubtitle,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
              const SizedBox(height: 24),
              _PreviewCard(
                args: args,
                meta: private
                    // 私密：无社区互动，❤/💬 只会误导；改类型 + 图片数（与审核中页同款 meta）。
                    ? Text('${args.typeLabel} · ${l10n.vetInboxImages(args.photoCount)}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 11, color: AppColors.muted))
                    : Row(
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
                  // 私密内容永不进 Social Feed → 主 CTA 改「去 Diary 看」（finding #11）。
                  onPressed: () => context.go(private ? '/profile' : '/home'),
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint,
                    foregroundColor: AppColors.onAccent,
                    elevation: 4,
                    shadowColor: AppColors.mint.withValues(alpha: 0.30),
                    padding: const EdgeInsets.symmetric(vertical: 15),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: Text(
                      private ? l10n.publishDonePrivateViewDiary : l10n.publishDoneViewFeed,
                      style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                ),
              ),
              const SizedBox(height: 8),
              // 分享飞轮（留存手册抓手 2）：**当场**给可分享的成长册，别等用户自己
              // 去成长档案页摸那个 FAB。分享出去 → 朋友点进来 → 看到真实日记 → 注册，
              // 这条链路已实测 70% 转化，是目前唯一有效的增长通道。
              // 私密保存也照给：分享的是宠物的成长册主页，不是刚才这条内容。
              if (args.cardToken != null) _ShareGrowthBookButton(args: args),
              // 私密时主 CTA 已指向 Diary，次按钮再指 Diary 就重复 → 不渲染。
              if (!private)
                TextButton(
                  key: const ValueKey('publishDoneBackHome'),
                  // 「Back to Diary」→ Diary Tab（2026-08-05 用户反馈）。原先两个按钮都去 `/home`，
                  // 次按钮等于主按钮的重复，用户没有回自己档案的出口。
                  // ⚠️ 文案与目的地绑定：改其一必须改另一（key 名保留 BackHome 只为不动测试锚点）。
                  onPressed: () => context.go('/profile'),
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

/// 发布成功页的「分享成长册」CTA（留存手册抓手 2）。
///
/// 分享的是 `/p/{cardToken}` 宠物成长册 H5——**不是**刚发的这条内容。
/// 选它而不是新造一张卡的原因：这个页面是诊断报告里唯一验证过的增长通道
/// （落地→注册 70%），而新造的分享物要重新验证一遍转化。
///
/// ⚠️ iOS 必须把按钮矩形当分享面板锚点传下去，否则 iPad 崩、iPhone 点了没反应
/// （bug 20260707 成长档案分享按钮就栽在这）。
class _ShareGrowthBookButton extends ConsumerStatefulWidget {
  const _ShareGrowthBookButton({required this.args});

  final PublishResultArgs args;

  @override
  ConsumerState<_ShareGrowthBookButton> createState() => _ShareGrowthBookButtonState();
}

class _ShareGrowthBookButtonState extends ConsumerState<_ShareGrowthBookButton> {
  bool _sharing = false;

  Future<void> _share() async {
    final token = widget.args.cardToken;
    if (token == null || _sharing) return;
    setState(() => _sharing = true);
    Analytics.capture('publish_done_share_tapped', {
      'is_private': widget.args.isPrivate,
    });
    try {
      final box = context.findRenderObject() as RenderBox?;
      final origin = box != null && box.hasSize
          ? box.localToGlobal(Offset.zero) & box.size
          : null;
      await ref.read(shareServiceProvider)(
        petCardShareUrl(token),
        sharePositionOrigin: origin,
      );
    } catch (_) {
      // 分享失败不做任何打扰：用户刚发布成功，此刻弹错误吐司只会冲淡正反馈。
    } finally {
      if (mounted) setState(() => _sharing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final name = widget.args.petName;
    return SizedBox(
      width: double.infinity,
      child: OutlinedButton.icon(
        key: const ValueKey('publishDoneShareGrowthBook'),
        onPressed: _sharing ? null : _share,
        icon: const Icon(Icons.ios_share_rounded, size: 18),
        style: OutlinedButton.styleFrom(
          foregroundColor: AppColors.ink,
          side: const BorderSide(color: AppColors.mint, width: 1.5),
          padding: const EdgeInsets.symmetric(vertical: 13),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        ),
        label: Text(
          name == null || name.isEmpty
              ? l10n.publishDoneShareGrowthBook
              : l10n.publishDoneShareGrowthBookNamed(name),
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
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
