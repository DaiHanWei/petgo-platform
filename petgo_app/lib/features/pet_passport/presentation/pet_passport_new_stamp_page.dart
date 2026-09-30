import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/haptics.dart';
import '../../place/presentation/widgets/place_stamp_view.dart';
import '../../profile/presentation/pet_insights_page.dart';
import '../domain/new_stamp_args.dart';

/// B4 整页落章（V1.3.2 batch-a Story 1.3 · AC1）。**只从打卡成功页 C2 进**。
///
/// 大尺寸章面 + 落章动效（缩放 1.3→1.0 + 淡入 + 轻微回弹，一次，≤600ms，振动一次）+
/// 「Cap baru! {place}」+「{n} cap terkumpul」（**无分母**）+ 吸底「Lihat Paspor」。
///
/// 🔴 静态兜底：`disableAnimations` 下首帧即是结束态（章面 + 两行文案完整可读）。
class PetPassportNewStampPage extends StatefulWidget {
  const PetPassportNewStampPage({super.key, required this.args});

  final NewStampArgs args;

  /// 落章动效时长（AC1.3：≤600ms）。
  static const Duration stampAnimation = Duration(milliseconds: 560);

  @override
  State<PetPassportNewStampPage> createState() => _PetPassportNewStampPageState();
}

class _PetPassportNewStampPageState extends State<PetPassportNewStampPage> {
  bool _vibrated = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_vibrated) {
      _vibrated = true;
      // 振动一次（与里程碑庆祝同一通道）。动效关闭时也振：振动不是动画。
      celebrationVibrate();
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final a = widget.args;
    final reduceMotion = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    final stamp = PlaceStampView(placeType: a.placeType, imageUrl: a.stampImageUrl, size: 148);
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        scrolledUnderElevation: 0,
        title: Text(l10n.passportPageTitle),
      ),
      bottomNavigationBar: SafeArea(
        minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12),
        child: FilledButton(
          key: const ValueKey('newStampViewPassport'),
          style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(48), backgroundColor: AppColors.mint),
          // 🔴 pushReplacement：从 B2 返回应回打卡成功页，不再看一次落章。
          onPressed: () =>
              context.pushReplacement(PetInsightsRoutes.passportFor(focus: a.placeToken)),
          child: Text(l10n.placeCheckinViewPassport),
        ),
      ),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                reduceMotion
                    ? stamp
                    : TweenAnimationBuilder<double>(
                        key: const ValueKey('newStampAnimation'),
                        tween: Tween(begin: 0, end: 1),
                        duration: PetPassportNewStampPage.stampAnimation,
                        curve: Curves.easeOutBack,
                        builder: (context, t, child) => Opacity(
                          opacity: t.clamp(0.0, 1.0),
                          child: Transform.scale(scale: 1.3 - 0.3 * t, child: child),
                        ),
                        child: stamp,
                      ),
                const SizedBox(height: 24),
                Text(l10n.passportNewStampTitle(a.placeName),
                    key: const ValueKey('newStampTitle'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        fontSize: 21, fontWeight: FontWeight.w700, color: AppColors.ink, height: 1.3)),
                const SizedBox(height: 8),
                Text(l10n.passportStampsCollected(a.stampCount),
                    key: const ValueKey('newStampCount'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
