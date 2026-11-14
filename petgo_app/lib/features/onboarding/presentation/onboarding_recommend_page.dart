import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../profile/data/onboarding_mark_repository.dart';
import '../../profile/data/pet_list_repository.dart';
import '../../profile/data/pet_recommendation_repository.dart';
import '../../profile/presentation/widgets/recommend_like_pill.dart';
import '../../profile/presentation/widgets/recommended_pet_card.dart';
import '../../social/presentation/widgets/pet_follow_button.dart';
import '../domain/onboarding_recommend_route.dart';

export '../domain/onboarding_recommend_route.dart';

/// 首日推荐页（V1.5.0 onboarding-recall Story 1.2 · FR-173 · UI 稿 A1 ~ A5）。
///
/// - **只出现一次**（AC5）：先读 `onboarding-marks`，已有 `first_day_recommend` → 直接去落点；
///   首帧渲染成功后写标记（best-effort）。读标记失败按「未看过」（[OnboardingMarks] 的既定口径）。
/// - **失败或空**（AC6）：不出现空白页，写标记后直接去落点。加载中 A4 骨架。
/// - 卡片（AC7）：复用 [RecommendedPetCard]，头像 / 名字点击不跳转；右上角点赞胶囊（[LikeButton]，只用公开参数）；
///   卡下 [PetFollowButton] 的 `listCompact`。
/// - 底部按钮初始「Lewati」描边，追更 / 点赞成功 ≥ 1 次后变「Selesai」实心（AC4）；右上角「Lewati」始终在；
///   顶栏无返回箭头，系统返回键等同右上角「Lewati」。
class OnboardingRecommendPage extends ConsumerStatefulWidget {
  const OnboardingRecommendPage({super.key, required this.entry, required this.landing});

  /// 从哪个引导出口进来（埋点 `entry`）。
  final String entry;

  /// 结束后的落点（`profile` / `home`）。
  final String landing;

  /// 落点路径：`profile` → `/profile`，其余一律 `/home`（未知值不跳到奇怪的地方）。
  static String landingPath(String landing) =>
      landing == kOnboardingRecommendLandingProfile ? '/profile' : '/home';

  @override
  ConsumerState<OnboardingRecommendPage> createState() => _OnboardingRecommendPageState();
}

class _OnboardingRecommendPageState extends ConsumerState<OnboardingRecommendPage> {
  /// 已经把内容展示出来了（之后标记集合变化——包括我们自己写的那一次——都不再触发离开）。
  bool _shown = false;

  /// 已发起离开，防重入。
  bool _leaving = false;

  int _followCount = 0;
  int _likeCount = 0;

  bool get _interacted => _followCount + _likeCount > 0;

  /// 写「已看过」标记：best-effort，失败静默（与现有两个引导一致）。
  void _markSeen() {
    ref.read(onboardingMarksProvider.notifier).mark(kOnboardingMarkFirstDayRecommend).catchError((_) {});
  }

