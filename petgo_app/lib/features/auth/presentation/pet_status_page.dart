import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../features/content/domain/home_refresh_provider.dart';
import '../../../l10n/app_localizations.dart';
import '../data/me_repository.dart';
import '../domain/auth_state.dart';
import '../domain/onboarding_branch.dart';

/// 宠物状态选择页（Story 1.6 F2/F3，FR-0F）。1:1 还原 pages/pet-select.html。
/// 顶部返回 + 进度条(2/2) + 标题 + 两选项卡（选中态紫边+checkmark）+ Lanjut。整页可滚。
class PetStatusPage extends ConsumerStatefulWidget {
  const PetStatusPage({super.key});

  @override
  ConsumerState<PetStatusPage> createState() => _PetStatusPageState();
}

class _PetStatusPageState extends ConsumerState<PetStatusPage> {
  String? _selected;
  bool _busy = false;

  Future<void> _onComplete() async {
    final status = _selected;
    if (status == null || _busy) return;
    setState(() => _busy = true);
    try {
      final profile = await ref.read(meRepositoryProvider).updatePetStatus(status);
      ref.read(authControllerProvider.notifier).completeOnboarding(profile);
      ref.read(homeRefreshProvider.notifier).bump();
      if (!mounted) return;
      context.go(petStatusBranchLocation(status));
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text(AppLocalizations.of(context).loginFailed)));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _onBackToNickname() => context.go('/onboarding/nickname');

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _onBackToNickname();
      },
      child: Scaffold(
        backgroundColor: Colors.white,
        body: SafeArea(
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
                  child: Row(children: [
                    GestureDetector(
                      key: const ValueKey('petStatusBack'),
                      onTap: _onBackToNickname,
                      child: Container(
                        width: 36, height: 36,
                        decoration: BoxDecoration(color: const Color(0xFFEFEDF3), borderRadius: BorderRadius.circular(11)),
                        child: const Icon(Icons.arrow_back, size: 18, color: Color(0xFF544864)),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(child: Row(children: [
                      Expanded(child: Container(height: 3, decoration: BoxDecoration(color: const Color(0xFF845EC9), borderRadius: BorderRadius.circular(999)))),
                      const SizedBox(width: 5),
                      Expanded(child: Container(height: 3, decoration: BoxDecoration(color: const Color(0xFF845EC9), borderRadius: BorderRadius.circular(999)))),
                    ])),
                    const SizedBox(width: 12),
                    const Text('2 / 2', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: Color(0xFF9690A6))),
                  ]),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 34, 24, 24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(l10n.onboardingPetStatusTitle,
                          style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, height: 1.3, color: Color(0xFF2E2A45))),
                      const SizedBox(height: 6),
                      Text(l10n.onboardingPetStatusSubtitle,
                          style: const TextStyle(fontSize: 13, color: Color(0xFF9690A6))),
                      const SizedBox(height: 28),
                      _OptionCard(
                        emoji: '🐶🐱',
                        title: l10n.onboardingPetStatusHasPetTitle,
                        desc: l10n.onboardingPetStatusHasPetDesc,
                        selected: _selected == 'HAS_PET',
                        valueKey: 'petStatus_HAS_PET',
                        onTap: () => setState(() => _selected = 'HAS_PET'),
                      ),
                      const SizedBox(height: 11),
                      _OptionCard(
                        emoji: '🤍',
                        title: l10n.onboardingPetStatusPlanningTitle,
                        desc: l10n.onboardingPetStatusPlanningDesc,
                        selected: _selected == 'PLANNING',
                        valueKey: 'petStatus_PLANNING',
                        onTap: () => setState(() => _selected = 'PLANNING'),
                      ),
                      const SizedBox(height: 32),
                      _PetStatusButton(enabled: _selected != null && !_busy, busy: _busy, label: l10n.onboardingDone, onTap: _onComplete),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _OptionCard extends StatelessWidget {
  const _OptionCard({required this.emoji, required this.title, required this.desc, required this.selected, required this.valueKey, required this.onTap});
  final String emoji, title, desc, valueKey;
  final bool selected;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      key: ValueKey(valueKey),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: selected ? const Color(0xFF845EC9) : const Color(0xFFE6E6E6), width: selected ? 2 : 1.5),
          boxShadow: selected ? [BoxShadow(color: const Color(0xFF845EC9).withValues(alpha: 0.12), blurRadius: 16, offset: const Offset(0, 4))] : null,
        ),
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(emoji, style: const TextStyle(fontSize: 34)),
              const SizedBox(height: 9),
              Text(title, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Color(0xFF2E2A45))),
              const SizedBox(height: 4),
              Text(desc, style: const TextStyle(fontSize: 12, height: 1.5, color: Color(0xFF544864))),
            ]),
            if (selected)
              Positioned(
                top: 0, right: 0,
                child: Container(
                  width: 22, height: 22,
                  decoration: const BoxDecoration(shape: BoxShape.circle, color: Color(0xFF845EC9)),
                  child: const Icon(Icons.check, size: 12, color: Colors.white),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _PetStatusButton extends StatelessWidget {
  const _PetStatusButton({required this.enabled, required this.busy, required this.label, required this.onTap});
  final bool enabled, busy;
  final String label;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(14),
        boxShadow: enabled ? [BoxShadow(color: const Color(0xFF845EC9).withValues(alpha: 0.28), blurRadius: 20, offset: const Offset(0, 8))] : null,
      ),
      child: FilledButton(
        key: const ValueKey('petStatusComplete'),
        onPressed: enabled ? onTap : null,
        style: FilledButton.styleFrom(
          backgroundColor: const Color(0xFF845EC9),
          disabledBackgroundColor: const Color(0xFFC2B0EC),
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(vertical: 15),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        ),
        child: busy
            ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
            : Text(label, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
      ),
    );
  }
}
