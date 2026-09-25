import '../../../l10n/app_localizations.dart';
import 'human_age.dart';

/// 年龄卡的趣味文案（V1.3.0 批次 A · Story 5.2 · AC6 · AD-A18.5）。
///
/// ## 这段文字和 App 里其它文案不是一回事
/// 它**不是界面文字，是印在分享图片上的一句话** —— 会被发到 WhatsApp 与
/// Instagram Stories 上给陌生人看。两条由此而来的约束：
/// 1. **短**：单条 ≤ 60 字符，超了会在 9:16 卡上折三行、挤掉设计留白；
/// 2. **仍走 l10n**：按用户设备语言取（印尼用户生成的卡上是印尼语），不走服务端下发。
///
/// ## 🔴 一段一句，不随机（2026-09-23 产品拍板）
/// 原实现是每段 5 条随机取一条（`文案需求清单.md` §5 的「每次随机取一条」）。
/// 换皮后**文案要与该段的角色图配对**（奶嘴气球 / 笔记本 / 咖啡 / 摇椅），
/// 一段一句，同一只宠物反复生成也是同一句 —— 随机会让文案与画面对不上。
/// ⚠️ 想恢复随机得先补齐「每段 5 句都配得上这张图」的文案，别直接把旧池子接回来。
///
/// 🔴 段由 [PetAgeStage] 决定，而 [PetAgeStage] 由**卡面展示的当量整数**决定 ——
/// 不是实际月龄。两者若各算各的，会出现卡上写 40 人岁却配了幼年文案。
String quipFor(AppLocalizations l10n, PetAgeStage stage) => switch (stage) {
      PetAgeStage.puppy => l10n.ageCardQuipPuppy,
      PetAgeStage.young => l10n.ageCardQuipYoung,
      PetAgeStage.middle => l10n.ageCardQuipMiddle,
      PetAgeStage.senior => l10n.ageCardQuipSenior,
    };