  /// 去落点（不出屏的三种情形 + 按钮 / 返回键）。
  void _leave() {
    if (_leaving) return;
    _leaving = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      context.go(OnboardingRecommendPage.landingPath(widget.landing));
    });
  }

  void _exit(String button) {
    Analytics.capture('onboarding_recommend_exited', {
      'button': button,
      'follow_count': _followCount,
      'like_count': _likeCount,
    });
    _leave();
  }

  /// 首帧渲染成功：报曝光 + 写标记（各一次）。
  void _onFirstShown(int cardCount) {
    if (_shown) return;
    _shown = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      Analytics.capture('onboarding_recommend_viewed', {
        'entry': widget.entry,
        'card_count': cardCount,
      });
      _markSeen();
    });
  }

  void _onAction(String action, RecommendedPet pet, int position) {
    setState(() {
      if (action == 'pet_follow') {
        _followCount++;
      } else {
        _likeCount++;
      }
    });
    Analytics.capture('onboarding_recommend_action', {
      'action': action,
      'pet_id': pet.petId,
      'position': position,
      'match': ?pet.match?.toLowerCase(),
    });
  }

  @override
  Widget build(BuildContext context) {
    final List<RecommendedPet>? items = _resolveItems();
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        // 系统返回键等同「Lewati」（AC4）；数据还没出来时直接去落点。
        if (_shown) {
          _exit('skip_top');
        } else {
          _leave();
        }
      },
      child: Scaffold(
        backgroundColor: AppColors.cream,
        body: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _topBar(context),
              _header(context),
              Expanded(child: items == null ? _skeleton() : _grid(items)),
              _bottomButton(context),
            ],
          ),
        ),
      ),
    );
  }

  /// 解析当前该展示什么：null = 骨架（加载中 / 正在离开）；非空列表 = 内容。
  /// 「已看过 / 失败 / 空」都在这里决定离开。
  List<RecommendedPet>? _resolveItems() {
    if (_leaving) return null;
    if (!_shown) {
      final marks = ref.watch(onboardingMarksProvider);
      if (marks.isLoading && !marks.hasValue) return null;
      // 读标记失败时 provider 已按「未看过」回空集（AC5）。
      if ((marks.asData?.value ?? const <String>{}).contains(kOnboardingMarkFirstDayRecommend)) {
        _leave();
        return null;
      }
    }
    final recs = ref.watch(onboardingRecommendProvider);
    if (recs.isLoading && !recs.hasValue) return null;
    final items = recs.asData?.value ?? const <RecommendedPet>[];
    if (recs.hasError || items.isEmpty) {
      if (!_shown) {
        // AC6：失败或空 → 不出空白页；照样写标记，下次不再进来。
        // ⚠️ 写标记会改 provider 状态 → 不能在 build 里同步做，挪到帧后（先于 _leave 的导航回调执行）。
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _markSeen();
        });
        _leave();
        return null;
      }
    }
    _onFirstShown(items.length);
    return items;
  }

  Widget _topBar(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Align(
      alignment: Alignment.centerRight,
      child: TextButton(
        key: const ValueKey('onboardingRecommendSkipTop'),
        onPressed: () => _shown ? _exit('skip_top') : _leave(),
        child: Text(l10n.archivePromptSkip,
            style: AppTypography.caption.copyWith(fontWeight: FontWeight.w600)),
      ),
    );
  }

  Widget _header(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final pets = ref.watch(petListProvider).asData?.value ?? const <PetListItem>[];
    final defaultPet = pets.where((p) => !p.isReadOnly).firstOrNull ?? pets.firstOrNull;
    final subtitle = defaultPet == null
        ? l10n.onboardingRecommendSubtitleNoPet
        : l10n.onboardingRecommendSubtitlePet(defaultPet.name);
    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.petRecommendSectionTitle, style: AppTypography.title),
          const SizedBox(height: AppSpacing.xxs),
          Text(subtitle,
              key: const ValueKey('onboardingRecommendSubtitle'),
              style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
        ],
      ),
    );
  }

  /// 追更按钮所在的卡下附加区高度（热区 44 + 上间距）。
  static const double _footerExtent = 44 + AppSpacing.xxs;

  Widget _grid(List<RecommendedPet> items) {
    return LayoutBuilder(builder: (context, constraints) {
      const crossGap = AppSpacing.md; // 12
      const sidePad = AppSpacing.lg;
      final cellWidth = (constraints.maxWidth - sidePad * 2 - crossGap) / 2;
      return GridView.builder(
        key: const ValueKey('onboardingRecommendGrid'),
        padding: const EdgeInsets.fromLTRB(sidePad, 0, sidePad, AppSpacing.lg),
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          crossAxisSpacing: crossGap,
          mainAxisSpacing: 16,
          mainAxisExtent:
              RecommendedPetCard.gridCellExtent(context, cellWidth, footerExtent: _footerExtent),
        ),
        itemCount: items.length,
        itemBuilder: (context, i) => _card(items[i], i),
      );
    });
  }

  Widget _card(RecommendedPet pet, int position) {
    final postId = pet.coverPostId;
    return RecommendedPetCard(
      pet: pet,
      from: 'onboarding_recommend',
      // AC7：头像 / 名字点击不跳转。
      onTapOverride: () {},
      likeSlot: postId == null
          ? null
          : RecommendLikePill(
              postId: postId,
              initialLiked: pet.coverLikedByViewer,
              initialCount: pet.coverLikeCount,
              source: 'onboarding_recommend',
              onLiked: () => _onAction('post_like', pet, position),
            ),
      footer: Padding(
        padding: const EdgeInsets.only(top: AppSpacing.xxs),
        child: Align(
          alignment: Alignment.centerLeft,
          child: PetFollowButton(
            petId: pet.petId,
            initialFollow: false,
            style: PetFollowButtonStyle.listCompact,
            source: 'onboarding_recommend',
            hitHeight: 44,
            invalidatesFollowedPetList: false,
            onSettled: (following) {
              if (following) _onAction('pet_follow', pet, position);
            },
          ),
        ),
      ),
    );
  }

  Widget _skeleton() {
    return LayoutBuilder(builder: (context, constraints) {
      const crossGap = AppSpacing.md;
      const sidePad = AppSpacing.lg;
      final cellWidth = (constraints.maxWidth - sidePad * 2 - crossGap) / 2;
      return GridView.builder(
        key: const ValueKey('onboardingRecommendSkeleton'),
        physics: const NeverScrollableScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: sidePad),
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          crossAxisSpacing: crossGap,
          mainAxisSpacing: 16,
          mainAxisExtent:
              RecommendedPetCard.gridCellExtent(context, cellWidth, footerExtent: _footerExtent),
        ),
        itemCount: kOnboardingRecommendLimit,
        itemBuilder: (context, i) => Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            AspectRatio(
              aspectRatio: 1,
              child: DecoratedBox(
                decoration: BoxDecoration(
                    color: AppColors.cream2, borderRadius: BorderRadius.circular(11)),
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            Container(width: cellWidth * 0.6, height: 12, color: AppColors.cream2),
            const SizedBox(height: AppSpacing.xxs),
            Container(width: cellWidth * 0.8, height: 10, color: AppColors.cream2),
          ],
        ),
      );
    });
  }

  Widget _bottomButton(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final done = _interacted;
    final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(14));
    const padding = EdgeInsets.symmetric(vertical: 14);
    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, AppSpacing.md),
      child: done
          ? FilledButton(
              key: const ValueKey('onboardingRecommendDone'),
              onPressed: () => _exit('done'),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                padding: padding,
                shape: shape,
              ),
              child: Text(l10n.onboardingDone,
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
            )
          : OutlinedButton(
              key: const ValueKey('onboardingRecommendSkipBottom'),
              onPressed: () => _shown ? _exit('skip_bottom') : _leave(),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.mint,
                side: const BorderSide(color: AppColors.mint),
                padding: padding,
                shape: shape,
              ),
              child: Text(l10n.archivePromptSkip,
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
            ),
    );
  }
}
