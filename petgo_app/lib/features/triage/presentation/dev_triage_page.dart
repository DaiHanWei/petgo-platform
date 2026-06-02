import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../domain/triage_result_controller.dart';

/// `@dev` 自测入口（Story 4.1 · F2）。不从任何 UI 链接，仅供开发期手动深链
/// `/dev/triage` 触发「提交 → 短轮询」契约，验证状态机/结果三态映射（联调）。
///
/// 真正的问诊入口 / 上传页 / 等待 spinner / 三态卡 / 红色半屏在 4.3/4.4/4.5。验收后可移除。
class DevTriagePage extends ConsumerWidget {
  const DevTriagePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(triageResultProvider);
    final controller = ref.read(triageResultProvider.notifier);

    return Scaffold(
      appBar: AppBar(title: const Text('Dev · Triage')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Text('phase: ${state.phase.name}'),
            if (state.triageId != null) Text('triageId: ${state.triageId}'),
            if (state.result?.dangerLevel != null)
              Text('dangerLevel: ${state.result!.dangerLevel!.name}'),
            if (state.result?.advice != null) Text('advice: ${state.result!.advice}'),
            const SizedBox(height: 24),
            if (state.isBusy)
              const CircularProgressIndicator()
            else
              ElevatedButton(
                onPressed: () => controller.submitAndPoll(symptomText: 'dev test symptom'),
                child: const Text('Submit & poll'),
              ),
          ],
        ),
      ),
    );
  }
}
