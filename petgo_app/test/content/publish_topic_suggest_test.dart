import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/domain/published_post.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/content/presentation/publish_result_page.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/topic/data/topic_repository.dart';
import 'package:tailtopia/features/topic/domain/topic.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import '../topic/fake_topic_repository.dart';

/// L0：发布页 # 联想、第 6 个话题提示、`topic_inserted`（V1.4.2 social-topic · Story 4.4 · AC4 / AC5 / AC6）。
///
/// ⚠️ 拆在独立文件而不是追加进 `publish_compose_test.dart`（那份已 800+ 行），harness 同口径。
void main() {
  late List<(String, Map<String, Object>?)> events;
  late FakeTopicRepository topics;

  setUp(() {
    events = [];
    Analytics.debugCaptureSink = (e, p) => events.add((e, p));
    topics = FakeTopicRepository();
  });
  tearDown(() => Analytics.debugCaptureSink = null);

  Finder field() => find.byKey(const ValueKey('publishText'));
  Finder panel() => find.byKey(const ValueKey('topicSuggestPanel'));
  TextField textField(WidgetTester tester) => tester.widget<TextField>(field());

  Future<(PublishController, GoRouter)> pumpPage(WidgetTester tester, {_Repo? repo}) async {
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final controller = PublishController(repository: repo ?? _Repo(), uploadOne: (b) async => 'https://cdn/x.jpg');
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(controller),
      topicRepositoryProvider.overrideWithValue(topics),
      authControllerProvider.overrideWith(_Auth.new),
      petListProvider.overrideWith((ref) async => const <PetListItem>[]),
    ]);
    addTearDown(container.dispose);
    final router = GoRouter(initialLocation: '/home', routes: [
      GoRoute(path: '/home', builder: (c, s) => const Scaffold(body: Text('home'))),
      GoRoute(
          path: '/compose',
          builder: (c, s) => const Scaffold(body: PublishComposePage(preset: ContentType.daily))),
      GoRoute(path: '/publish/done', builder: (c, s) => PublishDonePage(args: s.extra as PublishResultArgs)),
      GoRoute(
          path: '/publish/rejected', builder: (c, s) => PublishRejectedPage(args: s.extra as PublishResultArgs)),
    ]);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    ));
    await tester.pumpAndSettle();
    router.push('/compose');
    await tester.pumpAndSettle();
    return (controller, router);
  }

  Future<void> type(WidgetTester tester, String text) async {
    await tester.enterText(field(), text);
    await tester.pump();
  }

  Future<AppLocalizations> en() => AppLocalizations.delegate.load(const Locale('en'));

  testWidgets('输入 # → 面板出现并立即请求（不带 q）', (tester) async {
    topics.suggestDefault = topicsList(2, official: {1});
    await pumpPage(tester);
    await type(tester, 'halo #');
    await tester.pump();
    expect(topics.suggestCalls, [null]);
    expect(panel(), findsOneWidget);
    expect(find.text('#Topik1'), findsOneWidget);
    expect(find.text('#Topik2'), findsOneWidget);
  });

  testWidgets('输入 #ku → 300ms 内不请求，之后带 q=ku 请求', (tester) async {
    await pumpPage(tester);
    await type(tester, '#ku');
    await tester.pump(const Duration(milliseconds: 250));
    expect(topics.suggestCalls, isEmpty);
    // 等待期间面板显示加载转圈（无文案）。
    expect(find.byKey(const ValueKey('topicSuggestLoading')), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 60));
    expect(topics.suggestCalls, ['ku']);
  });

  testWidgets('快速 #k → #ku：只采用后一次结果（旧回包被丢弃）', (tester) async {
    final gate = Completer<void>();
    topics.suggestGates['k'] = gate;
    topics.suggestions['k'] = [const TopicSummary(name: 'kopi', displayName: 'Kopi')];
    topics.suggestions['ku'] = [const TopicSummary(name: 'kucing', displayName: 'Kucing')];
    await pumpPage(tester);
    await type(tester, '#k');
    await tester.pump(const Duration(milliseconds: 310)); // 发出 k（挂起）
    await type(tester, '#ku');
    await tester.pump(const Duration(milliseconds: 310)); // 发出 ku
    gate.complete();
    await tester.pump();
    expect(topics.suggestCalls, ['k', 'ku']);
    expect(find.text('#Kucing'), findsOneWidget);
    expect(find.text('#Kopi'), findsNothing);
  });

  testWidgets('无结果且关键词合法 → 只剩「#关键词 / New topic」一行', (tester) async {
    await pumpPage(tester);
    await type(tester, '#kucingbaru');
    await tester.pump(const Duration(milliseconds: 310));
    final l10n = await en();
    expect(find.byKey(const ValueKey('topicSuggestNew')), findsOneWidget);
    expect(find.descendant(of: panel(), matching: find.text('#kucingbaru')), findsOneWidget);
    expect(find.text(l10n.topicNewLabel), findsOneWidget);
    expect(find.descendant(of: panel(), matching: find.byIcon(Icons.add)), findsOneWidget);
  });

  testWidgets('关键词不合法（1 个字）且无结果 → 面板不出现', (tester) async {
    await pumpPage(tester);
    await type(tester, '#a');
    await tester.pump(const Duration(milliseconds: 310));
    expect(topics.suggestCalls, ['a']);
    expect(panel(), findsNothing);
  });

  testWidgets('失败 → 网络错误 + Coba lagi，点了用当前关键词重查', (tester) async {
    topics.suggestError = Exception('boom');
    await pumpPage(tester);
    await type(tester, '#ku');
    await tester.pump(const Duration(milliseconds: 310));
    final l10n = await en();
    expect(find.byKey(const ValueKey('topicSuggestError')), findsOneWidget);
    expect(find.text(l10n.detailNetworkError), findsOneWidget);
    topics.suggestError = null;
    topics.suggestions['ku'] = [const TopicSummary(name: 'kucing', displayName: 'Kucing')];
    await tester.tap(find.byKey(const ValueKey('topicSuggestRetry')));
    await tester.pump();
    expect(topics.suggestCalls, ['ku', 'ku']);
    expect(find.text('#Kucing'), findsOneWidget);
  });

  testWidgets('点候选 → 正文换成 #displayName + 空格、光标在尾、面板收起', (tester) async {
    topics.suggestions['ku'] = [const TopicSummary(name: 'kucingsehat', displayName: 'KucingSehat', official: true)];
    final (controller, _) = await pumpPage(tester);
    await type(tester, 'halo #ku');
    await tester.pump(const Duration(milliseconds: 310));
    await tester.tap(find.byKey(const ValueKey('topicSuggestion_kucingsehat')));
    await tester.pump();
    final tc = textField(tester).controller!;
    expect(tc.text, 'halo #KucingSehat ');
    expect(tc.selection.baseOffset, tc.text.length);
    expect(controller.text, 'halo #KucingSehat ');
    expect(panel(), findsNothing);
  });

  testWidgets('打空格 → 面板自然收起', (tester) async {
    topics.suggestDefault = topicsList(1);
    await pumpPage(tester);
    await type(tester, '#');
    await tester.pump();
    expect(panel(), findsOneWidget);
    await type(tester, '#kucing ');
    expect(panel(), findsNothing);
  });

  testWidgets('已有 5 个话题再输 # → 无面板 + 顶部 toast，同一个 # 只提示一次', (tester) async {
    await pumpPage(tester);
    final l10n = await en();
    const five = '#aa #bb #cc #dd #ee ';
    await type(tester, '$five#');
    expect(panel(), findsNothing);
    expect(topics.suggestCalls, isEmpty);
    final toast = find.text(l10n.topicLimitReached);
    expect(toast, findsOneWidget);
    // top: true → 屏幕顶部（安全区 + 16），不是底部。
    expect(tester.getTopLeft(toast).dy, lessThan(200));
    await tester.pump(const Duration(seconds: 3)); // toast 自然消失
    await tester.pump(const Duration(milliseconds: 300));
    expect(toast, findsNothing);
    // 同一个 # 继续打字 → 不再提示。
    await type(tester, '$five#f');
    expect(toast, findsNothing);
    expect(panel(), findsNothing);
    // 删掉 # 再输 → 再提示一次。
    await type(tester, five);
    await type(tester, '$five#');
    expect(toast, findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
    await tester.pump(const Duration(milliseconds: 300));
  });

  testWidgets('面板可见时 scrollPadding.bottom = 面板高 + 20；收起后恢复 20', (tester) async {
    topics.suggestDefault = topicsList(2);
    await pumpPage(tester);
    expect(textField(tester).scrollPadding, const EdgeInsets.all(20));
    await type(tester, '#');
    await tester.pump();
    await tester.pump();
    final double h = tester.getSize(panel()).height;
    expect(textField(tester).scrollPadding.bottom, h + 20);
    await type(tester, '# ');
    await tester.pump();
    expect(textField(tester).scrollPadding, const EdgeInsets.all(20));
  });

  group('topic_inserted（发布成功后按服务端 topics 上报）', () {
    Future<void> submit(WidgetTester tester) async {
      events.clear();
      await tester.tap(find.byKey(const ValueKey('publishSubmit')));
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      await tester.pump(const Duration(seconds: 4));
      await tester.pumpAndSettle();
    }

    Iterable<Map<String, Object>?> inserted() =>
        events.where((e) => e.$1 == 'topic_inserted').map((e) => e.$2);

    testWidgets('suggestion / typed 区分，is_new / is_official 透传', (tester) async {
      topics.suggestions['ku'] = [const TopicSummary(name: 'kucingsehat', displayName: 'KucingSehat', official: true)];
      final repo = _Repo(topics: const [
        PublishedTopic(name: 'kucingsehat', official: true),
        PublishedTopic(name: 'oyen', created: true),
      ]);
      await pumpPage(tester, repo: repo);
      await type(tester, 'halo #ku');
      await tester.pump(const Duration(milliseconds: 310));
      await tester.tap(find.byKey(const ValueKey('topicSuggestion_kucingsehat')));
      await tester.pump();
      await submit(tester);
      expect(inserted().toList(), [
        {'topic_id': 'kucingsehat', 'is_official': true, 'is_new': false, 'method': 'suggestion'},
        {'topic_id': 'oyen', 'is_official': false, 'is_new': true, 'method': 'typed'},
      ]);
    });

    testWidgets('点选后删掉、再手打同名 → typed（联想记录随正文收缩）', (tester) async {
      topics.suggestions['ku'] = [const TopicSummary(name: 'kucingsehat', displayName: 'KucingSehat')];
      await pumpPage(tester, repo: _Repo(topics: const [PublishedTopic(name: 'kucingsehat')]));
      await type(tester, 'halo #ku');
      await tester.pump(const Duration(milliseconds: 310));
      await tester.tap(find.byKey(const ValueKey('topicSuggestion_kucingsehat')));
      await tester.pump();
      await type(tester, 'halo '); // 删掉话题
      await type(tester, 'halo #kucingsehat lucu'); // 手打同名
      await tester.pump(const Duration(milliseconds: 310));
      await submit(tester);
      expect(inserted().single!['method'], 'typed');
    });

    testWidgets('点选后正文里已删掉（服务端仍返回）→ 不算 suggestion（交集）', (tester) async {
      topics.suggestions['ku'] = [const TopicSummary(name: 'kucingsehat', displayName: 'KucingSehat')];
      await pumpPage(tester, repo: _Repo(topics: const [PublishedTopic(name: 'kucingsehat')]));
      await type(tester, 'halo #ku');
      await tester.pump(const Duration(milliseconds: 310));
      await tester.tap(find.byKey(const ValueKey('topicSuggestion_kucingsehat')));
      await tester.pump();
      // 程序化改值不经 onChanged（模拟记录没来得及收缩的路径），正文里已无该话题。
      final tc = tester.widget<TextField>(field()).controller!;
      tc.value = const TextEditingValue(text: 'halo lucu', selection: TextSelection.collapsed(offset: 9));
      await tester.pump();
      await submit(tester);
      expect(inserted().single!['method'], 'typed');
    });

    testWidgets('服务端没返回 topics（私密 / 无话题）→ 不报', (tester) async {
      await pumpPage(tester);
      await type(tester, '#oyen lucu');
      await submit(tester);
      expect(find.byType(PublishDonePage), findsOneWidget);
      expect(inserted(), isEmpty);
    });

    testWidgets('发布被拦截 → 不报', (tester) async {
      await pumpPage(tester, repo: _Repo(failSlug: 'content-text-blocked'));
      await type(tester, '#oyen lucu');
      await submit(tester);
      expect(find.byType(PublishRejectedPage), findsOneWidget);
      expect(inserted(), isEmpty);
    });
  });
}

