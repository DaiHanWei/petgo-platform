import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../../../../core/config/app_download_url.dart';
import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/card_render/card_canvas.dart';
import '../../../../shared/card_render/card_qr.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../../shared/widgets/letter_avatar.dart';
import '../../domain/human_age.dart';

/// 角色切图的物种分支。**只决定取哪张图与胶囊里的物种名**，不参与任何换算。
///
/// `other` 目前走不到（入口按 CAT/DOG 置灰），但设计稿出了这一套图，
/// 且档案的 `petType` 本来就是三值 —— 留着比将来现补一遍便宜。
enum AgeCardSpecies { cat, dog, other }

/// 宠物年龄卡的卡面（V1.3.0 批次 A · Story 5.2 · AC5，2026-09-23 按设计稿换皮）。
///
/// 🔴 **按 1080×1920 设计坐标排版，再整体 contain 缩到 [canvas]**。
/// 设计稿只出了 9:16 一套（角色图是按这个比例画的），所以这里不再按画布重新分配空间 ——
/// 换画布只是把同一张版面等比缩小居中，四周留白由卡面底色补齐（1:1 上不能露黑/透明）。
/// 这样做的代价是 1:1 上卡面会缩到 56%，收益是**任何画布下版式与设计稿逐像素一致**。
///
/// 字段顺序（`文案需求清单.md` §5.0 的字段清单）：
/// 头像 + 名字 → 实际年龄 → **人类年龄当量（主视觉）** → 趣味文案 → 体型档（仅狗）→ 品牌带。
///
/// ## 🔴 不加水印
/// 水印只属 KTP / 护照那类**付费保护**场景（高清无水印图是要花钱的，预览带水印才防得住
/// 截屏白嫖）。年龄卡免费、且**越多人转发越好** —— 加水印既没有要保护的收入，
/// 又让卡面脏得看不清。
class AgeCardTemplate extends StatelessWidget {
  const AgeCardTemplate({
    super.key,
    required this.canvas,
    required this.petName,
    required this.age,
    required this.quip,
    this.species = AgeCardSpecies.cat,
    this.avatarUrl,
    this.sizeLabel,
    this.pawrentName,
  });

  final CardCanvas canvas;
  final String petName;
  final HumanAgeResult age;

  /// 已经取好的那一句趣味文案（随机在调用方做，**不在这里做** ——
  /// build 会被调用多次，放这里每帧都换一句）。
  final String quip;

  /// 取哪一套角色切图 + 信息胶囊里的物种名。
  final AgeCardSpecies species;

  final String? avatarUrl;

  /// 体重区间（**仅狗卡**有，如「9–23 kg」）。猫 / other 传 null ⇒ 胶囊整段省略
  /// （2026-09-23 产品拍板：体重区间只有狗显示）。
  final String? sizeLabel;

  /// Pawrent（当前登录用户昵称）。拿不到就**整行不显示** —— 不占位、不写占位文案。
  final String? pawrentName;

  // ── 设计坐标系（1 单位 = 设计稿 1 像素）────────────────────────────────
  static const double designWidth = 1080;
  static const double designHeight = 1920;

  /// 设计坐标 → 画布坐标的缩放比。**与 [CardFrame] 里那层 FittedBox 的口径一致**
  /// （都是 contain），二维码的导出边长要靠它反算，不能各算各的。
  double get _scale => math.min(canvas.width / designWidth, canvas.height / designHeight);

