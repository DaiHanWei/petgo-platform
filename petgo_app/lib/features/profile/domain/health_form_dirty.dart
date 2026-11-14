import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 健康记录表单「有未保存的改动」标记（V1.5.0 Story 3.2 · AC6，PRD FR-167 Fallbacks）。
///
/// 表单打开且任一字段与初值不同 → true；保存成功 / 关闭抽屉 → false。Story 3.4 在推送 / 通知中心点开
/// 健康提醒**导航之前**读它：true 时只弹 toast、不切宠物不跳转，免得用户填了一半的表单被盖掉。
class HealthFormDirtyNotifier extends Notifier<bool> {
  @override
  bool build() => false;

  void set(bool dirty) {
    // 表单 dispose 后的收尾可能晚于 ProviderScope 销毁（退出页面 / 测试结束）→ 已卸载就忽略。
    if (!ref.mounted) return;
    if (state != dirty) state = dirty;
  }
}

final healthFormDirtyProvider =
    NotifierProvider<HealthFormDirtyNotifier, bool>(HealthFormDirtyNotifier.new);