List<TopicSummary> topicsList(int n, {Set<int> official = const {}}) => topics(n, official: official);

class _Repo implements ContentRepository {
  _Repo({this.topics = const [], this.failSlug});
  final List<PublishedTopic> topics;
  final String? failSlug;

  @override
  Future<PublishedPost> publish({
    required ContentType type,
    int? petId,
    List<int> petIds = const [],
    String? text,
    List<String> imageUrls = const [],
    List<ImageSize?> imageSizes = const [],
    List<double?> imageCropOffsets = const [],
    DateTime? eventDate,
    required String idempotencyKey,
    bool syncToMoment = true,
    List<int> mentionedUserIds = const [],
    String? placeCheckinToken,
  }) async {
    if (failSlug != null) throw _blocked(failSlug!);
    return PublishedPost(id: 9, topics: topics);
  }
}

DioException _blocked(String slug) {
  final ro = RequestOptions(path: '/api/v1/content-posts');
  return DioException(
    requestOptions: ro,
    type: DioExceptionType.badResponse,
    response: Response(requestOptions: ro, statusCode: 422, data: {'type': 'https://petgo/errors/$slug', 'status': 422}),
  );
}

class _Auth extends AuthController {
  @override
  AuthState build() => const AuthState(
        status: AuthStatus.authenticated,
        role: 'USER',
        profile: UserProfile(nickname: 'Aurel', hasPetProfile: false),
      );

  @override
  Future<void> ensureRestored() => Future<void>.value();
}