  /// 角色切图的 y 位置（设计坐标，dx 一律 0、宽一律 1080）。
  ///
  /// 🔴 这些数是**把切图按 dy 合成到纸底、再与效果图逐像素比色**取最小误差得到的
  /// （步长 8px，猫狗残差 ≈24/255，说明基本重合）。不是凭手感调的 ——
  /// 要改先回去对效果图，不要在模拟器上目测微调，目测会把整套图调歪。
  ///
  /// ⚠️ 别用「差分掩码相关」那种只看形状的算法反解：`other/25` 的桌子是淡线稿，
  /// 掩码法会把它整整压低 260px（2026-09-23 实际踩过，模拟器上一眼看出大空档）。
  static const Map<AgeCardSpecies, Map<PetAgeStage, double>> _characterTop = {
    AgeCardSpecies.cat: {
      PetAgeStage.puppy: 560,
      PetAgeStage.young: 560,
      PetAgeStage.middle: 504,
      PetAgeStage.senior: 480,
    },
    AgeCardSpecies.dog: {
      PetAgeStage.puppy: 560,
      PetAgeStage.young: 672,
      PetAgeStage.middle: 560,
      PetAgeStage.senior: 544,
    },
    // other 这一套的效果图与切图本身就对不齐（残差 ≈60–90/255，疑似效果图用的是更早一版
    // 角色稿），取比色最优解；反正入口按 CAT/DOG 置灰，这套图当前走不到。
    // ⚠️ 这一套在比色最优解基础上**整体上移 15px**（2026-09-23 产品看模拟器后两次微调：
    // 先 -20，再 +5 定稿）—— 效果图与切图本就对不齐，比色解偏低，角色压数字压得不够。
    AgeCardSpecies.other: {
      PetAgeStage.puppy: 585,
      PetAgeStage.young: 649,
      PetAgeStage.middle: 617,
      PetAgeStage.senior: 593,
    },
  };

  /// 四档角色的文件名后缀。
  ///
  /// 🔴 判据是 [PetAgeStage]（由当量 N 决定），**不是文件名里的数字、更不是月龄** ——
  /// `_25` 只是设计稿给这一档起的编号，不代表「25 人岁」。
  static const Map<PetAgeStage, String> _characterSlug = {
    PetAgeStage.puppy: '10',
    PetAgeStage.young: '25',
    PetAgeStage.middle: '75',
    PetAgeStage.senior: '100',
  };

  /// 本卡要用的角色切图路径。
  String get characterAsset =>
      'assets/age_card/${species.name}_${_characterSlug[age.stage]}.webp';

