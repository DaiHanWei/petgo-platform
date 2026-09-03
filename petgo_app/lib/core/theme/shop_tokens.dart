import 'package:flutter/widgets.dart';

import 'colors.dart';

/// 设计 token —— **精选自营电商板块专用**（V1.4.0 · `design_handoff_ecommerce/04_tokens_and_states.md`）。
///
/// ## 为什么另起一套，而不是扩 [AppColors]
///
/// 2026-08-19 决策：电商设计稿的三色分工（玫红=价格 / 紫=平台能力 / 墨=已完成态）与全局
/// 既有色板**语义冲突**——`AppColors.popRed` 已被「危险 / 点赞 / 疫苗 / 分诊红」占满，
/// 若把设计稿的玫红并进去，全 app 会同时存在两个红且分不清谁管什么。
/// 故本套 token **作用域限定在电商子树**：`/shop/**`、`/me/addresses/**`、电商订单相关页面。
/// 其余页面（Diary / Health / Social / Me）继续用 [AppColors]，本文件**禁止**在电商子树外引用。
///
/// ## 与既有 token 的关系
///
/// 值完全一致的**直接别名回 [AppColors]**（遵循 handoff「先别自己发明新命名」）：
/// - [purple] ← `AppColors.mint`（#845EC9，常量名叫 mint 是历史包袱，值是紫）
/// - [text2]  ← `AppColors.ink2`（#544864）
///
/// 其余为设计稿独有值，与全局色板**刻意不同**，勿「顺手统一」：
/// | 设计 token | 本文件 | 全局近似值 | 差异原因 |
/// |---|---|---|---|
/// | `rose` #E1485F | [rose] | `popRed` #F0425A | 全局红另有语义，不可复用 |
/// | `bg` #F3F1F8 | [bg] | `cream` #FFFFFF | 3px 灰缝密度设计依赖灰底，白底会让分隔消失 |
/// | `ink` #2E2742 | [ink] | `AppColors.ink` #2E2A45 | 设计稿终值，差 3 个色阶 |
/// | `text-3` #8A8398 | [text3] | `muted` #9690A6 | 同上 |
///
/// 🔴 **命名陷阱**：设计稿的 `ink-2` (#3A3154) 是渐变墨底的浅端，与 `AppColors.ink2`
/// (#544864，= 设计稿的 `text-2`) **同名不同义**。本文件把前者命名为 [inkLight] 以避免
/// 两个 `ink2` 在同一份 import 里静默取错色，对照设计文档时请注意这处改名。
class ShopColors {
  ShopColors._();

  // ============================================================
  // 主色 —— 三色分工，README「Color Semantics」定为信息骨架，不可混用
  // ============================================================

  /// 商业强调色。**唯一用途**：价格、促销、未完成的付款动作、电商类订单徽标。
  ///
  /// ⚠️ 2026-08-21 产品指定：由设计稿的玫红 #E1485F 改为 **TailTopia 品牌紫**，
  /// 理由是 Toko 要和 app 其余部分看起来是同一个产品。改名 rose → accent 是同批做的：
  /// 一个叫 rose 的紫色 token 是个会留很久的坑（参见 [AppColors.mint] —— 那个名字叫薄荷
  /// 的紫色常量，注释里至今写着「历史包袱」）。
  ///
  /// 🔴 与 [purple] **当前同值但仍是两个旋钮**：purple 管平台能力，accent 管钱与转化。
  /// 日后若要重新拉开二者，改这里一行即可，不必回头去分辨 41 个调用点当初想表达哪个语义。
  ///
  /// ⚠️ <b>但要清楚现在的后果</b>（2026-08-27 审查）：设计稿「紫 = PawCoin、玫红 = 真钱，
  /// 用户据此把『怎么付的』和『怎么退的』对上」这条规则，在两者同值期间**事实上不成立** ——
  /// `ShopLeftAccentBlock.pawcoin` 与 `.money` 的左色条对比度 1.00:1，底色 1.02:1，肉眼不可分。
  /// 依赖颜色区分二者的界面（结算页 / 待支付页 / 退款页的支付构成）目前靠**文案**承担这层语义。
  /// 要恢复颜色通道需产品决策，见 `docs/design/.decision-log.md` D-2。
  ///
  /// 🔴 推论式规则（设计稿明列，换色后逐条仍适用）：
  /// - 售罄商品与超服务范围页的价格转 [text4] 灰，**不用强调色** —— 不用一个买不到的价格做促销刺激。
  /// - 已支付订单的价格用 [ink] 墨色 —— 强调色只留给「还需要付钱」的动作。
  static const Color accent = AppColors.mint; // #845EC9

