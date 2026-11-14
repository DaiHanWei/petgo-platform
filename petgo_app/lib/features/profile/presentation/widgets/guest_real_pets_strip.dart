import 'package:flutter/gestures.dart' show kTouchSlop;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../core/theme/colors.dart';
import '../../../../core/theme/spacing.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../social/presentation/widgets/pet_follow_button.dart';
import '../../data/pet_recommendation_repository.dart';
import 'recommend_like_pill.dart';
import 'recommended_pet_card.dart';

/// 游客登录引导的 `entry_source`（V1.5.0 onboarding-recall Story 1.3 · AC3：新值）。
const String kGuestRealPetsEntrySource = 'guest_real_pets';

/// 游客 Diary 展示页的「真实宠物」横滑区（V1.5.0 onboarding-recall Story 1.3 · FR-175 · UI 稿 B1 ~ B4）。
///
/// - 数据：`GET /api/v1/public/pet-recommendations?limit=6`（全站段，服务端 10 分钟快照；App 不缓存）。
/// - **失败 / 空 → 整块（含标题）不渲染**，不出报错条；加载中 B2 骨架。展示页其余部分照旧零网络（AC4）。
/// - 卡片复用 [RecommendedPetCard]（宽 140）：点大图 → `/content/{coverPostId}`（游客可看；无封面帖点图无动作），
///   头像 / 名字沿用卡片现有点击（`requireLogin` 后进宠物页）；点赞胶囊 / 追更按钮 → 现有硬登录，
///   `entry_source = guest_real_pets`，登录后**不**自动补做（AC3，沿用两个按钮的游客行为）。
class GuestRealPetsStrip extends ConsumerStatefulWidget {
  const GuestRealPetsStrip({super.key});

  /// 卡宽（UI 稿 B1）。
  static const double cardWidth = 140;

  /// 卡下追更区高度（热区 44 + 上间距）。
  static const double footerExtent = 44 + AppSpacing.xxs;

  @override
  ConsumerState<GuestRealPetsStrip> createState() => _GuestRealPetsStripState();
}

class _GuestRealPetsStripState extends ConsumerState<GuestRealPetsStrip> {
  bool _viewedReported = false;

  void _reportViewed(int count) {
    if (_viewedReported) return;
    _viewedReported = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      Analytics.capture('guest_real_pets_viewed', {'card_count': count});
    });
  }

  void _reportTap(String action, RecommendedPet pet, int position) {
    Analytics.capture('guest_real_pets_tapped', {
      'action': action,
      'pet_id': pet.petId,
      'position': position,
    });
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(guestRealPetsProvider);
    if (async.isLoading && !async.hasValue) return _frame(context, _skeleton(context));
    final items = async.asData?.value ?? const <RecommendedPet>[];
    if (items.isEmpty) return const SizedBox.shrink(key: ValueKey('guestRealPetsHidden'));
    _reportViewed(items.length);
    final height = RecommendedPetCard.gridCellExtent(context, GuestRealPetsStrip.cardWidth,
        footerExtent: GuestRealPetsStrip.footerExtent);
    return _frame(
      context,
      SizedBox(
        height: height,
        child: ListView.separated(
          key: const ValueKey('guestRealPetsList'),
          scrollDirection: Axis.horizontal,
          padding: EdgeInsets.zero,
          itemCount: items.length,
          separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.md),
          itemBuilder: (context, i) =>
              SizedBox(width: GuestRealPetsStrip.cardWidth, child: _card(context, items[i], i)),
        ),
      ),
    );
  }

  Widget _frame(BuildContext context, Widget body) {
    final l10n = AppLocalizations.of(context);
    return Padding(
      key: const ValueKey('guestRealPetsStrip'),
      padding: const EdgeInsets.only(top: AppSpacing.sm, bottom: AppSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Text(l10n.petRecommendSectionTitle,
                style: AppTypography.body.copyWith(fontWeight: FontWeight.w700)),
          ),
          body,
        ],
      ),
    );
  }

  Widget _card(BuildContext context, RecommendedPet pet, int position) {
    final postId = pet.coverPostId;
    return RecommendedPetCard(
      pet: pet,
      from: kGuestRealPetsEntrySource,
      // AC2：点大图 → 帖子详情（游客可看）；没有封面帖时点图无动作。
      onCoverTap: postId == null
          ? () {}
          : () {
              _reportTap('open_post', pet, position);
              context.push('/content/$postId');
            },
      likeSlot: postId == null
          ? null
          : _TapReporter(
              onTap: () => _reportTap('post_like', pet, position),
              child: RecommendLikePill(
                postId: postId,
                initialLiked: pet.coverLikedByViewer,
                initialCount: pet.coverLikeCount,
                source: kGuestRealPetsEntrySource,
                loginEntrySource: kGuestRealPetsEntrySource,
              ),
            ),
      footer: Padding(
        padding: const EdgeInsets.only(top: AppSpacing.xxs),
        child: Align(
          alignment: Alignment.centerLeft,
          child: _TapReporter(
            onTap: () => _reportTap('pet_follow', pet, position),
            child: PetFollowButton(
              petId: pet.petId,
              initialFollow: false,
              style: PetFollowButtonStyle.listCompact,
              source: kGuestRealPetsEntrySource,
              hitHeight: 44,
              invalidatesFollowedPetList: false,
              loginEntrySource: kGuestRealPetsEntrySource,
            ),
          ),
        ),
      ),
    );
  }

  Widget _skeleton(BuildContext context) {
    final height = RecommendedPetCard.gridCellExtent(context, GuestRealPetsStrip.cardWidth,
        footerExtent: GuestRealPetsStrip.footerExtent);
    return SizedBox(
      key: const ValueKey('guestRealPetsSkeleton'),
      height: height,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        physics: const NeverScrollableScrollPhysics(),
        itemCount: 3,
        separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.md),
        itemBuilder: (context, i) => SizedBox(
          width: GuestRealPetsStrip.cardWidth,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: GuestRealPetsStrip.cardWidth,
                height: GuestRealPetsStrip.cardWidth,
                decoration:
                    BoxDecoration(color: AppColors.cream2, borderRadius: BorderRadius.circular(11)),
              ),
              const SizedBox(height: AppSpacing.sm),
              Container(width: 84, height: 12, color: AppColors.cream2),
              const SizedBox(height: AppSpacing.xxs),
              Container(width: 112, height: 10, color: AppColors.cream2),
            ],
          ),
        ),
      ),
    );
  }
}

/// 只「看」点击、不参与手势竞争：子组件（点赞 / 追更按钮）自己的点击照常生效，
/// 这里在抬手且位移很小时报一次埋点（游客点按钮只弹登录、按钮本身不回调，所以埋点放在外面）。
class _TapReporter extends StatefulWidget {
  const _TapReporter({required this.onTap, required this.child});

  final VoidCallback onTap;
  final Widget child;

  @override
  State<_TapReporter> createState() => _TapReporterState();
}

class _TapReporterState extends State<_TapReporter> {
  Offset? _down;

  @override
  Widget build(BuildContext context) {
    return Listener(
      behavior: HitTestBehavior.deferToChild,
      onPointerDown: (e) => _down = e.position,
      onPointerCancel: (_) => _down = null,
      onPointerUp: (e) {
        final down = _down;
        _down = null;
        if (down != null && (e.position - down).distance < kTouchSlop) widget.onTap();
      },
      child: widget.child,
    );
  }
}
