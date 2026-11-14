import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/notify/data/notification_repository.dart';
import 'package:tailtopia/features/notify/domain/notification_item.dart';
import 'package:tailtopia/features/notify/presentation/notification_center_page.dart';
import 'package:tailtopia/features/social/presentation/widgets/follow_button.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.2 social-topic · Story 1.5 · AC6（UI 稿 A12）：通知中心 `PET_FOLLOWED` / `PET_DIARY_POSTED`。
class _Repo extends NotificationRepository {
  _Repo(this.items) : super(dio: Dio());
  final List<NotificationItem> items;
  final List<String> markReadTokens = [];

  @override
  Future<NotificationPage> list({String? cursor, int limit = 20}) async =>
      NotificationPage(items: items, hasMore: false);

  @override
  Future<int> unreadCount() async => 0;

  @override
  Future<void> markRead(String token) async => markReadTokens.add(token);

  @override
  Future<void> markAllRead() async {}
}

class _Probe extends ContentGoneProbe {
  _Probe({this.gone = false}) : super(Dio());
  final bool gone;
  final List<int> probed = [];

  @override
  Future<bool> isGone(int postId) async {
    probed.add(postId);
    return gone;
  }
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(id: 1, nickname: 'Me', onboardingCompleted: true),
      );
}

NotificationItem _petFollowed({int? actorUserId = 42, String token = 'pf1'}) => NotificationItem(
      type: 'PET_FOLLOWED',
      deepLinkType: 'PET_FOLLOWED',
      deepLinkToken: token,
      targetRef: '42',
      title: 'Rani started following Mochi',
      read: false,
      createdAt: DateTime.now().toUtc(),
      actorName: 'Rani',
      actorCount: 1,
      actorUserId: actorUserId,
      actorToken: 'tok42',
      petName: 'Mochi',
    );

NotificationItem _diary({String? actorName = 'Mochi', String title = 'Mochi posted a new Diary', String token = 'pd1'}) =>
    NotificationItem(
      type: 'PET_DIARY_POSTED',
      deepLinkType: 'PET_DIARY_POSTED',
      deepLinkToken: token,
      targetRef: '88',
      title: title,
      body: '',
      read: false,
      createdAt: DateTime.now().toUtc(),
      actorName: actorName,
      petName: actorName,
    );

class _Nav {
  String? pushed;
}