  /// 强调色深 —— 徽标文字、按下态。
  static const Color accentDark = AppColors.mint600; // #6C48AE

  /// 强调色块底 —— 支付真钱侧、电商徽标底。
  static const Color accentBg = AppColors.mintTint; // #F8F2FF

  /// 错误态专用红。**从原玫红里拆出来的**：换紫之后错误边框若跟着变紫，
  /// 「这里填错了」就读不出来了 —— 错误必须保持红。
  /// 直接别名回全局 [AppColors.popRed]，电商侧不再自造第二个红。
  static const Color error = AppColors.popRed; // #F0425A

  /// 错误文字深色 —— 错误块内的正文/标题，比 [error] 更深以保证对比度。
  static const Color errorText = AppColors.healthEventText; // #C4263C

  /// 紫。用途：平台能力（宠物档案、PawCoin、推荐系统、虚拟服务类订单徽标）
  /// **以及电商顶栏底色**（2026-09-03 产品拍板，原为 [ink] 墨底）。
  ///
  /// ⚠️ 「唯一用途 = 平台能力」这句原先的硬规则已被上面那次拍板放宽 ——
  /// 现在它同时是电商板块的主题色。新增用法仍需过产品，别自行扩散。
  ///
  /// 🔴 游客态 / 无档案时，依赖档案的紫色元素**整块不渲染**，不降级成灰色占位。
  ///
  /// 🔴 白字压在它上面是 **4.75:1**，刚过 AA 的 4.5 —— 用它做底时前景只能是纯白，
  /// 半透明白（onInk45/60/85）一律不达标。
  static const Color purple = AppColors.mint; // #845EC9，与全局主色同值

  /// 紫深 —— 虚拟服务徽标文字、链接按下态。
  ///
  /// ⚠️ 与 `AppColors.mint600` (#6C48AE) 差 1 个色阶，是设计稿终值，非笔误。
  static const Color purpleDark = Color(0xFF6B45AE);

  /// 紫块内的正文色。
  static const Color purpleText = Color(0xFF4A3D66);

  /// 紫块底 —— PawCoin、档案、推荐说明。
  static const Color purpleBg = Color(0xFFF8F5FE);

  /// 紫标签底 / 紫图标块底。
  static const Color purpleTagBg = Color(0xFFF1ECFB);

  /// 墨。导航栏、次级按钮、已付订单价格、正文标题。
  static const Color ink = Color(0xFF2E2742);

  /// 渐变墨底的浅端（设计稿 `ink-2`，见上方命名陷阱说明）。
  /// 用于视频卡 `linear-gradient(180deg, inkLight, ink)` 与墨底之上的占位条纹。
  static const Color inkLight = Color(0xFF3A3154);

  // ============================================================
  // 中性色
  // ============================================================

  /// 页面底色 · **3px 区块灰缝** · chips 未选底。三者同色是设计意图：
  /// 区块之间靠「露出底色」分隔，而不是卡片圆角 + 外边距。
  static const Color bg = Color(0xFFF3F1F8);

  /// 内容块底。
  static const Color surface = Color(0xFFFFFFFF);

  /// 主文字（与 [ink] 同值，语义不同：一个是文字色，一个是墨底/导航色）。
  static const Color text = ink;

  /// 正文、说明。
  static const Color text2 = AppColors.ink2; // #544864，与全局同值

  /// 次要信息、时间、灰标签。
  ///
  /// 🔴 2026-08-27 由设计稿的 #8A8398 压深到 #726C7E：原值在白底只有 **3.63:1**、
  /// 在 [bg] 灰底 3.24:1，达不到 WCAG AA 的 4.5。而它承载的是库存数、订单号、
  /// 配送时间这类**影响购买决策**的信息，不是装饰文字，不能按「弱化文字可以低对比」处理。
  /// 现值白底 5.05:1 / 灰底 4.50:1，与 [text2] (8.44) 的层级差仍在。
  static const Color text3 = Color(0xFF726C7E);

