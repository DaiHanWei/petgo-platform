import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../content/domain/content_type.dart';
import '../../content/domain/publish_checkin_place.dart';
import '../../content/presentation/publish_compose_page.dart';
import '../../pet_passport/data/pet_passport_repository.dart';
import '../../pet_passport/domain/new_stamp_args.dart';
import '../../pet_passport/presentation/passport_layout.dart';
import '../../pet_passport/presentation/pet_passport_stamp_page.dart';
import '../../profile/data/current_pet_provider.dart';
import '../../profile/domain/milestone_instant_codes.dart';
import '../../profile/presentation/widgets/pet_names.dart';
import '../../../shared/widgets/app_image.dart';
import '../../profile/presentation/milestone_instant_celebration.dart';
import '../../profile/presentation/pet_insights_page.dart';
import '../domain/place_checkin_result.dart';
import 'widgets/place_stamp_view.dart';

/// 本次打卡的一只宠物（V1.4.0 multi-pet Story 3.3 · AC6.1）：名字 / 头像来自宠物列表，章来自打卡响应的逐宠物结果。
class PlaceCheckinPetArgs {
  const PlaceCheckinPetArgs({
    required this.petId,
    required this.name,
    this.avatarUrl,
    required this.isNewStamp,
    required this.visitCount,
  });

  final int petId;

  /// ⚠️ 只渲染，不写日志 / 埋点（§5）。
  final String name;
  final String? avatarUrl;
  final bool isNewStamp;
  final int visitCount;
}

/// 打卡成功页的入参（经 go_router `extra` 传入）。
class PlaceCheckinSuccessArgs {
  const PlaceCheckinSuccessArgs({
    required this.result,
    required this.petName,
    this.ceremonyPlayed = false,
    this.pets = const [],
  });

  final PlaceCheckinResult result;

  /// 第一只宠物的名字（C2「{pet} dapat cap baru」；单宠物路径用）。
  final String petName;

  /// 本次打卡的全部宠物（按创建顺序，V1.4.0 multi-pet Story 3.3）。≤ 1 只 → 与改造前同一路径；
  /// ≥ 2 只 → 一排章面（G3）。空表 = 老调用方（未经选宠物流程）。
  final List<PlaceCheckinPetArgs> pets;

  /// ≥ 2 只宠物 → 多章路径。
  bool get isMultiPet => pets.length >= 2;

  /// 任一宠物是新章（盖章动画据此，AC6.4）；无逐宠物信息 → 顶层字段。
  bool get anyNewStamp => pets.isEmpty ? result.isNewStamp : pets.any((p) => p.isNewStamp);

  /// V1.3.4：刚播过全屏盖章动画（`PlaceStampCeremonyPage`）→ 不再播本页 0.36s 小落章，章面直接终态。
  /// 再访 / 动画兜底失败为 false，保持原样。
  final bool ceremonyPlayed;
}

/// 打卡成功页（V1.3.2 batch-a Story 1.1 · AC5 · UI 稿 C2 / C2b）。
///
/// - 新章（C2）：小尺寸章面 + 落章轻反馈（缩放 + 淡入，一次，≤400ms）+「{pet} dapat cap baru」。
/// - 再访（C2b）：同骨架，章角标「×{n}」跳动一次 +「Cap {place} sekarang {n}×」；**不播整页落章**。
///
/// 底部按钮行（`bottomNavigationBar` 的一行 `Row`；成功页没有评论输入条，可吸底，D-14）：
/// - 「Lihat Paspor」（Story 1.2 / 1.3）：**仅新章（C2）**出，次级样式 → B4 整页落章 → 护照页停在新章；C2b 不出；
/// - 「Rekam Momen Ini」（Story 1.5）：主 CTA，C2 更宽（flex 2:1）、C2b 整宽 → 发帖页（Diary 预选 + 打卡关联）。
/// 动效有静态兜底：动画结束态就是可读的章面 + 文案。
class PlaceCheckinSuccessPage extends ConsumerStatefulWidget {
  const PlaceCheckinSuccessPage({super.key, required this.args});

