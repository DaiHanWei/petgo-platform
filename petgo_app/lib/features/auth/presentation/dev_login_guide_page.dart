import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/router/route_intent.dart';
import '../../../core/theme/spacing.dart';
import '../domain/login_guide_controller.dart';

/// `@dev` 自测入口（Story 1.4 F3）。不从任何 UI 链接，仅供开发期手动深链
/// `/dev/login-guide` 触发软浮层 / 强弹窗，验证真实 Google 登录链路与回跳（L2）。
/// 验收后可移除。
class DevLoginGuidePage extends ConsumerWidget {
  const DevLoginGuidePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final guide = ref.read(loginGuideControllerProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Dev · Login Guide')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            ElevatedButton(
              onPressed: () => guide.showSoftSheet(context),
              child: const Text('Show soft sheet'),
            ),
            const SizedBox(height: AppSpacing.lg),
            ElevatedButton(
              onPressed: () => guide.showHardDialog(
                context,
                pendingAction: const RouteIntent(location: '/triage'),
              ),
              child: const Text('Show hard dialog (pending → /triage)'),
            ),
          ],
        ),
      ),
    );
  }
}