  /// 占位、禁用文字、**售罄价格**。
  ///
  /// 🔴 2026-08-27 由 #A79FBE 压深到 #8C859F：原值白底 **2.52:1**，
  /// 连大字号的 3:1 都不到 —— 而详情页售罄价用它渲染 24px 主价。
  /// 现值 3.52:1，满足大字号 AA，同时仍明显弱于 [text3]，「买不到」的降权语义不丢。
  ///
  /// ⚠️ 降权靠**色阶**而不是 `Opacity`：透明度会把它盖住的所有层级一起拉平
  /// （见 checkout_page_v2 超范围态的处置）。
  static const Color text4 = Color(0xFF8C859F);

  /// 输入框描边、时间线竖线、开关关闭态轨道。
  static const Color border = Color(0xFFE4DCF7);

  /// 浅分隔线、禁用块底。
  static const Color border2 = Color(0xFFEFECF7);

  /// 块内分隔线。
  static const Color divider = Color(0xFFF0EDF7);

  /// 设置项分隔线（更浅）。
  static const Color divider2 = Color(0xFFF5F2FA);

  /// 禁用图标（步进器 `+` 触顶、软提示 `×`）。
  static const Color disabledText = Color(0xFFCFC7DE);

  // ============================================================
  // 警示色 —— 开封不退 / 运费归属 / PawCoin 不可提现
  // ============================================================

  /// 橙提示块底。
  static const Color warnBg = Color(0xFFFDF7F0);

  /// 橙提示块描边（多数块无边框，仅个别用）。
  static const Color warnBorder = Color(0xFFF0DCC8);

  /// 橙提示标题。
  ///
  /// 🔴 2026-08-27 由 #9A6B33 微调到 #976932：原值在 [warnBg] 上 4.36:1，差 0.14 不达标。
  /// 这个块承载「开封不退」（FR-104 三处明示），是合规位点，不能踩线。现值 4.50:1。
  static const Color warnTitle = Color(0xFF976932);

  /// 橙提示正文。
  static const Color warnText = Color(0xFF8A6C4A);

  // ============================================================
  // 半透明 —— 用于墨底之上
  // ============================================================

  /// 顶栏胶囊底（PawCoin 余额 / `Masuk`）。
  static const Color onInk12 = Color(0x1FFFFFFF); // rgba(255,255,255,.12)

  /// 墨底卡内的商品条底（Diary 触发卡）。
  static const Color onInk07 = Color(0x12FFFFFF); // .07

  /// 墨底之上的描边（次按钮）。
  static const Color onInk20 = Color(0x33FFFFFF); // .2

  /// 极弱文字（`Kenapa ini muncul?`）。
  ///
  /// 🔴 2026-08-27 由 .45 提到 .50：墨底之上 .45 只有 4.10:1。名字保留 `onInk45`
  /// 是因为 25 个调用点按「墨底上最弱的那一档」引用它，语义没变，只是值达标了。
  static const Color onInk45 = Color(0x80FFFFFF); // .50（原 .45 = 4.10:1，不达标）

  /// 次要文字（推算依据行）。
  static const Color onInk60 = Color(0x99FFFFFF); // .6