  /// 二维码边长（设计坐标）。
  ///
  /// 🔴 **必须按 [_scale] 反算**：卡面整体会被缩到画布里，写死 160 的话 1:1 上出图只有 90px
  /// —— 一张扫不出来的码，而二维码是卡片发到 Story 后唯一的转化通路，坏了没人会立刻发现。
  double get qrSide => math.max(160, CardQr.minExportSide / _scale);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 底色铺满**整个画布**：1:1 上 contain 之后上下会留白，露黑/露透明就是一张废图。
    return ColoredBox(
      color: AppColors.ageCardBg,
      child: Center(
        child: FittedBox(
          fit: BoxFit.contain,
          child: SizedBox(
            width: designWidth,
            height: designHeight,
            child: _design(l10n),
          ),
        ),
      ),
    );
  }

  /// 层级（从下到上）：底色 → 字标 → 主数字 → 角色图 → 徽标 → 底部渐变 → 信息层。
  ///
  /// ⚠️ **主数字在角色图之下**（稿上角色的耳朵/头盖住数字下缘），**但徽标在角色之上**。
  /// 这三层的先后是设计稿的观感来源，调顺序前先回去看效果图。
  Widget _design(AppLocalizations l10n) => Stack(
        // 角色图超出画布底部的部分靠 Stack 默认的 hardEdge 裁掉。
        children: [
          const Positioned.fill(child: ColoredBox(color: AppColors.ageCardBg)),
          _wordmark(),
          _humanYearsNumber(),
          _character(),
          _humanYearsBadge(l10n),
          _bottomScrim(),
          _quipBar(),
          _avatar(),
          _petName(),
          _infoCapsule(l10n),
          ?_pawrent(),
          _qr(),
          _qrHint(l10n),
        ],
      );

  // ── Layer 1/2：品牌字标 + 主数字 + 角色 ───────────────────────────────

  Widget _wordmark() => Positioned(
        top: 88,
        left: 0,
        right: 0,
        child: Center(
          child: SvgPicture.asset(
            'assets/brand/wordmark_brand.svg',
            width: 222,
            height: 68,
            // 字标资产是纯白的（给紫底启动页做的）；浅底卡上不上色就是白字画在白纸上。
            // 用 brandViolet 而非 AppColors.mint：设计稿实测字标与信息胶囊**同色** #7D45F6，
            // 一张卡上出现两种紫是看得出来的。
            colorFilter: const ColorFilter.mode(AppColors.brandViolet, BlendMode.srcIn),
          ),
        ),
      );

  /// 主数字。**纯数字、不带「≈」**（设计稿定稿）——「这是估算」由徽标 TAHUN MANUSIA 承担。
  ///
  /// 字号 503 / 框顶 231 的来历：设计稿数字字身高 362px、字身顶在 y=270。
  /// Rubik Black 的数字实测占 0.72em（比 OS/2 的 capHeight 0.70em 高一点，字重越粗越溢出），
  /// 行高锁 1.0 时字身顶距文本框顶 0.0772em ⇒ 字号 362/0.72 ≈ 503、框顶 270 − 503×0.0772 ≈ 231。
  /// **改字号要连着改框顶**，否则数字会整体上下漂（角色的头就盖不住数字下缘了）。
  Widget _humanYearsNumber() => Positioned(
        top: 231,
        left: 40,
        right: 40,
        height: 503,
        child: FittedBox(
          // 三位数（如 136 人岁）在设计字号下约 980px，正好落在 1000 以内；
          // 真出现更宽的内容时等比缩，不允许溢出画布。
          fit: BoxFit.scaleDown,
          child: Text(
            '${age.humanAge}',
            key: const ValueKey('ageCardHumanYears'),
            style: _rubik(size: 503, weight: 900, color: AppColors.ageCardMagenta),
          ),
        ),
      );

  Widget _character() => Positioned(
        left: 0,
        top: _characterTop[species]![age.stage]!,
        width: designWidth,
        child: Image.asset(
          characterAsset,
          key: const ValueKey('ageCardCharacter'),
          width: designWidth,
          fit: BoxFit.fitWidth,
          excludeFromSemantics: true,
          // 素材缺失不该把整张卡变成一块红 —— 角色没了还有数字、文案与二维码。
          errorBuilder: (_, _, _) => const SizedBox.shrink(),
        ),
      );

  /// 「TAHUN MANUSIA」徽标。两行靠把**第一个空格换成换行**，不在 ARB 里写 `\n`。
  Widget _humanYearsBadge(AppLocalizations l10n) => Positioned(
        left: 982 - 202,
        top: 710,
        width: 202,
        height: 96,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: AppColors.ageCardMagenta,
            borderRadius: BorderRadius.circular(28),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 14),
            child: Center(
              // 🔴 scaleDown 不可省：徽标宽度是定死的 202，而 `HUMAN YEARS` / `TAHUN MANUSIA`
              // 两种语言长度不同，写死字号必有一种语言溢出。
              child: FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  l10n.ageCardHumanYearsBadge.replaceFirst(' ', '\n'),
                  key: const ValueKey('ageCardHumanYearsBadge'),
                  textAlign: TextAlign.center,
                  style: _rubik(
                    size: 44,
                    weight: 700,
                    color: AppColors.onAccent,
                    height: 1.05,
                  ),
                ),
              ),
            ),
          ),
        ),
      );

  // ── Layer 3：底部渐变 ────────────────────────────────────────────────

  /// 底部压暗带（设计稿 `Card Info.png` 的等效实现，**不另存一张图**）。
  ///
  /// 停靠点取自对那张 PNG 的逐行采样：它不是线性的，而是上快下慢、到 2/3 处就已经全不透明。
  /// 按三点线性近似会让胶囊/Pawrent 那一带亮一档，白字压不住底下的角色图。
  Widget _bottomScrim() => const Positioned(
        left: 0,
        right: 0,
        bottom: 0,
        height: 450,
        child: DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              stops: [0, 0.17, 0.33, 0.5, 0.67, 1],
              colors: [
                Color(0x003D2E51),
                Color(0x603D2E51),
                Color(0xB93D2E51),
                Color(0xEE3D2E51),
                Color(0xFF3D2E51),
                Color(0xFF3D2E51),
              ],
            ),
          ),
        ),
      );

  // ── Layer 4：信息层 ─────────────────────────────────────────────────

  /// 趣味文案白条。整体轻微倾斜（设计稿的贴纸感），前后各加一个弯引号。
  Widget _quipBar() => Positioned(
        left: 0,
        right: 0,
        top: 1544 - 106,
        height: 106,
        child: Center(
          child: Transform.rotate(
            angle: -1.5 * math.pi / 180,
            child: ColoredBox(
              color: AppColors.onAccent,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 40),
                child: SizedBox(
                  height: 106,
                  // ⚠️ 这里刻意**不用 `Container(alignment:)`** —— 带 alignment 的 Container
                  // 在松约束下会撑满可用宽度（Align 的 widthFactor 为空即铺满），
                  // 白条就会从画布左边一路白到右边（2026-09-23 实际渲染踩过）。
                  child: ConstrainedBox(
                    // 白条最宽 930（含内边距），文字区就是 930 − 40×2。
                    constraints: const BoxConstraints(maxWidth: 930 - 80),
                    // 🔴 单行 + scaleDown：文案长度是 ARB 里的变量（印尼语更长、句中还带宠物名），
                    // 折行会把白条撑成两行、盖住角色的脸。宁可字小一点。
                    child: FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(
                        '“$quip”',
                        key: const ValueKey('ageCardQuip'),
                        maxLines: 1,
                        softWrap: false,
                        style: _rubik(size: 54, weight: 700, color: AppColors.ageCardInk),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );

  Widget _avatar() => Positioned(
        left: 68,
        top: 1592,
        width: 70,
        height: 70,
        child: avatarUrl == null || avatarUrl!.isEmpty
            ? LetterAvatar(name: petName, size: 70)
            : ClipOval(child: AppImage.widget(avatarUrl!, fit: BoxFit.cover)),
      );

  Widget _petName() => Positioned(
        // 头像右侧 24px 起；右侧给二维码留出位置（二维码占位最宽到 x≈785）。
        left: 68 + 70 + 24,
        right: 300,
        top: 1592,
        height: 70,
        child: Align(
          alignment: Alignment.centerLeft,
          child: Text(
            petName,
            key: const ValueKey('ageCardPetName'),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: _rubik(size: 64, weight: 900, color: AppColors.onAccent),
          ),
        ),
      );

  /// 信息胶囊：`物种 · 实际年龄`（狗再接 ` · 9–23 kg`）。
  ///
  /// 🔴 实际年龄直接取 [HumanAgeResult]，**不在这里再算一遍** —— 档案页与卡面必须同一个数。
  Widget _infoCapsule(AppLocalizations l10n) {
    final realAge = age.months < 12
        ? l10n.ageCardRealAgeMonths(age.months)
        : l10n.ageCardRealAgeYearsMonths(age.years, age.monthsPart);
    final speciesName = switch (species) {
      AgeCardSpecies.cat => l10n.petTypeCat,
      AgeCardSpecies.dog => l10n.petTypeDog,
      AgeCardSpecies.other => l10n.petTypeOther,
    };
    const sep = TextSpan(text: ' · ');
    return Positioned(
      left: 68,
      right: 300,
      top: 1690,
      height: 66,
      child: Align(
        alignment: Alignment.centerLeft,
        // ⚠️ 胶囊要**贴着文字收窄**，所以这里同样不能用带 alignment 的 Container
        //（那会让它铺满 Positioned 给的整段宽度，变成一根横贯半张卡的紫条）。
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: AppColors.brandViolet,
            borderRadius: BorderRadius.circular(33),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 28),
            child: SizedBox(
              height: 66,
              child: FittedBox(
                fit: BoxFit.scaleDown,
                child: Text.rich(
                  TextSpan(
                    children: [
                      TextSpan(text: speciesName),
                      sep,
                      // 实际年龄在设计稿上是加粗的那一段（Pet Info: Rubik Regular & Bold）。
                      TextSpan(
                        text: realAge,
                        style: _rubik(size: 36, weight: 700, color: AppColors.onAccent),
                      ),
                      // 猫 / other 没有体重区间 ⇒ **整段省略**，不留一个空的「 · 」。
                      if (sizeLabel != null) ...[sep, TextSpan(text: sizeLabel!)],
                    ],
                  ),
                  key: const ValueKey('ageCardInfoCapsule'),
                  maxLines: 1,
                  style: _rubik(size: 36, weight: 400, color: AppColors.onAccent),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Pawrent 名。拿不到昵称就**整行不显示**（返回 null，`?` 展开时直接不入 Stack）。
  Widget? _pawrent() {
    final name = pawrentName?.trim();
    if (name == null || name.isEmpty) return null;
    return Positioned(
      left: 68,
      right: 300,
      // 基线 1850：Rubik 行高锁 1.0 时 ascent = 0.789em ⇒ 框顶 = 1850 − 36×0.789。
      top: 1850 - 36 * 0.789,
      child: Text(
        name,
        key: const ValueKey('ageCardPawrent'),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: _rubik(size: 36, weight: 600, color: AppColors.onAccent),
      ),
    );
  }

  /// 🔴 年龄卡的码指向**通用下载页**，不是某条内容的分享链接，也**不带 token**（AD-A19）：
  /// 这张图会被发给陌生人看，任何随图外流的标识都是隐患。
  /// 地址走配置项 [kAppDownloadUrl]，不硬编码在这里。
  Widget _qr() {
    final footprint = CardQr.footprintFor(qrSide);
    return Positioned(
      left: 1006 - footprint,
      top: 1768 - footprint,
      width: footprint,
      height: footprint,
      child: CardQr(data: kAppDownloadUrl, side: qrSide),
    );
  }

  Widget _qrHint(AppLocalizations l10n) => Positioned(
        left: 620,
        right: 1080 - 1006,
        top: 1768 + 22,
        child: Text(
          l10n.shareCardScanHint,
          textAlign: TextAlign.right,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: _rubik(size: 28, weight: 600, color: AppColors.onAccent),
        ),
      );
}

/// 卡面统一字体：Rubik 可变字体。
///
/// 🔴 `fontWeight` 与 `fontVariations` **必须同时给**：可变字体只给 fontWeight 在部分平台
/// 不生效（会静默渲染成默认字重），只给 fontVariations 则 Flutter 的字体回退挑不对字形。
///
/// 行高默认锁 1.0：卡面是按字身位置排的坐标，留了行间距就对不上设计稿。
TextStyle _rubik({
  required double size,
  required int weight,
  required Color color,
  double height = 1.0,
}) =>
    TextStyle(
      fontFamily: 'Rubik',
      fontSize: size,
      height: height,
      color: color,
      // 🔴 显式关掉下划线：卡面可能被挂在没有 Material 祖先的子树里（导出/预览都可能），
      // 那时 `Text` 会继承 WidgetsApp 兜底的「黄色双下划线」告警样式，直接印进导出图。
      decoration: TextDecoration.none,
      fontWeight: FontWeight.values.firstWhere((w) => w.value == weight),
      fontVariations: [FontVariation('wght', weight.toDouble())],
    );
