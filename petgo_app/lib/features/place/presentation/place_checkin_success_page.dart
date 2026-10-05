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
import '../../pet_passport/presentation/pet_passport_stamp_page.dart';
import '../../profile/presentation/pet_insights_page.dart';
import '../domain/place_checkin_result.dart';
import 'widgets/place_stamp_view.dart';

/// 打卡成功页的入参（经 go_router `extra` 传入）。
class PlaceCheckinSuccessArgs {
  const PlaceCheckinSuccessArgs({required this.result, required this.petName});

  final PlaceCheckinResult result;

  /// 当前唯一宠物的名字（C2「{pet} dapat cap baru」）。
  final String petName;
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

  @override
  ConsumerState<PlaceCheckinSuccessPage> createState() => _PlaceCheckinSuccessPageState();
}

class _PlaceCheckinSuccessPageState extends ConsumerState<PlaceCheckinSuccessPage> {
  PlaceCheckinSuccessArgs get args => widget.args;

  @override
  void initState() {
    super.initState();
    // 刚盖的章要出现在护照里：进页即重取护照（章详情块读它；取到前 / 取不到用简版章面兜底）。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) ref.invalidate(petPassportProvider);
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final r = args.result;
    final passport = ref.watch(petPassportProvider).asData?.value;
    final stampIndex = passport?.stamps.indexWhere((s) => s.placeToken == r.placeToken) ?? -1;
    final date = r.visitDate == null ? null : formatDayMonthYear(context, r.visitDate!);
    return Scaffold(
      backgroundColor: AppColors.cream,
      // D-14：底部一行（成功页无评论输入条，可吸底）。C2 = 左次级「Lihat Paspor」+ 右主 CTA「Rekam Momen Ini」
      // （flex 1 : 2，主 CTA 更宽实心）；C2b = 只有主 CTA，整宽。两按钮同高、热区 ≥44、按压 0.96。
      bottomNavigationBar: SafeArea(
        minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12),
        child: Row(
          key: const ValueKey('placeCheckinActions'),
          children: [
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
      ),
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
            if (passport != null && stampIndex >= 0)
              TweenAnimationBuilder<double>(
                key: const ValueKey('placeCheckinStampDetail'),
                tween: Tween(begin: 0, end: 1),
                duration: PlaceCheckinSuccessPage.stampAnimation,
                curve: Curves.easeOut,
                builder: (context, t, child) => Opacity(
                  opacity: t,
                  child: Transform.scale(scale: 0.96 + 0.04 * t, child: child),
                ),
                child: PassportStampDetail(passport: passport, index: stampIndex, showSeePlace: false),
              )
            else ...[
              const SizedBox(height: 12),
              Center(child: r.isNewStamp ? _NewStamp(result: r) : _RepeatStamp(result: r)),
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
}

/// C2：落章轻反馈（缩放 1.25→1.0 + 淡入，一次）。
class _NewStamp extends StatelessWidget {
  const _NewStamp({required this.result});

  final PlaceCheckinResult result;

  @override
  Widget build(BuildContext context) {
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
