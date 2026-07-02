import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/shadows.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/design/btn3d.dart';
import '../../../shared/widgets/design/emoji_avatar.dart';
import '../../../shared/widgets/design/momo.dart';

/// 新用户引导流（TailTopia Prototype · FR-11）。
///
/// 三步：欢迎页（吉祥物 Momo）→ 创建宠物档案 → 完成动效。
/// 全面换肤后的薄荷绿 × Duolingo 风格，界面文案印尼语（真实语境）+ 中文注释。
/// V1 单账号单宠物；完成后进入主页（后端落库留后续，mock 下首页已有种子）。
class MintOnboardingPage extends StatefulWidget {
  const MintOnboardingPage({super.key});

  @override
  State<MintOnboardingPage> createState() => _MintOnboardingPageState();
}

class _MintOnboardingPageState extends State<MintOnboardingPage> {
  int _step = 0;

  // 宠物草稿
  String _emoji = '🐱';
  final _name = TextEditingController();
  String _breed = '';
  final _birthday = TextEditingController();
  final _bio = TextEditingController();

  static const _emojis = ['🐱', '🐶', '🐰', '🐹', '🐦', '🐢', '🦜', '🐠'];
  static const _breeds = [
    'Kucing Oren',
    'Anjing Corgi',
    'Kelinci',
    'Kucing Persia',
    'Golden Retriever',
    'Hamster',
  ];

  @override
  void dispose() {
    _name.dispose();
    _birthday.dispose();
    _bio.dispose();
    super.dispose();
  }

  void _complete() => context.go('/home');

  @override
  Widget build(BuildContext context) {
    final Widget body;
    switch (_step) {
      case 0:
        body = _Welcome(onStart: () => setState(() => _step = 1));
        break;
      case 1:
        body = _CreatePet(
          emoji: _emoji,
          emojis: _emojis,
          breeds: _breeds,
          name: _name,
          breed: _breed,
          birthday: _birthday,
          bio: _bio,
          onEmoji: (e) => setState(() => _emoji = e),
          onBreed: (b) => setState(() => _breed = b),
          onChanged: () => setState(() {}),
          onNext: () => setState(() => _step = 2),
        );
        break;
      default:
        body = _Done(
          emoji: _emoji,
          name: _name.text.trim().isEmpty ? 'Mochi' : _name.text.trim(),
          onEnter: _complete,
        );
    }
    return Scaffold(
      backgroundColor: AppColors.cream,
      body: body,
    );
  }
}

// ============================================================
// Step 0 — Welcome
// ============================================================
class _Welcome extends StatelessWidget {
  const _Welcome({required this.onStart});

