import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../../pet_passport/domain/new_stamp_args.dart';
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
/// 底部按钮行（`bottomNavigationBar` 的一行 `Row`；成功页没有评论输入条，可吸底）：
/// - 「Lihat Paspor」（Story 1.2 / 1.3）：**仅新章（C2）**出，次级样式 → B4 整页落章 → 护照页停在新章；C2b 不出；
/// - 「Rekam Momen Ini」主 CTA 由 Story 1.5 在同一行追加并排（D-14）。
/// 动效有静态兜底：动画结束态就是可读的章面 + 文案。
class PlaceCheckinSuccessPage extends StatelessWidget {
  const PlaceCheckinSuccessPage({super.key, required this.args});

  final PlaceCheckinSuccessArgs args;

  /// 路由模板（顶层 GoRoute，与 `/places/:token` 并列）。⚠️ 与 `app_router.dart` 同源。
  static const String routePattern = '/places/:token/checkin-success';

  static String routeFor(String token) => '/places/$token/checkin-success';

  /// 落章轻反馈时长（AC5.1：≤400ms）。
  static const Duration stampAnimation = Duration(milliseconds: 360);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final r = args.result;
    final date = r.visitDate == null ? null : formatDayMonthYear(context, r.visitDate!);
    return Scaffold(
      backgroundColor: AppColors.cream,
      bottomNavigationBar: r.isNewStamp
          ? SafeArea(
              minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      key: const ValueKey('placeCheckinViewPassport'),
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size.fromHeight(48),
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
                      child: Text(l10n.placeCheckinViewPassport),
                    ),
                  ),
                ],
              ),
            )
          : null,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        title: Text(l10n.placeCheckinButton),
      ),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(28, 24, 28, 28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                r.isNewStamp
                    ? _NewStamp(result: r)
                    : _RepeatStamp(result: r),
                const SizedBox(height: 22),
                Text(l10n.placeCheckinSuccessTitle,
                    key: const ValueKey('placeCheckinSuccessTitle'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        fontSize: 21, fontWeight: FontWeight.w700, color: AppColors.ink, height: 1.3)),
                const SizedBox(height: 8),
                Text(date == null ? r.placeName : l10n.placeCheckinSuccessPlaceDate(r.placeName, date),
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
                const SizedBox(height: 4),
                Text(
                  r.isNewStamp
                      ? l10n.placeCheckinNewStamp(args.petName)
                      : l10n.placeCheckinRepeatStamp(r.placeName, r.visitCount),
                  key: const ValueKey('placeCheckinStampLine'),
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                      fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.mint),
                ),
              ],
            ),
          ),
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