  /// Toko 顶部 banner 的渐变遮罩起止色（2026-08-27）。
  ///
  /// 🔴 **不是装饰，是可读性的唯一保障**：banner 图内容完全由运营决定，
  /// 浅色图上白色的标题与按钮会直接看不见。顶部压一层由深到透的渐变，
  /// 让顶栏文字在任何图上都有足够对比度。
  /// 🔴 <b>三段式，不是两段</b>（2026-09-03 改）。原来是 .55 → 0 的线性渐变，
  /// 算下来在**标题那一带**（状态栏下沿到顶栏中部）只剩 14–29% 黑 ——
  /// 实测白色「Shop」压在 banner 亮部只有 <b>2.45:1</b>，低于图形/大字的 3:1 下限。
  /// 线性渐变的问题在于：它把最深的部分给了**状态栏**那条（那里只有系统图标），
  /// 真正要保护的顶栏文字反而落在已经淡下去的一段。
  ///
  /// 现在改成「顶栏范围内维持深色、出了顶栏再快速淡出」：
  /// [bannerScrimTop] .78 → [bannerScrimMid] .62（正好在顶栏下沿）→ 全透明。
  /// 标题带因此稳定在 ~.66–.70，最坏情形（近白的运营图，L≈.9）白字仍有 3.4:1。
  /// ⚠️ 代价照旧是「图的上缘更暗」—— 但那一条本就被状态栏和顶栏占着，
  /// 不是主视觉；而标题看不看得见没有权衡余地。
  /// 分段位置由页面按 `(topInset + kShopAppBarHeight) / 渐变总高` 算出，见 _BannerAppBar。
  static const Color bannerScrimTop = Color(0xC7000000); // rgba(0,0,0,.78)

  /// 顶栏下沿处的中间停靠色，见 [bannerScrimTop]。
  static const Color bannerScrimMid = Color(0x9E000000); // rgba(0,0,0,.62)

  static const Color bannerScrimBottom = Color(0x00000000); // 全透明

  /// 近正文（倒计时块的说明行）。
  static const Color onInk85 = Color(0xD9FFFFFF); // .85

  /// 玫红条上的倒计时底。
  static const Color countdownScrim = Color(0x38000000); // rgba(0,0,0,.22)

  /// 商品图上的圆形按钮底（返回 / 收藏 / 购物车）。
  static const Color imageButtonScrim = Color(0x8C2E2742); // rgba(46,39,66,.55)

  /// 顶栏胶囊（PawCoin 余额 / 登录 / 购物车）压在 **banner 图**上时的底色（2026-09-03）。
  ///
  /// 🔴 .88 不是"看着深一点"，是按最坏情形算出来的下限：banner 图由运营上传、内容不可控，
  /// 亮部可以接近纯白，白字要在那上面仍过 AA（4.5:1）就只能压这么深。
  /// 实测（stag banner，1080px 截图取样）：原来的 [onInk12]（白 12%）只有 **1.78:1**，
  /// 购物车图标 2.27:1 —— 文字和图标双双不达标，这正是 2026-09-03 产品反馈的
  /// 「有毛玻璃框但白字看不清」。
  /// ⚠️ 不要照抄 [imageButtonScrim] 的 .55：那是压在**商品图**上的按钮，图偏白时同样只有
  /// 2.2:1（已知问题，另案），别把它当作可用的先例。
  static const Color imageCapsuleScrim = Color(0xE02E2742); // rgba(46,39,66,.88)

  /// 全幅商品图**顶部**的渐变蒙层（墨色 .35 → 全透明）。
  ///
  /// 🔴 商品详情页的图区顶到状态栏之下，而该页没有 AppBar 去声明 overlay 样式。
  /// 浅色商品图上系统状态栏图标会整排看不见 —— 这层渐变是兜底，
  /// 同时也给图上的返回 / 购物车按钮当底衬。
  static const List<Color> imageTopScrim = [Color(0x592E2742), Color(0x002E2742)];

  /// 售罄蒙层 —— 详情页整图覆盖。
  static const Color soldOutScrimDetail = Color(0x802E2742); // .5

  /// 售罄蒙层 —— 列表/网格卡。比详情页略重，因为卡片小、需要更强的「买不到」信号。
  static const Color soldOutScrimCard = Color(0x942E2742); // .58

  /// 失效商品蒙层 —— 购物车失效分组。最轻，因为整组已有 `opacity: .75`。
  static const Color soldOutScrimCart = Color(0x732E2742); // .45

  // ============================================================
  // 占位图
  // ============================================================

  /// 白底之上的商品图占位斜纹（设计稿 `repeating-linear-gradient` 45°，5px/10px）。
  static const List<Color> placeholderStripe = [Color(0xFFEDE9F6), Color(0xFFF6F4FB)];

  /// 墨底之上的占位斜纹（4px/8px）。
  static const List<Color> placeholderStripeOnInk = [Color(0xFF4A4166), inkLight];
}

