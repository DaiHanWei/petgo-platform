import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/domain/published_post.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/mention/data/mention_candidate_repository.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/topic/data/topic_repository.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/keyboard_safe_area.dart';
import 'package:tailtopia/shared/widgets/keyboard_suggest_panel.dart';

import '../topic/fake_topic_repository.dart';

/// L0：发布页 @ 面板放进 4.4 的贴键盘承载位（V1.4.2 social-topic · Story 5.2 · AC2.1 / AC2.3 · D-19）。
///
/// 结构断言（不写 golden）：面板底边 = KeyboardInset 内容底边（不双倍上移）、≤ 220、@ 与 # 互斥、
/// 正文 scrollPadding.bottom = 面板高 + 20 且收起后恢复、行左右 20、旧的正文下方内联浮层已删。
void main() {
  late FakeTopicRepository topics;

  setUp(() => topics = FakeTopicRepository());

  Finder field() => find.byKey(const ValueKey('publishText'));
  Finder mentionPanel() => find.byKey(const ValueKey('mentionPicker'));
  Finder topicPanel() => find.byKey(const ValueKey('topicSuggestPanel'));
  TextField textField(WidgetTester tester) => tester.widget<TextField>(field());

  Future<void> pumpPage(WidgetTester tester, List<MentionCandidate> candidates,
      {double keyboard = 300}) async {
    tester.view.physicalSize = const Size(1200, 3200);
    tester.view.devicePixelRatio = 1.0;
    tester.view.viewInsets = FakeViewPadding(bottom: keyboard);
    addTearDown(tester.view.reset);
    final controller = PublishController(repository: _Repo(), uploadOne: (b) async => 'https://cdn/x.jpg');
    final container = ProviderContainer(overrides: [
      publishControllerProvider.overrideWithValue(controller),
      topicRepositoryProvider.overrideWithValue(topics),
      mentionCandidateRepositoryProvider.overrideWithValue(_Mentions(candidates)),
      authControllerProvider.overrideWith(_Auth.new),
      petListProvider.overrideWith((ref) async => const <PetListItem>[]),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: const MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        // 真机上发布页在 modal sheet 里、由自身 KeyboardInset 让位；这里关掉 Scaffold 的让位，只留 KeyboardInset 一层。
        home: Scaffold(
          resizeToAvoidBottomInset: false,
          body: PublishComposePage(preset: ContentType.daily),
        ),
      ),
    ));
    await tester.pumpAndSettle();
  }

  Future<void> type(WidgetTester tester, String text) async {
    await tester.enterText(field(), text);
    await tester.pumpAndSettle();
  }

  testWidgets('@ 面板贴 KeyboardInset 内容底边（不双倍上移），外壳 = KeyboardSuggestPanel，行左右 20', (tester) async {
    await pumpPage(tester, [_c(1, 'Aurel'), _c(2, 'Budi')]);
    await type(tester, 'halo @');
    expect(mentionPanel(), findsOneWidget);
    expect(tester.widget(mentionPanel()), isA<KeyboardSuggestPanel>());

    final Rect inset = tester.getRect(find.byType(KeyboardInset));
    final Rect panel = tester.getRect(mentionPanel());
    expect(panel.bottom, inset.bottom - 300, reason: '承载位在 KeyboardInset 内取 bottom: 0 = 键盘上沿');
    expect(panel.left, inset.left);
    expect(panel.right, inset.right);
    // 旧内联浮层已删：面板不在正文框正下方紧贴，而在内容区最底。
    expect(panel.top, greaterThan(tester.getRect(field()).bottom + 7));

    final pad = tester.widget<Padding>(find
        .descendant(of: find.byKey(const ValueKey('mentionCandidate_1')), matching: find.byType(Padding))
        .first);
    expect((pad.padding as EdgeInsets).left, 20);
    expect((pad.padding as EdgeInsets).right, 20);
  });

  testWidgets('候选多 → 面板最高 220，超出在面板内滚动', (tester) async {
    await pumpPage(tester, [for (int i = 1; i <= 12; i++) _c(i, 'User$i')]);
    await type(tester, '@');
    expect(tester.getSize(mentionPanel()).height, lessThanOrEqualTo(220));
    expect(find.descendant(of: mentionPanel(), matching: find.byType(ListView)), findsOneWidget);
  });

  testWidgets('scrollPadding.bottom = 面板实际高度 + 20；收起后恢复 all(20)', (tester) async {
    await pumpPage(tester, [_c(1, 'Aurel'), _c(2, 'Budi')]);
    expect(textField(tester).scrollPadding, const EdgeInsets.all(20));
    await type(tester, 'halo @');
    final double h = tester.getSize(mentionPanel()).height;
    expect(textField(tester).scrollPadding.bottom, h + 20);

    // 状态变化（列表 → 转圈 → 结果）后高度跟着重量。
    await type(tester, 'halo @bu');
    final double h2 = tester.getSize(mentionPanel()).height;
    expect(h2, isNot(h));
    expect(textField(tester).scrollPadding.bottom, h2 + 20);

    await type(tester, 'halo @bu ');
    expect(mentionPanel(), findsNothing);
    expect(textField(tester).scrollPadding, const EdgeInsets.all(20));
  });

  testWidgets('同一时刻只有一个面板：光标处是 @ 查询 → @ 面板；是 # 查询 → # 面板', (tester) async {
    await pumpPage(tester, [_c(1, 'Aurel')]);
    await type(tester, '#kucing @au');
    expect(mentionPanel(), findsOneWidget);
    expect(topicPanel(), findsNothing);

    await type(tester, '@au #kucing');
    expect(topicPanel(), findsOneWidget);
    expect(mentionPanel(), findsNothing);
  });
}

MentionCandidate _c(int id, String nickname) => MentionCandidate(userId: id, nickname: nickname);

class _Mentions implements MentionCandidateRepository {
  _Mentions(this.candidates);
  final List<MentionCandidate> candidates;

  @override
  Future<List<MentionCandidate>> getCandidates() async => candidates;

  @override
  Future<List<MentionCandidate>> search(String keyword, {CancelToken? cancelToken}) async => candidates
      .where((c) => c.nickname.toLowerCase().contains(keyword.toLowerCase()))
      .toList(growable: false);
}

class _Repo implements ContentRepository {
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
  }) async =>
      const PublishedPost(id: 9);
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