  final PlaceCheckinSuccessArgs args;

  /// 路由模板（顶层 GoRoute，与 `/places/:token` 并列）。⚠️ 与 `app_router.dart` 同源。
  static const String routePattern = '/places/:token/checkin-success';

  static String routeFor(String token) => '/places/$token/checkin-success';

  /// 落章轻反馈时长（AC5.1：≤400ms）。
  static const Duration stampAnimation = Duration(milliseconds: 360);

  /// V1.3.4：从盖章动画页进来时的淡入时长（spec §2 拍板 A：停 0.4s 后约 0.3s 淡出进入成功页）。
  static const Duration ceremonyFadeIn = Duration(milliseconds: 300);

  /// 空白护照本占位的最长时间（ST-07 弱网测试 2026-10-15）：护照重取超过它仍未回来 → 退到简版章面，
  /// 不再让护照区整片空白（实测弱网下重取 5～17s 无任何提示）。退回后固定在简版，护照晚到也不跳变。
  static const Duration passportPlaceholderMax = Duration(milliseconds: 1200);

  /// 路由页面：刚播过盖章动画（`ceremonyPlayed`）→ 300ms 淡入（动画页被 pushReplacement 替换，
  /// 淡入期间它仍垫在下面，即「动画最终帧 → 成功页」的交叉淡化）；其余（再访 / 兜底）保持默认平台转场。
  /// ⚠️ `app_router.dart` 与测试路由同用它。
  static Page<void> pageFor(GoRouterState state, PlaceCheckinSuccessArgs args) {
    final child = PlaceCheckinSuccessPage(args: args);
    if (!args.ceremonyPlayed) return MaterialPage<void>(key: state.pageKey, child: child);
    return CustomTransitionPage<void>(
      key: state.pageKey,
      transitionDuration: ceremonyFadeIn,
      // 进场只淡入；侧滑 / 返回交还平台转场（iOS 自带边缘右滑返回，popGestureEnabled 恢复）。
      // 两种状态下 widget 树结构不变（始终 FadeTransition + 平台转场），成功页 State 不重建。
      transitionsBuilder: (context, animation, secondaryAnimation, child) {
        final route = ModalRoute.of(context)! as PageRoute<void>;
        final entering = animation.status == AnimationStatus.forward && !route.popGestureInProgress;
        return FadeTransition(
          opacity: entering ? animation : kAlwaysCompleteAnimation,
          child: Theme.of(context).pageTransitionsTheme.buildTransitions<void>(
                route,
                context,
                entering ? kAlwaysCompleteAnimation : animation,
                secondaryAnimation,
                child,
              ),
        );
      },
      child: child,
    );
  }

  @override
  ConsumerState<PlaceCheckinSuccessPage> createState() => _PlaceCheckinSuccessPageState();
}

class _PlaceCheckinSuccessPageState extends ConsumerState<PlaceCheckinSuccessPage> {
  PlaceCheckinSuccessArgs get args => widget.args;

  /// V1.3.4 Story 4.3：每个成功页实例只检测一次「第一次打卡」里程碑的当场庆祝（重建 / 返回本页不重复）。
  bool _milestoneChecked = false;

