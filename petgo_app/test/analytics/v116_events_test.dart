import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/auth/domain/user_tag.dart';
import 'package:tailtopia/features/content/domain/content_tag.dart';
import 'package:tailtopia/features/content/domain/share_card_data.dart';
import 'package:tailtopia/features/me/domain/phone_soft_prompt.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/domain/push_permission_prompt.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_export.dart';
import 'package:tailtopia/shared/widgets/content_tag_chip.dart';
import 'package:tailtopia/shared/widgets/user_tag_row.dart';

import 'v112_events_test.dart' show eventNamesInSource;

/// Story 10.1 · L0/L1：V1.1.6 埋点清单（E-1~E-28）的收尾。
///
/// **为什么单开一个文件而不是塞进 v112**：那份钉的是 V1.1.2 的 T 系列，
/// 两版清单的事件集合各自独立（清单 §2 原话：「E 系列与 v112 的 T 系列各自独立、不连续分配」）。
/// 混在一起以后想回答「V1.1.6 的埋点齐了没」就得从一堆断言里挑。
///
/// 🔴 **本文件不重复钉前序 story 已经钉过的事件**（E-1/2/3 在 pinned_slot_test、
/// E-8/9/10 在 image_crop_test、E-19/20/21 在 push_* 那几个文件…）。
/// 重复断言的代价是：某个事件改了名要改两处，而漏改的那一处会**继续绿着**。
/// 这里只钉两样：① 本 story 新补的事件；② 一条**集合级**的存在性护栏。
void main() {
  late List<(String, Map<String, Object>?)> seen;

  setUp(() {
    seen = <(String, Map<String, Object>?)>[];
    Analytics.debugCaptureSink = (e, p) => seen.add((e, p));
    SharedPreferences.setMockInitialValues({});
  });
  tearDown(() {
    Analytics.debugCaptureSink = null;
    PushPermissionPrompt.phonePromptHook = null;
  });

  Map<String, Object>? propsOf(String event) =>
      seen.where((e) => e.$1 == event).map((e) => e.$2).firstOrNull;
  List<String> names() => seen.map((e) => e.$1).toList();

  // ==========================================================================
  group('AC1/AC2 集合级护栏：清单声明的事件在源码里确实存在', () {
    /// 🔴 **这一条守的是"文档说埋了、代码里却没有"** —— 也就是本 story 的对账
    /// （AC1）的机器化版本。人工对账只在做的那一天成立；这条测试让它以后一直成立。
    ///
    /// ⚠️ 名单里写的是**实现名**，不是清单原名：其中十条在实现期被按本项目命名规范
    /// 订正过（PRD §3.2 逐条记了理由与日期，清单 v116 漏了同步，已随本 story 补回）。
    /// 详见 story 的对账表。
    test('E-1~E-23 里所有 App 侧事件都能在 lib/ 提取到', () {
      const appSideEvents = <String>{
        // FR-68 顶置坑位（Story 4.2；原名 feed_* → social_*）
        'social_pinned_slot_viewed',
        'social_pinned_slot_tapped',
        'social_pinned_duplicate_viewed',
        // FR-70 手机号（E-4 本 story 补；E-6/E-7 原名 phone_saved/phone_save_failed → me_*）
        'phone_prompt_shown',
        'phone_prompt_responded',
        'me_phone_save_succeeded',
        'me_phone_save_error_shown',
        // FR-71 图片裁剪（Story 3.5；原名 _required/_confirmed/_abandoned）
        'publish_image_crop_shown',
        'publish_image_crop_completed',
        'publish_image_crop_exit_tapped',
        // FR-73 分享卡（E-11/E-12 本 story 补，E-13 本 story 订正时机与属性）
        'post_share_card_tapped',
        'post_share_card_generated',
        'post_share_card_sent',
        // FR-74/75 两类标签 tooltip（本 story 补）
        'user_badge_tooltip_opened',
        'content_badge_tooltip_opened',
        // FR-76 通知中心（E-17 本 story 补；原名 notification_* → app_*）
        'app_notification_center_viewed',
        'app_notification_item_tapped',
        // FR-85 推送权限（Story 8.1~8.3）
        'push_permission_prompt_shown',
        'push_permission_responded',
        'push_permission_state_reported',
        // FR-92 名片分享 App 侧起点（本 story 补）
        'pet_card_share_tapped',
        // FR-93 点赞（扩展既有事件）+ 清单外 E-28
        'post_like_tapped',
        'publish_page_image_source_selected',
      };
      final inSource = eventNamesInSource();
      final missing = appSideEvents.difference(inSource);
      expect(missing, isEmpty,
          reason: '这些事件清单里声明了、源码里找不到 —— '
              '要么没实现，要么改了名而没同步文档：$missing');
    });

    /// 反方向：E-14 与 E-24~E-26 是**服务端上报**，App 侧一行都不该有。
    /// 在客户端补一份等于同一个事件被报两次，绝对值直接翻倍。
    test('服务端上报的那几个事件，App 侧不得出现', () {
      const serverOnly = <String>{
        'post_share_link_opened',
        'pet_card_link_opened',
        'pet_card_cta_tapped',
        'pet_card_cta_outcome',
      };
      expect(eventNamesInSource().intersection(serverOnly), isEmpty,
          reason: '这几个只能由服务端报（H5 无登录态、无前端 SDK）');
    });
  });

  // ==========================================================================
  group('E-4/E-5 手机号软引导：曝光是分母，三个响应取值必须分开', () {
    DateTime wib(int y, int m, int d, [int h = 12]) =>
        DateTime.utc(y, m, d, h).subtract(const Duration(hours: 7));

    Future<void> run(Future<bool?> Function() sheet) async {
      await PhoneSoftPrompt.maybeShow(
        prefs: await AppPrefs.create(),
        registeredAt: wib(2026, 8, 1),
        hasPhone: false,
        now: wib(2026, 8, 5),
        showSheet: sheet,
      );
    }

    test('E-4 在展示时上报一次，且排在 E-5 之前', () async {
      await run(() async => true);
      expect(names(), ['phone_prompt_shown', 'phone_prompt_responded'],
          reason: 'E-4 是 E-5 的分母；反了或缺了都算不出"打断了多少人"');
    });

    /// 🔴 `trigger` 的旧词表（first_consult / profile_created）已被 X-20 判为死代码，
    /// X-21 把时机改成"注册第 3 天首次打开"。照旧词表报会产出恒为空的属性。
    test('E-4 的 trigger 是新时机 day3_open，不是已作废的旧两档', () async {
      await run(() async => true);
      expect(propsOf('phone_prompt_shown')?['trigger'], 'day3_open');
    });

    /// 🔴 清单 §3 明写「skipped 与 dismissed 要分开：前者是拒绝，后者可能只是没看懂」。
    /// 7-2 当时把两者都记成 skipped（抽屉只回 bool）。合并之后这条判读永久做不出来。
    test('点取消 → skipped；划走 → dismissed；两者不再混为一谈', () async {
      await run(() async => false);
      expect(propsOf('phone_prompt_responded')?['action'], 'skipped');

      seen.clear();
      SharedPreferences.setMockInitialValues({});
      await run(() async => null); // 划走 / 点遮罩
      expect(propsOf('phone_prompt_responded')?['action'], 'dismissed');
    });
  });

  // ==========================================================================
  group('E-15/E-16 两类标签 tooltip：position 是唯一有价值的维度', () {
    Widget host(Widget child) => MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          home: Scaffold(body: Center(child: child)),
        );

    const userTag = UserTag(
        code: 'TOP_OWNER', name: 'Pemilik Teladan', icon: '🏅', description: 'desc');
    const contentTag =
        ContentTag(code: 'EDITOR_PICK', name: 'Pilihan Editor', icon: '⭐', description: 'd');

    testWidgets('点用户标签 → user_badge_tooltip_opened(badge_id, position)',
        (tester) async {
      await tester.pumpWidget(host(SizedBox(
        width: 300,
        child: UserTagRow(
          name: 'Ana',
          nameStyle: const TextStyle(fontSize: 14),
          tags: const [userTag],
          position: 'comment',
        ),
      )));
      await tester.tap(find.byKey(const ValueKey('userTag_TOP_OWNER')));
      await tester.pumpAndSettle();

      expect(propsOf('user_badge_tooltip_opened'),
          {'badge_id': 'TOP_OWNER', 'position': 'comment'});
    });

    testWidgets('点装饰标签 → content_badge_tooltip_opened(badge_id, position)',
        (tester) async {
      await tester.pumpWidget(
          host(const ContentTagChip.inline(tag: contentTag, position: 'diary')));
      await tester.tap(find.byKey(const ValueKey('contentTag_EDITOR_PICK')));
      await tester.pumpAndSettle();

      expect(propsOf('content_badge_tooltip_opened'),
          {'badge_id': 'EDITOR_PICK', 'position': 'diary'});
    });

    /// 🔴 详情页的两种形态（首图角落 overlay / 正文下方 inline）都属于 `detail`。
    /// 拿 onImage 反推展示位会把同一处记成两个地方 —— 这条钉的就是那个混淆。
    testWidgets('详情页两种形态都报 detail，不因 overlay/inline 而分叉', (tester) async {
      await tester.pumpWidget(
          host(const ContentTagChip.overlay(tag: contentTag, position: 'detail')));
      await tester.tap(find.byKey(const ValueKey('contentTag_EDITOR_PICK')));
      await tester.pumpAndSettle();
      expect(propsOf('content_badge_tooltip_opened')?['position'], 'detail');
    });
  });

  // ==========================================================================
  group('E-13 channel 归一：两个平台回传的形状完全不同', () {
    test('按子串匹配，不按等值', () {
      expect(CardExport.normalizeChannel('com.whatsapp'), 'whatsapp');
      expect(CardExport.normalizeChannel('net.whatsapp.WhatsApp.ShareExtension'),
          'whatsapp');
      expect(CardExport.normalizeChannel('com.burbn.instagram.shareextension'),
          'instagram');
      expect(CardExport.normalizeChannel('com.apple.UIKit.activity.CopyToPasteboard'),
          'other');
    });

    /// 平台不回调时上游拿到空串 —— 归一为 other，而不是抛或返回 null。
    /// 清单 §3 已写明这个属性「覆盖不全、不可当分母」。
    test('空/null → other', () {
      expect(CardExport.normalizeChannel(null), 'other');
      expect(CardExport.normalizeChannel(''), 'other');
    });
  });

  // ==========================================================================
  group('E-14 open_method：二维码必须印带标记的那一份 URL', () {
    /// 🔴 不加标记，`open_method` 就是做不出来的 —— 码里印的和文字里给的是同一个 URL。
    /// 而它是下载二维码**唯一的验收依据**（那个码占了卡片页脚近一半版面）。
    test('qrUrl 比 shareUrl 多一个 src=qr', () {
      const d = ShareCardData(
        authorName: 'Ana',
        type: 'DAILY',
        shareUrl: 'https://s.tailtopia.id/c/TOKEN123',
      );
      expect(d.qrUrl, 'https://s.tailtopia.id/c/TOKEN123?src=qr');
    });

    test('已带查询串的 URL 用 & 续接，不拼出第二个 ?', () {
      const d = ShareCardData(
        authorName: 'Ana',
        type: 'DAILY',
        shareUrl: 'https://s.tailtopia.id/c/T?a=1',
      );
      expect(d.qrUrl, 'https://s.tailtopia.id/c/T?a=1&src=qr');
    });
  });

  // ==========================================================================
  group('E-17 通知中心曝光：同时是 FR-85 触发点 4 的曝光分母', () {
    /// [openId] 相当于「第几次打开」。⚠️ 同一个测试里连开两次**必须给不同的 key** ——
    /// 否则结构相同，Flutter 会**复用 State**、`initState` 不再跑，第二次压根不上报，
    /// 断言看到的是第一次的结果。（push_notification_center_banner_test 记过同一个坑。）
    Future<void> open(WidgetTester tester,
        {required bool granted,
        required bool asked,
        int unread = 0,
        int openId = 1}) async {
      SharedPreferences.setMockInitialValues(
          asked ? {'petgo.push_permission_asked': true} : {});
      final prefs = await AppPrefs.create();
      await tester.pumpWidget(ProviderScope(
        overrides: [
          notificationRepositoryProvider
              .overrideWithValue(_FakeNotifRepo(unread: unread)),
        ],
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('en'),
          home: NotificationCenterPage(
            key: ValueKey('open-$openId'),
            prefsForTest: prefs,
            isNotificationGrantedForTest: () async => granted,
            openSettingsForTest: () async => true,
          ),
        ),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('已授权 → push_permission=granted，并带上未读数', (tester) async {
      await open(tester, granted: true, asked: true, unread: 7);
      expect(propsOf('app_notification_center_viewed'),
          {'unread_count': 7, 'push_permission': 'granted'});
    });

    /// 🔴 iOS 上「从没问过」与「问过被拒」在系统 API 里都返回 denied，
    /// 只有本地那个「问过没有」的标记能分开两者。混在一起，"拒绝率"里
    /// 会混进一批压根没被问过的人，这个数就没法用了。
    testWidgets('未授权且问过 → denied；未授权且没问过 → not_asked', (tester) async {
      await open(tester, granted: false, asked: true);
      expect(propsOf('app_notification_center_viewed')?['push_permission'], 'denied');

      seen.clear();
      await open(tester, granted: false, asked: false, openId: 2);
      expect(
          propsOf('app_notification_center_viewed')?['push_permission'], 'not_asked');
    });

    /// 打开一次只报一次 —— 这是分母，重复上报会把触发点 4 的转化率算低。
    testWidgets('一次打开只报一条', (tester) async {
      await open(tester, granted: true, asked: true);
      expect(names().where((e) => e == 'app_notification_center_viewed'), hasLength(1));
    });
  });

  // ==========================================================================
  group('E-27 注册归因新增 pet_card：归因链跨了好几个页面', () {
    LoginResponse newUser() => const LoginResponse(
          accessToken: 'a',
          refreshToken: 'r',
          role: 'USER',
          isNewUser: true,
          onboardingCompleted: false,
        );

    /// 硬门弹层的 `entrySource` 默认就是兜底值 `other` —— 也就是"不知道从哪来的"。
    /// 从名片链接进来的人正是走这条路：访客只读页**没有注册入口**，
    /// 他是先看完别人的档案、再在别处撞上登录引导才注册的。
    Widget guideApp(LoginGuideController c) {
      final router = GoRouter(
        initialLocation: '/',
        routes: [
          GoRoute(
            path: '/',
            builder: (ctx, _) => Scaffold(
              body: Builder(
                builder: (inner) => TextButton(
                  onPressed: () => c.showHardDialog(inner),
                  child: const Text('trigger'),
                ),
              ),
            ),
          ),
          GoRoute(
              path: '/onboarding',
              builder: (_, _) => const Scaffold(body: Text('onboarding'))),
        ],
      );
      return ProviderScope(
        child: MaterialApp.router(
          routerConfig: router,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
        ),
      );
    }

    Future<void> signUp(WidgetTester tester) async {
      final c = LoginGuideController(() async => newUser());
      await tester.pumpWidget(guideApp(c));
      await tester.pumpAndSettle();
      await tester.tap(find.text('trigger'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('hardDialogGoogleCta')));
      await tester.pumpAndSettle();
    }

    tearDown(LoginGuideController.resetPetCardEntry);

    testWidgets('走过名片深链 → entry_source=pet_card', (tester) async {
      LoginGuideController.markPetCardEntry();
      await signUp(tester);
      expect(propsOf('signup_succeeded')?['entry_source'], 'pet_card');
    });

    testWidgets('没走过名片深链 → 仍是兜底值 other', (tester) async {
      LoginGuideController.resetPetCardEntry();
      await signUp(tester);
      expect(propsOf('signup_succeeded')?['entry_source'], 'other');
    });
  });
}

// ============================================================================
// E-17：通知中心曝光。单开一个 main 之外的分组不方便共享 harness，故放在文件末尾
// 用自己的 fake repo（与 push_notification_center_banner_test 同一套做法）。
// ============================================================================

class _FakeNotifRepo extends NotificationRepository {
  _FakeNotifRepo({required this.unread}) : super(dio: Dio());

  final int unread;

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      const NotificationPage(items: <NotificationItem>[], hasMore: false);

  @override
  Future<int> unreadCount() async => unread;
}
