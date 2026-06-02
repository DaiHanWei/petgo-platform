import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../data/vet_repository.dart';
import '../domain/vet_login_response.dart';

/// 兽医工作台壳（Story 5.1 占位）。
///
/// 本故事仅证明 role=VET 登录链路通：调 `GET /vet/me` 显示 displayName。
/// Tab Bar（待接单/进行中/历史/我的）由 Story 5.2 填充。
class VetWorkbenchShell extends ConsumerStatefulWidget {
  const VetWorkbenchShell({super.key});

  @override
  ConsumerState<VetWorkbenchShell> createState() => _VetWorkbenchShellState();
}

class _VetWorkbenchShellState extends ConsumerState<VetWorkbenchShell> {
  late Future<VetMe> _me;

  @override
  void initState() {
    super.initState();
    _me = ref.read(vetRepositoryProvider).me();
  }

  Future<void> _logout() async {
    await ref.read(vetRepositoryProvider).logout();
    ref.read(authControllerProvider.notifier).toGuest();
    if (mounted) context.go('/home');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        title: Text(l10n.vetWorkbenchTitle),
        actions: [
          TextButton(
            key: const ValueKey('vetLogoutButton'),
            onPressed: _logout,
            child: Text(l10n.vetLogout),
          ),
        ],
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: FutureBuilder<VetMe>(
            future: _me,
            builder: (context, snapshot) {
              final name = snapshot.data?.displayName ?? '';
              return Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (name.isNotEmpty)
                    Text(name, key: const ValueKey('vetDisplayName'), style: AppTypography.headline),
                  const SizedBox(height: AppSpacing.md),
                  Text(l10n.vetWorkbenchComingSoon, style: AppTypography.body),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