Future<_Nav> _pump(WidgetTester tester, _Repo repo, {_Probe? probe}) async {
  final nav = _Nav();
  GoRoute sink(String path) => GoRoute(
        path: path,
        builder: (_, s) {
          nav.pushed = s.uri.toString();
          return const Scaffold(body: Text('target'));
        },
      );
  final router = GoRouter(
    initialLocation: '/notifications',
    routes: [
      GoRoute(path: '/notifications', builder: (_, _) => const NotificationCenterPage()),
      sink('/users/:userId'),
      sink('/content/:id'),
    ],
  );
  addTearDown(router.dispose);
  final container = ProviderContainer(overrides: [
    notificationRepositoryProvider.overrideWithValue(repo),
    contentGoneProbeProvider.overrideWithValue(probe ?? _Probe()),
    authControllerProvider.overrideWith(_Auth.new),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      locale: const Locale('en'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return nav;
}

Finder _tile(String token) => find.byKey(ValueKey('notification_$token'));

List<String?> _bold(WidgetTester tester, String token) {
  final title = tester.widget<Text>(find.byKey(ValueKey('notificationTitle_$token')));
  final spans = (title.textSpan! as TextSpan).children!.cast<TextSpan>();
  return spans.where((s) => s.style?.fontWeight == FontWeight.w700).map((s) => s.text).toList();
}

String _plain(WidgetTester tester, String token) {
  final title = tester.widget<Text>(find.byKey(ValueKey('notificationTitle_$token')));
  return (title.textSpan! as TextSpan).toPlainText();
}

(Icon, Color?) _iconOf(WidgetTester tester, String token) {
  final icon = tester.widget<Icon>(find.descendant(of: _tile(token), matching: find.byType(Icon)).first);
  final block = tester.widget<Container>(
      find.ancestor(of: find.byWidget(icon), matching: find.byType(Container)).first);
  return (icon, (block.decoration! as BoxDecoration).color);
}

void main() {
  late AppLocalizations l10n;
  final events = <(String, Map<String, Object>?)>[];
  setUpAll(() async {
    l10n = await AppLocalizations.delegate.load(const Locale('en'));
  });
  setUp(() {
    events.clear();
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  test('两类型都是「后端文案条目」类型；petName 缺键 null', () {
    expect(NotificationItem.serverCopyTypes, containsAll(['PET_FOLLOWED', 'PET_DIARY_POSTED']));
    expect(_petFollowed().hasServerCopy, isTrue);
    expect(_diary(actorName: null).hasServerCopy, isFalse);
    expect(NotificationItem.fromJson({'type': 'PET_FOLLOWED', 'petName': 'Mochi'}).petName, 'Mochi');
    expect(NotificationItem.fromJson({'type': 'USER_FOLLOWED'}).petName, isNull);
  });

  testWidgets('PET_FOLLOWED：标题加粗昵称与宠物名；图标 person_add / violet100；无回关按钮、无正文行', (tester) async {
    await _pump(tester, _Repo([_petFollowed()]));
    expect(_plain(tester, 'pf1'), 'Rani started following Mochi');
    expect(_bold(tester, 'pf1'), ['Rani', 'Mochi']);
    final (icon, bg) = _iconOf(tester, 'pf1');
    expect(icon.icon, Icons.person_add_rounded);
    expect(icon.color, AppColors.mint);
    expect(bg, AppColors.violet100);
    expect(find.descendant(of: _tile('pf1'), matching: find.byType(FollowButton)), findsNothing);
    expect(find.byKey(const ValueKey('notificationBody_pf1')), findsNothing);
  });

  testWidgets('PET_FOLLOWED：点击 → openUserProfile(actorUserId)，报 user_profile_viewed；notif_type=pet_followed',
      (tester) async {
    final repo = _Repo([_petFollowed()]);
    final nav = await _pump(tester, repo);
    await tester.tap(_tile('pf1'));
    await tester.pumpAndSettle();
    expect(nav.pushed, startsWith('/users/42'));
    expect(repo.markReadTokens, ['pf1']);
    expect(events.firstWhere((e) => e.$1 == 'app_notification_item_tapped').$2!['notif_type'], 'pet_followed');
    expect(events.firstWhere((e) => e.$1 == 'user_profile_viewed').$2, {'target_user_id': 42, 'from': 'avatar'});
  });

  testWidgets('PET_FOLLOWED 无 actorUserId → 不跳转（落通知中心本身），已读照标', (tester) async {
    final repo = _Repo([_petFollowed(actorUserId: null)]);
    final nav = await _pump(tester, repo);
    await tester.tap(_tile('pf1'));
    await tester.pumpAndSettle();
    expect(nav.pushed, isNull);
    expect(find.byType(NotificationCenterPage), findsOneWidget);
    expect(repo.markReadTokens, ['pf1']);
  });

  testWidgets('PET_DIARY_POSTED：宠物名只加粗一次；图标 pets / violet100（不用 cream2）', (tester) async {
    await _pump(tester, _Repo([_diary()]));
    expect(_bold(tester, 'pd1'), ['Mochi']);
    final (icon, bg) = _iconOf(tester, 'pd1');
    expect(icon.icon, Icons.pets_rounded);
    expect(icon.color, AppColors.mint);
    expect(bg, AppColors.violet100);
    expect(bg, isNot(AppColors.cream2));
    expect(find.byKey(const ValueKey('notificationBody_pd1')), findsNothing);
  });

  testWidgets('PET_DIARY_POSTED：点击先探帖子 → 进 /content/{id}；notif_type=pet_diary_posted', (tester) async {
    final probe = _Probe();
    final nav = await _pump(tester, _Repo([_diary()]), probe: probe);
    await tester.tap(_tile('pd1'));
    await tester.pumpAndSettle();
    expect(probe.probed, [88]);
    expect(nav.pushed, '/content/88');
    expect(events.firstWhere((e) => e.$1 == 'app_notification_item_tapped').$2!['notif_type'], 'pet_diary_posted');
  });

  testWidgets('PET_DIARY_POSTED：帖子已删 → toast、不跳转、已读照标', (tester) async {
    final repo = _Repo([_diary()]);
    final nav = await _pump(tester, repo, probe: _Probe(gone: true));
    await tester.tap(_tile('pd1'));
    await tester.pump();
    expect(find.text(l10n.detailGoneTitle), findsOneWidget);
    await tester.pumpAndSettle();
    expect(nav.pushed, isNull);
    expect(repo.markReadTokens, ['pd1']);
    await tester.pump(const Duration(seconds: 5)); // toast 计时器走完
    await tester.pumpAndSettle();
  });

  testWidgets('PET_DIARY_POSTED 宠物已删（无 actorName）→ 读后端落库 title，不留正文行', (tester) async {
    await _pump(tester, _Repo([_diary(actorName: null, title: 'A pet you follow posted a new Diary')]));
    expect(find.text('A pet you follow posted a new Diary'), findsOneWidget);
    expect(find.text(l10n.notifyBodySystem), findsNothing);
  });

  testWidgets('1.5 复审：昵称包含宠物名（Mochi Lover / Mochi）→ 句末宠物名也加粗', (tester) async {
    await _pump(tester, _Repo([
      NotificationItem(
        type: 'PET_FOLLOWED',
        deepLinkType: 'PET_FOLLOWED',
        deepLinkToken: 'pf3',
        targetRef: '42',
        title: 'Mochi Lover started following Mochi',
        read: true,
        createdAt: DateTime.now().toUtc(),
        actorName: 'Mochi Lover',
        actorUserId: 42,
        petName: 'Mochi',
      ),
    ]));
    expect(_bold(tester, 'pf3'), ['Mochi Lover', 'Mochi']);
    expect(_plain(tester, 'pf3'), 'Mochi Lover started following Mochi');
  });

  testWidgets('标题里找不到 petName（后端语序变化）→ 只加粗找得到的片段，不崩', (tester) async {
    await _pump(tester, _Repo([
      NotificationItem(
        type: 'PET_FOLLOWED',
        deepLinkType: 'PET_FOLLOWED',
        deepLinkToken: 'pf2',
        targetRef: '42',
        title: 'Rani started following your pet',
        read: true,
        createdAt: DateTime.now().toUtc(),
        actorName: 'Rani',
        actorUserId: 42,
        petName: 'Mochi',
      ),
    ]));
    expect(_bold(tester, 'pf2'), ['Rani']);
    expect(_plain(tester, 'pf2'), 'Rani started following your pet');
  });

  // ===== V1.5.0 onboarding-recall Story 7.2（UI A3 / B2 / C7）=====

  NotificationItem stored(String type, String token,
          {String? title, String? body, String? targetRef, String? actorName}) =>
      NotificationItem(
        type: type,
        deepLinkType: type,
        deepLinkToken: token,
        targetRef: targetRef,
        title: title,
        body: body,
        read: false,
        createdAt: DateTime.now().toUtc(),
        actorName: actorName,
      );

  test('7.2 AC2：storedCopyTypes 五类；title 为空不算存储文案条目', () {
    expect(NotificationItem.storedCopyTypes, {
      'USER_POST_PUBLISHED', 'COMPANION_ANNIVERSARY', 'LIFECYCLE_D1', 'LIFECYCLE_D3', 'LIFECYCLE_D7',
    });
    expect(stored('LIFECYCLE_D1', 'x', title: 'Hi').hasStoredCopy, isTrue);
    expect(stored('LIFECYCLE_D1', 'x', title: ' ').hasStoredCopy, isFalse);
    expect(stored('COMPANION_ANNIVERSARY', 'x').hasStoredCopy, isFalse);
    expect(stored('PET_BIRTHDAY', 'x', title: 'Hi').hasStoredCopy, isFalse);
    // 改版前落库的印尼语兜底串视同未存文案
    expect(stored('COMPANION_ANNIVERSARY', 'x', title: '🎉 Hari jadi kebersamaan').hasStoredCopy, isFalse);
    expect(stored('LIFECYCLE_D7', 'x', title: 'Rangkuman minggu ini').hasStoredCopy, isFalse);
  });

  testWidgets('7.2 A3：关注更新 / 追更新 Diary 对照——读后端整句、爪印紫 / violet100、无正文行', (tester) async {
    await _pump(tester, _Repo([
      stored('USER_POST_PUBLISHED', 'up1',
          title: 'Rani just updated Mochi\'s Diary, take a peek 👀', body: '', targetRef: '77'),
      stored('USER_POST_PUBLISHED', 'up2', title: 'Budi just posted something new, take a peek 👀', targetRef: '78'),
      _diary(),
    ]));
    expect(find.text("Rani just updated Mochi's Diary, take a peek 👀"), findsOneWidget);
    expect(find.text('Budi just posted something new, take a peek 👀'), findsOneWidget);
    for (final t in ['up1', 'up2', 'pd1']) {
      final (icon, bg) = _iconOf(tester, t);
      expect(icon.icon, Icons.pets_rounded, reason: t);
      expect(bg, AppColors.violet100, reason: t);
    }
    expect(find.byKey(const ValueKey('notificationStoredBody_up1')), findsNothing);
    expect(find.text(l10n.notifyTypeSystem), findsNothing);
  });

  testWidgets('7.2 AC1：关注更新点击先探帖子 → /content/{id}；notif_type=user_post_published', (tester) async {
    final probe = _Probe();
    final nav = await _pump(tester, _Repo([stored('USER_POST_PUBLISHED', 'up1', title: 'Budi posted', targetRef: '77')]),
        probe: probe);
    await tester.tap(_tile('up1'));
    await tester.pumpAndSettle();
    expect(probe.probed, [77]);
    expect(nav.pushed, '/content/77');
    expect(events.firstWhere((e) => e.$1 == 'app_notification_item_tapped').$2!['notif_type'], 'user_post_published');
  });

  testWidgets('7.2 AC1：关注更新帖子已删 → toast、留在通知中心', (tester) async {
    final repo = _Repo([stored('USER_POST_PUBLISHED', 'up1', title: 'Budi posted', targetRef: '77')]);
    final nav = await _pump(tester, repo, probe: _Probe(gone: true));
    await tester.tap(_tile('up1'));
    await tester.pump();
    expect(find.text(l10n.detailGoneTitle), findsOneWidget);
    await tester.pumpAndSettle();
    expect(nav.pushed, isNull);
    expect(repo.markReadTokens, ['up1']);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
  });

  testWidgets('7.2 B2：新纪念日读存储文案、正文 2 行省略；旧纪念日空 title 回落本地固定文案', (tester) async {
    const longBody = 'Cheers to 30 days together! Snap Mochi\'s look today, one photo is all it takes 📸 '
        'Cheers to 30 days together! Snap Mochi\'s look today, one photo is all it takes 📸';
    await _pump(tester, _Repo([
      stored('COMPANION_ANNIVERSARY', 'an1', title: '🎉 Day 30 with Mochi!', body: longBody, targetRef: '5'),
      stored('COMPANION_ANNIVERSARY', 'an0', title: '', body: '', targetRef: '5'),
    ]));
    expect(find.text('🎉 Day 30 with Mochi!'), findsOneWidget);
    final body = tester.widget<Text>(find.byKey(const ValueKey('notificationStoredBody_an1')));
    expect(body.maxLines, 2);
    expect(body.overflow, TextOverflow.ellipsis);
    expect(find.text(l10n.notifyTypeCompanionAnniversary), findsOneWidget);
    expect(find.text(l10n.notifyBodyCompanionAnniversary), findsOneWidget);
  });

  testWidgets('7.2 C7：D1 / D3 / D7 读存储文案；D7 毕业帽 / goldTint；D1 / D3 图标不变', (tester) async {
    await _pump(tester, _Repo([
      stored('LIFECYCLE_D1', 'l1', title: 'The Persia squad is hanging out 🐾',
          body: 'Introduce Mochi to fellow Persia pals, maybe they\'ll be besties 💜', targetRef: 'DISCOVER'),
      stored('LIFECYCLE_D3', 'l3', title: 'Mochi still needs a bestie 🥺', body: 'Let\'s find Mochi a friend', targetRef: 'DISCOVER'),
      stored('LIFECYCLE_D7', 'l7', title: 'Yay, Lulus Pemula! 🎓', body: 'All Beginner Tasks done! Tap to celebrate 🎉',
          targetRef: 'GRADUATED'),
    ]));
    expect(find.text('The Persia squad is hanging out 🐾'), findsOneWidget);
    expect(find.text('Mochi still needs a bestie 🥺'), findsOneWidget);
    expect(find.text('Yay, Lulus Pemula! 🎓'), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const ValueKey('notificationStoredBody_l7'))).maxLines, 2);
    final (d7, d7bg) = _iconOf(tester, 'l7');
    expect(d7.icon, Icons.school_rounded);
    expect(d7bg, AppColors.goldTint);
    expect(_iconOf(tester, 'l1').$1.icon, Icons.auto_stories_rounded);
    expect(_iconOf(tester, 'l3').$1.icon, Icons.explore_rounded);
  });
}
