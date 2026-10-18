import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_image.dart';

/// 头像环扣（V1.4.0 multi-pet Story 3.3 · AC4.5 · UI 稿 `.pk-a`）：详情页关联宠物胶囊 F6 / F6b / F6c 用，
/// 4.1 / 4.2 可复用。
///
/// - 每个 [size] 圆形、外圈 2 白描边（`box-shadow: 0 0 0 2px #fff`）；后一个左移 [overlap]；
///   **第一个在最上层**（CSS z-index 3/2/1；稿件文字「后一只压前一只」与 CSS 不符，按 CSS）。
/// - 最多 3 个；**> 3 只**时第 3 个位置换「+N」（N = 总数 − 2，同尺寸圆、`mintTint2` 底、11 / w700 `ink2`）。
class PetAvatarStack extends StatelessWidget {
  const PetAvatarStack({super.key, required this.avatarUrls, this.size = 24, this.overlap = 6});

  /// 按创建顺序的全部关联宠物头像（null / 空 = 占位爪印）。
  final List<String?> avatarUrls;
  final double size;
  final double overlap;

  static const int maxShown = 3;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final total = avatarUrls.length;
    if (total == 0) return const SizedBox.shrink();
    final overflow = total > maxShown;
    final shownAvatars = overflow ? avatarUrls.take(maxShown - 1).toList() : avatarUrls;
    final items = <Widget>[
      for (var i = 0; i < shownAvatars.length; i++)
        _ring(_avatar(shownAvatars[i]), key: ValueKey('petAvatarStackItem-$i')),
      if (overflow)
        _ring(
          Container(
            alignment: Alignment.center,
            color: AppColors.mintTint2,
            child: Text(
              l10n.diaryPetsMore(total - (maxShown - 1)),
              style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.ink2),
            ),
          ),
          key: const ValueKey('petAvatarStackMore'),
        ),
    ];
    final outer = size + 4; // 含 2 白边
    final step = outer - overlap;
    final width = outer + step * (items.length - 1);
    return SizedBox(
      width: width,
      height: outer,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          // 倒序入栈：Stack 里后画的在上层 → 第一个最后画，压在最上面（z-index 3/2/1）。
          for (var i = items.length - 1; i >= 0; i--)
            Positioned(left: step * i, top: 0, child: items[i]),
        ],
      ),
    );
  }

  Widget _avatar(String? url) {
    final has = url != null && url.isNotEmpty;
    return has
        ? AppImage.widget(url, fit: BoxFit.cover, thumbWidth: 96,
            errorBuilder: (context, error, stack) => _placeholder())
        : _placeholder();
  }

  Widget _placeholder() => Container(
        color: AppColors.mintTint,
        alignment: Alignment.center,
        child: Icon(Icons.pets, size: size * 0.55, color: AppColors.mint500),
      );

  Widget _ring(Widget child, {Key? key}) => Container(
        key: key,
        width: size + 4,
        height: size + 4,
        padding: const EdgeInsets.all(2),
        decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
        child: ClipOval(child: SizedBox(width: size, height: size, child: child)),
      );
}
