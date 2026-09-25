import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';

/// 红色计数角标（danger 红底 / min-width 16 / 圆角 8 / 白字 10px / >99 显示 `99+`）。
///
/// **样式的唯一来源**。V1.3.0 Story 1.5 从 `notification_bell.dart` 内联的那个 Container
/// 原样抽出来 —— 抽出前它不是一个可复用单位，第二处想用只能照着"再画一个"，而两份手抄的
/// 样式迟早会走散（红色深浅、圆角、字号各差一点，用户在同一屏上看得出来）。
///
/// ⚠️ **视觉参数不得就地改**：两处（通知铃铛未读数、里程碑未庆祝数）共用同一份，
/// 改这里等于同时改两处。要做差异化就新开一个组件，别给本组件加分支参数。
///
/// 定位由调用方负责（各自的 `Stack` + `Positioned`），本组件只管那颗药丸本身。
class CountBadge extends StatelessWidget {
  const CountBadge({super.key, required this.count});

  /// 显示的数字。调用方需自行保证 `> 0` 才渲染本组件（0 应当整个不挂）。
  final int count;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
      constraints: const BoxConstraints(minWidth: 16),
      decoration: BoxDecoration(
        color: AppColors.danger,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        count > 99 ? '99+' : '$count',
        textAlign: TextAlign.center,
        style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.w700),
      ),
    );
  }
}
