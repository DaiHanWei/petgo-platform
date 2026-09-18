import 'dart:math';

import '../../../l10n/app_localizations.dart';
import 'human_age.dart';

/// 年龄卡的趣味文案池（V1.3.0 批次 A · Story 5.2 · AC6 · AD-A18.5）。
///
/// ## 这段文字和 App 里其它文案不是一回事
/// 它**不是界面文字，是印在分享图片上的一句话** —— 会被发到 WhatsApp 与
/// Instagram Stories 上给陌生人看。三条由此而来的约束：
/// 1. **短**：单条 ≤ 60 字符，超了会在 9:16 卡上折三行、挤掉设计留白；
/// 2. **仍走 l10n**：按用户设备语言取（印尼用户生成的卡上是印尼语），不走服务端下发；
/// 3. **每次随机**：同一只宠物反复生成不重样 —— 这是让用户愿意多生成、多分享的关键，
///    也是本 FR 核心指标（生成 / 分享次数）的直接抓手。
///
/// 文案定稿见 `文案需求清单.md` §5（4 段 × 5 条 × 2 语）。

/// 一条文案：给它 l10n 与宠物名，它给出最终那句话。
typedef AgeCardQuip = String Function(AppLocalizations l10n, String name);

/// 某一段的全部候选。
///
/// 🔴 段由 [PetAgeStage] 决定，而 [PetAgeStage] 由**卡面展示的当量整数**决定 ——
/// 不是实际月龄。两者若各算各的，会出现卡上写 ≈40 人岁却配了幼年文案。
List<AgeCardQuip> quipsFor(PetAgeStage stage) => switch (stage) {
      PetAgeStage.puppy => [
          (l, n) => l.ageCardQuipPuppy1,
          (l, n) => l.ageCardQuipPuppy2(n),
          (l, n) => l.ageCardQuipPuppy3(n),
          (l, n) => l.ageCardQuipPuppy4,
          (l, n) => l.ageCardQuipPuppy5(n),
        ],
      PetAgeStage.young => [
          (l, n) => l.ageCardQuipYoung1(n),
          (l, n) => l.ageCardQuipYoung2,
          (l, n) => l.ageCardQuipYoung3(n),
          (l, n) => l.ageCardQuipYoung4(n),
          (l, n) => l.ageCardQuipYoung5(n),
        ],
      PetAgeStage.middle => [
          (l, n) => l.ageCardQuipMiddle1(n),
          (l, n) => l.ageCardQuipMiddle2,
          (l, n) => l.ageCardQuipMiddle3(n),
          (l, n) => l.ageCardQuipMiddle4,
          (l, n) => l.ageCardQuipMiddle5(n),
        ],
      PetAgeStage.senior => [
          (l, n) => l.ageCardQuipSenior1(n),
          (l, n) => l.ageCardQuipSenior2,
          (l, n) => l.ageCardQuipSenior3(n),
          (l, n) => l.ageCardQuipSenior4,
          (l, n) => l.ageCardQuipSenior5(n),
        ],
    };

/// 从该段里随机取一条。
///
/// [random] 只为测试可控而存在 —— 生产路径不传，走默认随机源。
AgeCardQuip pickQuip(PetAgeStage stage, {Random? random}) {
  final pool = quipsFor(stage);
  return pool[(random ?? Random()).nextInt(pool.length)];
}