/// 设计 token —— 电商板块的字体阶。
///
/// 与全局 [AppTypography] **刻意不共用**：全局 scale 是 11–30px / w400–w700，
/// 电商稿是 9–30px 且大量 w800，两套的字号-字重组合几乎无交集，强行复用会两头都不像。
///
/// 🔴 **最小字号 9px 仅用于徽标与角标，正文不低于 10px**（设计稿硬规则）。
///
/// 字族：全部 UI 文案用 Poppins（全局 `fontFamily`，此处不重复声明）；
/// **订单号 / 运单号 / 倒计时用 [mono]** —— 等宽避免倒计时逐秒刷新时横向抖动。
class ShopText {
  ShopText._();

  /// 等宽字族名（见 `pubspec.yaml`：已子集化到字母数字与分隔符）。
  ///
  /// ⚠️ 只可用于订单号 / 运单号 / 倒计时 / 文件路径。渲染任何用户输入或印尼语文案
  /// 都会缺字形 → 豆腐块，需先重跑子集。
  static const String mono = 'IBMPlexMono';

  // --- 标题 ---

  /// 页面大标题（Toko 首页 / 订单列表）。
  static const TextStyle pageTitle = TextStyle(
      fontSize: 20, fontWeight: FontWeight.w800, letterSpacing: -.3, color: ShopColors.surface);

  /// 导航栏标题（墨底之上）。
  static const TextStyle navTitle = TextStyle(
      fontSize: 14.5, fontWeight: FontWeight.w700, color: ShopColors.surface);

  /// 区块标题（`Metode Pembayaran` / `Riwayat pengiriman`）。
  static const TextStyle sectionTitle =
      TextStyle(fontSize: 13.5, fontWeight: FontWeight.w700, color: ShopColors.text);

  /// 组头（大写灰，`JENIS PENGINGAT` / `Tidak tersedia (1)`）。
  static const TextStyle groupHeader = TextStyle(
      fontSize: 10, fontWeight: FontWeight.w700, letterSpacing: .4, color: ShopColors.text3);

  // --- 价格（全部 w800，颜色由调用点按状态给：在售玫红 / 已付墨 / 售罄灰）---

  /// 详情页主价。
  static const TextStyle priceHero =
      TextStyle(fontSize: 26, fontWeight: FontWeight.w800, letterSpacing: -.5);

  /// 底部操作条总价。
  static const TextStyle priceTotal =
      TextStyle(fontSize: 19, fontWeight: FontWeight.w800, letterSpacing: -.3);

  /// 网格商品卡价格。
  static const TextStyle priceGrid = TextStyle(fontSize: 16, fontWeight: FontWeight.w800);

  /// 横滑商品卡价格。
  static const TextStyle priceRail = TextStyle(fontSize: 15, fontWeight: FontWeight.w800);

  /// 行内价格（购物车行 / 结算商品行 / 退款拆分）。
  static const TextStyle priceInline = TextStyle(fontSize: 13.5, fontWeight: FontWeight.w800);

  /// 划线原价。**必须与促销价成对出现** —— 后台无原价则两者都不显示，
  /// 且不允许只留 `-xx%` 角标（设计稿规则）。
  static const TextStyle priceStruck = TextStyle(
      fontSize: 10.5, fontWeight: FontWeight.w400, color: ShopColors.text4,
      decoration: TextDecoration.lineThrough);

  // --- 倒计时（等宽）---

  /// 待支付详情页的大倒计时。
  static const TextStyle countdownHero = TextStyle(
      fontFamily: mono, fontSize: 30, fontWeight: FontWeight.w500, letterSpacing: 1,
      color: ShopColors.surface);

  /// 促销条 / 订单卡状态位里的小倒计时。
  static const TextStyle countdownInline =
      TextStyle(fontFamily: mono, fontSize: 10, fontWeight: FontWeight.w400);

  /// 订单号 / 运单号。
  static const TextStyle serialNo = TextStyle(
      fontFamily: mono, fontSize: 9.5, fontWeight: FontWeight.w400, color: ShopColors.text3);

  // --- 商品名 ---

  /// 详情页商品名。`text-wrap: pretty` 在 Flutter 无直接对应，用默认换行。
  static const TextStyle productNameDetail = TextStyle(
      fontSize: 14.5, fontWeight: FontWeight.w600, height: 1.4, color: ShopColors.text);