  /// 占位超时（[PlaceCheckinSuccessPage.passportPlaceholderMax]）时护照仍未取回 → 本页固定走简版章面。
  bool _passportFellBack = false;
  Timer? _placeholderTimer;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    precachePassportBook(context);
  }

  @override
  void initState() {
    super.initState();
    // 刚盖的章要出现在护照里：进页即重取护照（章详情块读它；取到前 / 取不到用简版章面兜底）。
    _placeholderTimer = Timer(PlaceCheckinSuccessPage.passportPlaceholderMax, () {
      if (!mounted) return;
      final other = _singlePetId;
      final stamps = (other == null ? ref.read(petPassportProvider) : ref.read(petPassportOfPetProvider(other)))
          .asData
          ?.value
          .stamps;
      final found = stamps?.any((s) => s.placeToken == args.result.placeToken) ?? false;
      if (!found) setState(() => _passportFellBack = true);
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        final other = _singlePetId;
        if (other == null) {
          ref.invalidate(petPassportProvider);
        } else {
          ref.invalidate(petPassportOfPetProvider(other));
        }
      }
      // V1.3.4 Story 4.3 · AD-19：后端异步达成「第一次打卡」→ 盖在成功页上当场庆祝；没等到交补庆祝兜底。
      // 不按 isNewStamp 过滤（那是「这个场所的新章」），统一看「已完成未庆祝」。
      if (mounted && !_milestoneChecked) {
        _milestoneChecked = true;
        unawaited(_celebrateMilestones());
      }
    });
  }

  /// V1.4.0 multi-pet Story 3.3 · AC6.6：多宠时按所选宠物**逐只**检查并依次弹（里程碑 provider 已是 petId family）；
  /// 单宠 / 老调用方 → 与改造前同口径（当前宠物）。
  Future<void> _celebrateMilestones() async {
    if (args.pets.length <= 1) {
      final only = _singlePetId;
      await celebrateAutoMilestoneIfAny(context, ref, kPlaceCheckinMilestoneCodes, forPetId: only);
      return;
    }
    await celebrateAutoMilestonesForPets(
        context, ref, kPlaceCheckinMilestoneCodes, [for (final p in args.pets) p.petId]);
  }

  /// 单只宠物打卡、且它**确定不是**当前宠物 → 那只宠物的 id（章详情 / 里程碑按它读）；
  /// 否则 null（= 当前宠物，改造前路径；当前宠物还没解析出来时也走改造前路径）。
  int? get _singlePetId {
    if (args.pets.length != 1) return null;
    final id = args.pets.first.petId;
    final current = ref.exists(effectivePetIdProvider) ? ref.read(effectivePetIdProvider).value : null;
    return (current == null || id == current) ? null : id;
  }

  @override
  void dispose() {
    _placeholderTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final r = args.result;
    if (args.isMultiPet) return _buildMulti(context, l10n);
    final other = _singlePetId;
    final passportAsync =
        other == null ? ref.watch(petPassportProvider) : ref.watch(petPassportOfPetProvider(other));
    final passport = _passportFellBack ? null : passportAsync.asData?.value;
    final stampIndex = passport?.stamps.indexWhere((s) => s.placeToken == r.placeToken) ?? -1;
    // 护照还在取（进页即重取）→ 先放空白护照本占位；只有真的取不到 / 找不到这枚章才退到简版章面。
    // （2026-10-06 动效验收：原先加载期间先闪约 0.8s 简版章面再换成护照本。）
    // ST-07：占位有上限，超时后 [_passportFellBack] 让本页固定走简版章面。
    final passportPending =
        !_passportFellBack && stampIndex < 0 && (passportAsync.isLoading || passportAsync.isRefreshing);
    final date = r.visitDate == null ? null : formatDayMonthYear(context, r.visitDate!);
    return Scaffold(
      backgroundColor: AppColors.cream,
      // D-14：底部一行（成功页无评论输入条，可吸底）。C2 = 左次级「Lihat Paspor」+ 右主 CTA「Rekam Momen Ini」
      // （flex 1 : 2，主 CTA 更宽实心）；C2b = 只有主 CTA，整宽。两按钮同高、热区 ≥44、按压 0.96。
      bottomNavigationBar: _actions(context, l10n),
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        title: Text(l10n.placeCheckinButton),
      ),
      // 2026-10-06 产品：成功页正文 = 1.3 的章详情（护照本单章页 + 地点信息 + 地址），顶部保留成功提示；
      // 「See place」不出（本页就是从场所页进来的）。护照还没取到 / 找不到这枚章 → 简版章面兜底（C2 / C2b 原样）。
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
          children: [
            Text(l10n.placeCheckinSuccessTitle,
                key: const ValueKey('placeCheckinSuccessTitle'),
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w700, color: AppColors.ink, height: 1.3)),
            const SizedBox(height: 4),
            Text(
              r.isNewStamp
                  ? l10n.placeCheckinNewStamp(args.petName)
                  : l10n.placeCheckinRepeatStamp(r.placeName, r.visitCount),
              key: const ValueKey('placeCheckinStampLine'),
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.mint),
            ),
            const SizedBox(height: 16),
            if ((passport != null && stampIndex >= 0) || passportPending)
              // 空白护照本 → 有内容的那页：底图不动，只让新内容淡入（旧的占位不淡出，避免中间整页发白一帧）。
              AnimatedSwitcher(
                // 刚播过盖章动画 → 章详情直接终态，不再叠 0.36s 淡入（不重复动效）。
                duration: args.ceremonyPlayed ? Duration.zero : PlaceCheckinSuccessPage.stampAnimation,
                switchInCurve: Curves.easeOut,
                layoutBuilder: (current, previous) => Stack(
                  alignment: Alignment.topCenter,
                  children: [...previous, ?current],
                ),
                transitionBuilder: (child, anim) => child.key == const ValueKey('placeCheckinStampDetail')
                    ? FadeTransition(opacity: anim, child: child)
                    : child,
                child: (passport != null && stampIndex >= 0)
                    ? KeyedSubtree(
                        key: const ValueKey('placeCheckinStampDetail'),
                        // 单章页不叠水印（2026-10-06 产品：水印只在纵览）。
                        child: PassportStampDetail(passport: passport, index: stampIndex, showSeePlace: false),
                      )
                    : PassportBook(
                        key: const ValueKey('placeCheckinBookPlaceholder'),
                        petName: args.petName,
                        passportNo: r.passportNo ?? '',
                        content: const SizedBox.shrink(),
                      ),
              )
            else ...[
              const SizedBox(height: 12),
              Center(
                  child: r.isNewStamp
                      ? _NewStamp(result: r, animate: !args.ceremonyPlayed)
                      : _RepeatStamp(result: r)),
              const SizedBox(height: 16),
              Text(date == null ? r.placeName : l10n.placeCheckinSuccessPlaceDate(r.placeName, date),
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
            ],
          ],
        ),
      ),
    );
  }

  /// 底部按钮行（D-14）。单宠 / 多宠共用。
  Widget _actions(BuildContext context, AppLocalizations l10n) {
    final r = args.result;
    return SafeArea(
      minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12),
      child: Row(
        key: const ValueKey('placeCheckinActions'),
        children: [
          // 「Lihat Paspor」仍按顶层 isNewStamp（= 第一只宠物；单宠与改造前一致）：护照是单只宠物的。
          if (r.isNewStamp) ...[
            Expanded(
              child: _Pressable(
                child: OutlinedButton(
                  key: const ValueKey('placeCheckinViewPassport'),
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                    // 1:2 分栏下本按钮只有 ~106dp 宽（360dp 屏），默认左右各 24 的内边距只剩 ~58 放字，
                    // 「View Passport」被截成「View Pas…」（L2 验收 2026-10-05）。收窄内边距 + 字放不下时等比缩小，不截断。
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                    foregroundColor: AppColors.mint,
                    side: const BorderSide(color: AppColors.mint),
                    // 与右侧主 CTA（filledButtonTheme：圆角 12 + 14.5/w700）同形同字重；
                    // 主题未配 outlinedButtonTheme，不写则落到 Material 默认胶囊形 + 细字，两钮并排不统一。
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    textStyle: const TextStyle(fontFamily: 'Poppins', fontSize: 14.5, fontWeight: FontWeight.w700),
                  ),
                  // Story 1.3：先进 B4 整页落章（只在 isNewStamp 分支构造 NewStampArgs），
                  // B4 的「Lihat Paspor」再 pushReplacement 到护照页停在新章。
                  onPressed: () => context.push(
                    PetInsightsRoutes.passportNewStamp,
                    extra: NewStampArgs(
                      placeToken: r.placeToken,
                      placeName: r.placeName,
                      placeType: r.placeType,
                      stampImageUrl: r.stampImageUrl,
                      stampCount: r.stampCount ?? 1,
                    ),
                  ),
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(l10n.placeCheckinViewPassport, maxLines: 1),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 10),
          ],
          Expanded(
            flex: 2,
            child: _Pressable(
              child: FilledButton.icon(
                key: const ValueKey('placeCheckinRecordMoment'),
                style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48), backgroundColor: AppColors.mint),
                // Story 1.5：Diary 预选 + 打卡关联随发布请求一次写入（AD-10 / AD-11）。
                onPressed: () => PublishComposePage.open(
                  context,
                  preset: ContentType.growthMoment,
                  placeCheckinToken: r.checkinToken,
                  // G3：默认选中本次打卡的宠物（AC1.7 / AC6.5）；老调用方无逐宠物信息 → 不预选。
                  initialPetIds: args.pets.isEmpty ? null : [for (final p in args.pets) p.petId],
                  placeCheckinPlace: PublishCheckinPlace(
                    placeToken: r.placeToken,
                    placeName: r.placeName,
                    placeType: r.placeType,
                  ),
                ),
                icon: const Icon(Icons.photo_camera_outlined, size: 18),
                // 同理：窄屏放不下时等比缩小，不截断。
                label: FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(l10n.placeCheckinRecordMoment, maxLines: 1),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// ≥ 2 只宠物（G3）：不出护照单章详情（那是单只宠物的护照），改为一排章面 + 各自头像 / 名字。
  Widget _buildMulti(BuildContext context, AppLocalizations l10n) {
    final r = args.result;
    final date = r.visitDate == null ? null : formatDayMonthYear(context, r.visitDate!);
    final newNames = [for (final p in args.pets) if (p.isNewStamp) p.name];
    final subtitle = newNames.isNotEmpty
        ? l10n.placeCheckinNewStamp(formatPetNames(l10n, newNames))
        : l10n.placeCheckinRepeatStamp(r.placeName, args.pets.first.visitCount);
    return Scaffold(
      backgroundColor: AppColors.cream,
      bottomNavigationBar: _actions(context, l10n),
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        title: Text(l10n.placeCheckinButton),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
          children: [
            Text(l10n.placeCheckinSuccessTitle,
                key: const ValueKey('placeCheckinSuccessTitle'),
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w700, color: AppColors.ink, height: 1.3)),
            const SizedBox(height: 4),
            Text(
              subtitle,
              key: const ValueKey('placeCheckinStampLine'),
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.mint),
            ),
            const SizedBox(height: 16),
            // UI 稿 `.stamps`：横排居中、间距 14；超过 2 枚时横向可滚，不换行。
            LayoutBuilder(
              builder: (context, c) => SingleChildScrollView(
                key: const ValueKey('placeCheckinMultiStamps'),
                scrollDirection: Axis.horizontal,
                child: ConstrainedBox(
                  constraints: BoxConstraints(minWidth: c.maxWidth),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      for (var i = 0; i < args.pets.length; i++) ...[
                        if (i > 0) const SizedBox(width: 14),
                        _PetStampCell(
                          key: ValueKey('placeCheckinPetStamp-${args.pets[i].petId}'),
                          result: r,
                          pet: args.pets[i],
                          animate: !args.ceremonyPlayed,
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Text(date == null ? r.placeName : l10n.placeCheckinSuccessPlaceDate(r.placeName, date),
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
          ],
        ),
      ),
    );
  }
}

/// 多宠成功页的一枚章（UI 稿 `.stampc`）：110 章面（新章落章轻反馈 / 再访静态）+ 头像 26 + 名字 12 `ink2`，间距 6；
/// 再访在名字后标「×{visitCount}」（与 C2b 角标同写法）。
class _PetStampCell extends StatelessWidget {
  const _PetStampCell({super.key, required this.result, required this.pet, this.animate = true});

  final PlaceCheckinResult result;
  final PlaceCheckinPetArgs pet;
  final bool animate;

  @override
  Widget build(BuildContext context) {
    final face = PlaceStampView(placeType: result.placeType, imageUrl: result.stampImageUrl, size: 110);
    final Widget stamp = pet.isNewStamp && animate
        ? TweenAnimationBuilder<double>(
            tween: Tween(begin: 0, end: 1),
            duration: PlaceCheckinSuccessPage.stampAnimation,
            curve: Curves.easeOutBack,
            builder: (context, t, child) => Opacity(
              opacity: t.clamp(0.0, 1.0),
              child: Transform.scale(scale: 1.25 - 0.25 * t, child: child),
            ),
            child: face,
          )
        : face;
    final hasAvatar = pet.avatarUrl != null && pet.avatarUrl!.isNotEmpty;
    return SizedBox(
      width: 110,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          stamp,
          const SizedBox(height: 6),
          CircleAvatar(
            radius: 13,
            backgroundColor: AppColors.mintTint,
            backgroundImage: AppImage.provider(pet.avatarUrl, thumbWidth: 78),
            child: hasAvatar ? null : const Icon(Icons.pets, size: 14, color: AppColors.mint500),
          ),
          const SizedBox(height: 6),
          Text(
            pet.isNewStamp ? pet.name : '${pet.name} ×${pet.visitCount}',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 12, color: AppColors.ink2),
          ),
        ],
      ),
    );
  }
}

/// C2：落章轻反馈（缩放 1.25→1.0 + 淡入，一次）。
///
/// [animate] 为 false（V1.3.4 刚播过全屏盖章动画）→ 直接终态，不重复落章。
class _NewStamp extends StatelessWidget {
  const _NewStamp({required this.result, this.animate = true});

  final PlaceCheckinResult result;
  final bool animate;

  @override
  Widget build(BuildContext context) {
    if (!animate) {
      return KeyedSubtree(
        key: const ValueKey('placeCheckinNewStamp'),
        child: PlaceStampView(placeType: result.placeType, imageUrl: result.stampImageUrl, size: 112),
      );
    }
    return TweenAnimationBuilder<double>(
      key: const ValueKey('placeCheckinNewStamp'),
      tween: Tween(begin: 0, end: 1),
      duration: PlaceCheckinSuccessPage.stampAnimation,
      curve: Curves.easeOutBack,
      builder: (context, t, child) => Opacity(
        opacity: t.clamp(0.0, 1.0),
        child: Transform.scale(scale: 1.25 - 0.25 * t, child: child),
      ),
      child: PlaceStampView(placeType: result.placeType, imageUrl: result.stampImageUrl, size: 112),
    );
  }
}

/// C2b：同一枚章 + 角标「×{n}」跳动一次（不播整页落章）。
class _RepeatStamp extends StatelessWidget {
  const _RepeatStamp({required this.result});

  final PlaceCheckinResult result;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 132,
      height: 124,
      child: Stack(
        clipBehavior: Clip.none,
        alignment: Alignment.center,
        children: [
          PlaceStampView(placeType: result.placeType, imageUrl: result.stampImageUrl, size: 112),
          Positioned(
            right: 0,
            top: 0,
            child: TweenAnimationBuilder<double>(
              tween: Tween(begin: 0, end: 1),
              duration: PlaceCheckinSuccessPage.stampAnimation,
              curve: Curves.elasticOut,
              builder: (context, t, child) =>
                  Transform.scale(scale: 0.6 + 0.4 * t, child: child),
              child: Container(
                key: const ValueKey('placeCheckinVisitBadge'),
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: AppColors.popRed,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text('×${result.visitCount}',
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w700, color: Colors.white)),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 按压反馈（scale 0.96，项目无公共 press 组件）。只包视觉，点击仍由内部按钮处理。
class _Pressable extends StatefulWidget {
  const _Pressable({required this.child});

  final Widget child;

  @override
  State<_Pressable> createState() => _PressableState();
}

class _PressableState extends State<_Pressable> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerDown: (_) => setState(() => _down = true),
      onPointerUp: (_) => setState(() => _down = false),
      onPointerCancel: (_) => setState(() => _down = false),
      child: AnimatedScale(
        scale: _down ? 0.96 : 1,
        duration: const Duration(milliseconds: 90),
        child: widget.child,
      ),
    );
  }
}