  final VoidCallback onStart;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [AppColors.mintTint, AppColors.cream],
          stops: [0.0, 0.62],
        ),
      ),
      child: Stack(
        children: [
          // 装饰圆斑
          Positioned(
            top: 90,
            left: -30,
            child: _blob(130, AppColors.mintTint),
          ),
          Positioned(
            top: 150,
            right: -20,
            child: _blob(80, AppColors.goldTint),
          ),
          SafeArea(
            child: Column(
              children: [
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 30),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Momo(size: 150, float: true),
                        const SizedBox(height: 30),
                        Text(
                          l10n.appTitle,
                          style: const TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w800,
                            color: AppColors.mint700,
                            letterSpacing: 2,
                          ),
                        ),
                        const SizedBox(height: 10),
                        Text(
                          l10n.onboardWelcomeTagline,
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                            fontSize: 30,
                            fontWeight: FontWeight.w900,
                            height: 1.15,
                            letterSpacing: -0.5,
                            color: AppColors.ink,
                          ),
                        ),
                        const SizedBox(height: 14),
                        ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 280),
                          child: Text(
                            l10n.onboardWelcomeSubtitle,
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                              fontSize: 15.5,
                              color: AppColors.ink2,
                              height: 1.5,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 0, 24, 40),
                  child: Column(
                    children: [
                      Btn3d(
                        expand: true,
                        onPressed: onStart,
                        child: Text(l10n.onboardWelcomeStart),
                      ),
                      const SizedBox(height: 8),
                      TextButton(
                        onPressed: onStart,
                        child: Text(
                          l10n.onboardWelcomeHaveAccount,
                          style: const TextStyle(
                            color: AppColors.muted,
                            fontWeight: FontWeight.w700,
                            fontSize: 15,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _blob(double size, Color color) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      );
}

// ============================================================
// Step 1 — Create pet
// ============================================================
class _CreatePet extends StatelessWidget {
  const _CreatePet({
    required this.emoji,
    required this.emojis,
    required this.breeds,
    required this.name,
    required this.breed,
    required this.birthday,
    required this.bio,
    required this.onEmoji,
    required this.onBreed,
    required this.onChanged,
    required this.onNext,
  });

  final String emoji;
  final List<String> emojis;
  final List<String> breeds;
  final TextEditingController name;
  final String breed;
  final TextEditingController birthday;
  final TextEditingController bio;
  final ValueChanged<String> onEmoji;
  final ValueChanged<String> onBreed;
  final VoidCallback onChanged;
  final VoidCallback onNext;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final canNext = name.text.trim().isNotEmpty && breed.isNotEmpty;
    return SafeArea(
      child: Column(
        children: [
          Expanded(
            child: ListView(
              padding: EdgeInsets.zero,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(22, 16, 22, 8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const _Steps(n: 3, i: 1),
                      const SizedBox(height: 18),
                      Text(
                        l10n.onboardCreateTitle,
                        style: const TextStyle(
                          fontSize: 24,
                          fontWeight: FontWeight.w900,
                          letterSpacing: -0.4,
                          color: AppColors.ink,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        l10n.onboardCreateSubtitle,
                        style: const TextStyle(fontSize: 14.5, color: AppColors.muted),
                      ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 22),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      // 头像（引导期用 emoji 选择；真实头像照片在档案创建/编辑页上传）。
                      const SizedBox(height: 16),
                      Center(
                        child: EmojiAvatar(emoji: emoji, size: 104, tone: AppColors.mintTint),
                      ),
                      const SizedBox(height: 14),
                      // emoji 选择
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        alignment: WrapAlignment.center,
                        children: [
                          for (final e in emojis)
                            GestureDetector(
                              onTap: () => onEmoji(e),
                              child: Container(
                                width: 40,
                                height: 40,
                                alignment: Alignment.center,
                                decoration: BoxDecoration(
                                  color: emoji == e ? AppColors.mintTint : AppColors.card,
                                  borderRadius: BorderRadius.circular(12),
                                  border: Border.all(
                                    color: emoji == e ? AppColors.mint : Colors.transparent,
                                    width: 2,
                                  ),
                                  boxShadow: AppShadows.sm,
                                ),
                                child: Text(e, style: const TextStyle(fontSize: 21)),
                              ),
                            ),
                        ],
                      ),
                      const SizedBox(height: 18),
                      _Field(
                        label: l10n.onboardFieldName,
                        hint: l10n.onboardFieldNameHint,
                        child: _input(name, l10n.onboardFieldNamePlaceholder, onChanged),
                      ),
                      _Field(
                        label: l10n.onboardFieldBreed,
                        hint: l10n.onboardFieldOptional,
                        child: Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            for (final b in breeds)
                              Btn3d(
                                variant: breed == b ? Btn3dVariant.primary : Btn3dVariant.soft,
                                onPressed: () => onBreed(b),
                                padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 9),
                                fontSize: 13.5,
                                borderRadius: 12,
                                child: Text(b),
                              ),
                          ],
                        ),
                      ),
                      _Field(
                        label: l10n.onboardFieldBirthday,
                        hint: l10n.onboardFieldOptional,
                        child: _input(birthday, l10n.onboardFieldBirthdayPlaceholder, onChanged),
                      ),
                      _Field(
                        label: l10n.onboardFieldBio,
                        hint: l10n.onboardFieldBioHint,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            _input(bio, l10n.onboardFieldBioPlaceholder, onChanged,
                                maxLength: 30),
                            Padding(
                              padding: const EdgeInsets.only(top: 4),
                              child: Text(
                                '${bio.text.characters.length}/30',
                                textAlign: TextAlign.right,
                                style: const TextStyle(fontSize: 11.5, color: AppColors.muted),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(22, 6, 22, 30),
            child: Btn3d(
              expand: true,
              onPressed: canNext ? onNext : null,
              child: Text(l10n.onboardNext),
            ),
          ),
        ],
      ),
    );
  }

  Widget _input(TextEditingController c, String hint, VoidCallback onChanged,
      {int? maxLength}) {
    return TextField(
      controller: c,
      maxLength: maxLength,
      onChanged: (_) => onChanged(),
      style: const TextStyle(fontSize: 15.5, color: AppColors.ink),
      decoration: InputDecoration(
        counterText: '',
        hintText: hint,
        hintStyle: const TextStyle(color: AppColors.muted, fontSize: 15.5),
        filled: true,
        fillColor: AppColors.card,
        contentPadding: const EdgeInsets.symmetric(horizontal: 15, vertical: 13),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: AppColors.line, width: 1.5),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: AppColors.mint, width: 1.5),
        ),
      ),
    );
  }
}

// ============================================================
// Step 2 — Done
// ============================================================
class _Done extends StatefulWidget {
  const _Done({required this.emoji, required this.name, required this.onEnter});

  final String emoji;
  final String name;
  final VoidCallback onEnter;

  @override
  State<_Done> createState() => _DoneState();
}

class _DoneState extends State<_Done> with TickerProviderStateMixin {
  late final AnimationController _pop = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 500),
  )..forward();
  late final AnimationController _wiggle = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1600),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _pop.dispose();
    _wiggle.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [AppColors.mintTint, AppColors.cream],
          stops: [0.0, 0.6],
        ),
      ),
      child: SafeArea(
        child: Stack(
          children: [
            Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 30),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    ScaleTransition(
                      scale: CurvedAnimation(parent: _pop, curve: Curves.easeOutBack),
                      child: FadeTransition(
                        opacity: _pop,
                        child: Stack(
                          clipBehavior: Clip.none,
                          children: [
                            EmojiAvatar(emoji: widget.emoji, size: 120, tone: AppColors.mintTint),
                            Positioned(
                              top: -8,
                              right: -10,
                              child: AnimatedBuilder(
                                animation: _wiggle,
                                builder: (_, child) => Transform.rotate(
                                  angle: (_wiggle.value - 0.5) * 0.34,
                                  child: child,
                                ),
                                child: const Text('✨', style: TextStyle(fontSize: 30)),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 22),
                    Text(
                      l10n.onboardDoneTitle(widget.name),
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                        fontSize: 26,
                        fontWeight: FontWeight.w900,
                        letterSpacing: -0.4,
                        color: AppColors.ink,
                      ),
                    ),
                    const SizedBox(height: 6),
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 280),
                      child: Text(
                        l10n.onboardDoneBody,
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 15, color: AppColors.ink2, height: 1.5),
                      ),
                    ),
                    const SizedBox(height: 18),
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.public, size: 16, color: AppColors.mint700),
                        const SizedBox(width: 7),
                        Text(
                          'tailtopia.id/m/${widget.name.toLowerCase()}',
                          style: const TextStyle(
                            color: AppColors.mint700,
                            fontSize: 13,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            Positioned(
              left: 24,
              right: 24,
              bottom: 40,
              child: Btn3d(
                expand: true,
                onPressed: widget.onEnter,
                child: Text(l10n.onboardDoneEnter),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ============================================================
// 小部件
// ============================================================
class _Steps extends StatelessWidget {
  const _Steps({required this.n, required this.i});

  final int n;
  final int i;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        for (int k = 0; k < n; k++) ...[
          Expanded(
            child: Container(
              height: 6,
              decoration: BoxDecoration(
                color: k <= i ? AppColors.mint : AppColors.line,
                borderRadius: BorderRadius.circular(4),
              ),
            ),
          ),
          if (k < n - 1) const SizedBox(width: 6),
        ],
      ],
    );
  }
}

class _Field extends StatelessWidget {
  const _Field({required this.label, this.hint, required this.child});

  final String label;
  final String? hint;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.only(bottom: 7),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                Text(label,
                    style: const TextStyle(
                        fontSize: 14.5, fontWeight: FontWeight.w800, color: AppColors.ink)),
                if (hint != null) ...[
                  const SizedBox(width: 8),
                  Text(hint!, style: const TextStyle(fontSize: 11.5, color: AppColors.muted)),
                ],
              ],
            ),
          ),
          child,
        ],
      ),
    );
  }
}
