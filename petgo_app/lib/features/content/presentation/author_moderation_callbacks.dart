import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'feed_controller.dart';

/// 在迷你卡里对某个**作者**动手（拉黑 / 举报）成功之后，当前这一屏的统一收尾。
///
/// **为什么要立刻动这一屏**：两层弹层收起后，用户回到的通常就是 Feed，而那个人的帖子还挂在上面。
/// 不处理的话，用户举报/拉黑完抬头就看见「我明明处理了，他的东西还在」——
/// 这正是本版本要消灭的体验，只是推迟了几秒。服务端过滤保证的是**下次拉取**不再出现，
/// 这里补的是**现在这一屏**。
///
/// **为什么抽成一个函数**：Feed 侧与详情页侧原先各自独立写了一遍乐观移除，
/// 拉黑与举报又各要一份 —— 四处几乎相同的逻辑，改一处漏一处的风险是真实的。
///
/// ⚠️ **静默，不弹任何提示**。「已不再向你展示 TA 的内容」这类话会直接泄露
/// 「举报会隐藏内容」，与举报成功态刻意不提隐藏的取舍冲突。
/// （既有的**帖子级**举报是会弹 `reportHiddenToast` 的 —— 两者行为不同是有意为之，别顺手复用那段。）
///
/// [popContext] 非空 = 调用方是**内容详情页**：先退回上一级列表再移除 ——
/// 停在被举报者的帖子详情页上是明显穿帮（何况那一页服务端已经返回 404 了）。
VoidCallback onAuthorHidden(WidgetRef ref, int authorId, {BuildContext? popContext}) {
  return () {
    if (popContext != null && popContext.mounted) {
      Navigator.of(popContext).maybePop();
    }
    // ⚠️ 是按**作者**移除他的全部卡片，不是只移除点开的那一条。
    // 列表会因此突然变短，产品已接受这个代价：不补位、不占位、不提示「已移除 N 条」——
    // 任何补偿动作都会重新暴露「刚才发生了什么」。
    ref.read(feedProvider.notifier).removeByAuthor(authorId);
  };
}