  /// 卡片内商品名。
  ///
  /// 🔴 配合 [kCardProductNameHeight] 固定两行高度并溢出隐藏 —— 目的是让同一排卡片的
  /// **价格行对齐**。改成自适应高度会让长短名称的卡片价格错位。
  static const TextStyle productNameCard =
      TextStyle(fontSize: 11.5, fontWeight: FontWeight.w500, height: 1.35, color: ShopColors.text);

  // --- 按钮 ---

  /// 主按钮（`Bayar` / `Beli Sekarang` / `Checkout`）。
  static const TextStyle buttonPrimary = TextStyle(fontSize: 13, fontWeight: FontWeight.w700);

  /// 次按钮 / 小按钮（`Beli Lagi` / `Cari mirip` / `Hapus`）。
  static const TextStyle buttonSecondary = TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700);

  // --- 正文与元信息 ---

  /// 卡内小标题。
  static const TextStyle cardTitle =
      TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700, color: ShopColors.text);

  /// 正文说明。
  static const TextStyle body = TextStyle(
      fontSize: 10.5, fontWeight: FontWeight.w400, height: 1.6, color: ShopColors.text2);

  /// 元信息 / 时间。
  static const TextStyle meta = TextStyle(
      fontSize: 10, fontWeight: FontWeight.w400, height: 1.5, color: ShopColors.text3);

  /// 徽标 / 角标。**9px 只允许出现在这里。**
  static const TextStyle badge = TextStyle(fontSize: 9, fontWeight: FontWeight.w700);
}

/// 设计 token —— 电商板块的圆角 / 间距。
///
/// 与全局 `AppRounded`（4/8/12/16/24）**只有 8 对得上**，故另起。设计稿的圆角是逐元素
/// 指定的终值，不是一套 scale，因此这里按用途命名而不是按大小命名。
class ShopShape {
  ShopShape._();

  /// 徽标、小标签、倒计时底。
  static const double radiusBadge = 3;

  /// 支付方式条（带左色条）、退款拆分段。
  static const double radiusPayRow = 5;

  /// 网格商品图、chips、退货原因选项。
  static const double radiusChip = 6;

  /// 输入框、行内商品图、紫说明块。
  static const double radiusField = 7;

  /// 主按钮、横滑卡商品图、图上圆形按钮。
  static const double radiusButton = 8;

  /// 圆点数字（16px 圆）。
  static const double radiusStepDot = 9;

  /// 开关轨道（大 11 / 小 10）。
  static const double radiusSwitchLarge = 11;
  static const double radiusSwitchSmall = 10;
}

/// 区块之间的**灰缝**厚度。
///
/// 🔴 这是本套设计密度的来源：白色内容块之间用 3px 实色缝（露出 [ShopColors.bg]）分隔，
/// **不用卡片圆角 + 外边距**。设计稿原文：「改成卡片间距会让每屏承载量减半」。
///
/// ⚠️ 全局 `AppSpacing` 没有 3（最小 xxs=2 / xs=4），故单列于此，勿就近取 2 或 4。
const double kShopGutter = 3;

/// 内容块的标准内边距。
const EdgeInsets kShopBlockPadding = EdgeInsets.symmetric(horizontal: 16, vertical: 12.5);

/// 强调块（Diary 触发卡、征询卡）的内边距 —— 比标准块略高。
const EdgeInsets kShopEmphasisPadding = EdgeInsets.symmetric(horizontal: 16, vertical: 14);

/// 底部操作条内边距。末值 18 含安全区视觉留白 ——
/// 🔴 实现时**改用 `MediaQuery.viewPadding.bottom`**，不要写死 18。
const EdgeInsets kShopBottomBarPadding = EdgeInsets.fromLTRB(14, 10, 14, 10);

/// 卡片内商品名的固定高度（两行 × 11.5px × 1.35 ≈ 31）。见 [ShopText.productNameCard]。
const double kCardProductNameHeight = 31;

/// 横滑列表：卡片间距 / 首末与屏边距。
const double kShopRailGap = 9;
const double kShopScreenEdge = 16;
