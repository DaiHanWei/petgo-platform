import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/comment_composer.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/features/mention/data/mention_candidate_repository.dart';
import 'package:tailtopia/features/mention/domain/mention_draft.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// L0：@ 的**两处触发**与提交形态
/// （V1.3.0 batch-b1 Story 3.2 · AC1「两处触发」/ AC4 / AC5）。
///
/// <h3>🔴 为什么非要两处都测</h3>
/// AC1 的原文是「内容正文输入框与评论/回复输入框，输入「@」**都**弹出」。
/// 现状这两个输入框是两套代码（发布页的 textarea / 详情页底部的 pill 输入条），
/// 只接一处而另一处忘了接，是这条 story 最可能的漏法 —— 而它在 L0 完全可查。
class _FakeMentions implements MentionCandidateRepository {
  _FakeMentions(this.candidates);

  final List<MentionCandidate> candidates;

  @override
  Future<List<MentionCandidate>> getCandidates() async => candidates;
}

class _RecordingDetailRepo implements DetailRepository {
  List<int>? lastCommentMentions;
  List<int>? lastReplyMentions;

  // 批次 A Story 2.4 的评论点赞通道（合并后接口多出来的两个方法）；本类不验它。
  @override
  Future<void> likeComment(int commentId) async {}
  @override
  Future<void> unlikeComment(int commentId) async {}

  @override
  Future<Comment> postComment(int postId, String body,
      {List<int> mentionedUserIds = const []}) async {
    lastCommentMentions = mentionedUserIds;
    return _comment();
  }

  @override
  Future<Comment> postReply(int parentId, String body,
      {List<int> mentionedUserIds = const []}) async {
    lastReplyMentions = mentionedUserIds;
    return _comment();
  }

  static Comment _comment() => Comment(
        id: 999,
        authorId: 1,
        authorDeleted: false,
        authorNickname: 'U1',
        body: 'b',
        createdAt: DateTime.utc(2026, 9, 15),
        replyCount: 0,
        replies: const [],
      );

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);

  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);

  @override
  Future<ContentDetail> getDetail(int id) => throw UnimplementedError();

  @override
  Future<void> deleteComment(int commentId) async {}

  @override
  Future<void> deleteContent(int postId) async {}

  @override
  Future<void> submitReport(int postId, String reasonType) async {}

  @override
  Future<String> getShareUrl(int postId) => throw UnimplementedError();
}

class _RecordingContentRepo implements ContentRepository {
  List<int>? lastMentions;
  String? lastText;

  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    String? text,
    List<String> imageUrls = const [],
    List<ImageSize?> imageSizes = const [],
    DateTime? eventDate,
    required String idempotencyKey,
    bool syncToMoment = true,
    List<int> mentionedUserIds = const [],
  }) async {
    lastMentions = mentionedUserIds;
    lastText = text;
    return 1;
  }
}

class _FakeProfileRepo implements ProfileRepository {
  @override
  Future<void> deleteMyProfile() async {}

  @override
  Future<PetProfile> create({
    required String petType,
    required String name,
    required DateTime birthday,
    String? avatarUrl,
    String? breed,
    String? intro,
    double? weightKg,
    String? neuterStatus,
    String? sex,
    String? idempotencyKey,
  }) async =>
      PetProfile(id: 1, name: name, cardToken: 'T', petType: petType, birthday: birthday);

  @override
  Future<PetProfile?> getMyProfile() async => null;

  @override
  Future<PetProfile> update({
    String? name,
    String? avatarUrl,
    String? breed,
    DateTime? birthday,
    String? sex,
    String? intro,
    double? weightKg,
    String? neuterStatus,
  }) async =>
      PetProfile(id: 1, name: name ?? 'x', cardToken: 'T');
}

LoginResponse _user(int id) => LoginResponse(
      accessToken: 'a',
      refreshToken: 'r',
      role: 'USER',
      isNewUser: false,
      onboardingCompleted: true,
      profile: UserProfile(id: id, onboardingCompleted: true),
    );

MentionCandidate _c(int id, String nickname) =>
    MentionCandidate(userId: id, nickname: nickname);

void main() {
  // ===== 触发点①：评论 / 回复输入框 =====

  group('AC1 触发点① 评论输入框', () {
    Future<(ProviderContainer, _RecordingDetailRepo)> pumpComposer(
      WidgetTester tester, {
      List<MentionCandidate> candidates = const [],
    }) async {
      final detail = _RecordingDetailRepo();
      final container = ProviderContainer(overrides: [
        detailRepositoryProvider.overrideWithValue(detail),
        mentionCandidateRepositoryProvider.overrideWithValue(_FakeMentions(candidates)),
      ]);
      addTearDown(container.dispose);
      container.read(authControllerProvider.notifier).applyLogin(_user(1));
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(body: CommentComposer(postId: 5)),
        ),
      ));
      await tester.pumpAndSettle();
      return (container, detail);
    }

    testWidgets('输入「@」→ 弹出选择器浮层', (tester) async {
      await pumpComposer(tester, candidates: [_c(42, 'Aurel')]);
      expect(find.byKey(const ValueKey('mentionPicker')), findsNothing);
      await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'hai @');
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('mentionPicker')), findsOneWidget);
    });

    testWidgets('正文里的邮箱不会误弹浮层', (tester) async {
      await pumpComposer(tester, candidates: [_c(42, 'Aurel')]);
      await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'aku@mail');
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('mentionPicker')), findsNothing);
    });

    testWidgets('🔴 AC4：选中后正文插 @昵称、提交发的是 userId', (tester) async {
      final (_, detail) = await pumpComposer(tester, candidates: [_c(42, 'Aurel')]);
      await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'hai @');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('mentionCandidate_42')));
      await tester.pumpAndSettle();

      // 正文里是昵称（给人读）……
      expect(find.text('hai @Aurel '), findsOneWidget);
      // ……选完浮层收起。
      expect(find.byKey(const ValueKey('mentionPicker')), findsNothing);

      await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
      await tester.pumpAndSettle();
      // ……提交出去的是 id（存昵称则对方改名后历史 @ 全部失效）。
      expect(detail.lastCommentMentions, [42]);
    });

    testWidgets('AC4：把 @昵称 删掉后再发 → 不提交那个 id', (tester) async {
      final (_, detail) = await pumpComposer(tester, candidates: [_c(42, 'Aurel')]);
      await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), '@');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('mentionCandidate_42')));
      await tester.pumpAndSettle();
      // 用户整段重写（浮层里选完又改主意）。
      await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), '算了不 at 了');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
      await tester.pumpAndSettle();
      expect(detail.lastCommentMentions, isEmpty);
    });

    testWidgets('没 @ 任何人 → 提交的名单是空的（老行为不变）', (tester) async {
      final (_, detail) = await pumpComposer(tester);
      await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'biasa aja');
      // 批次 A Story 2.3：发送钮只在有字时出现（底栏形态切换）—— 先让它渲染出来。
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
      await tester.pumpAndSettle();
      expect(detail.lastCommentMentions, isEmpty);
    });

    testWidgets('Story 3.5 AC4：插入成功 → mention_inserted(context=comment)', (tester) async {
      final captured = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);

      await pumpComposer(tester, candidates: [_c(42, 'Aurel')]);
      await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'hai @');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('mentionCandidate_42')));
      await tester.pumpAndSettle();

      expect(captured.where((e) => e.$1 == 'mention_inserted').map((e) => e.$2),
          [{'context': 'comment'}]);
    });

    testWidgets('🔴 被上限拦住的那次**不**报 mention_inserted（没插进去就不算）', (tester) async {
      final captured = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);

      final candidates = [for (int i = 1; i <= 6; i++) _c(i, 'U$i')];
      await pumpComposer(tester, candidates: candidates);
      for (int i = 1; i <= 6; i++) {
        await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'hai @U$i');
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(ValueKey('mentionCandidate_$i')));
        await tester.pumpAndSettle();
      }
      // 只有前 5 次真的插进去了 —— 第 6 次被 AC5 的上限拦住。
      expect(captured.where((e) => e.$1 == 'mention_inserted'), hasLength(5));
      await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
    });

    testWidgets('AC5：第 6 个人插不进去并给出提示', (tester) async {
      final candidates = [for (int i = 1; i <= 6; i++) _c(i, 'U$i')];
      await pumpComposer(tester, candidates: candidates);
      for (int i = 1; i <= 6; i++) {
        // ⚠️ 顺手用 @ 后面的关键词把浮层筛到只剩这一个人：6 行一起渲染会超出浮层
        //    的 maxHeight，后面几行滚出可视区就点不到了（与被测行为无关的噪声）。
        await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), 'hai @U$i');
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(ValueKey('mentionCandidate_$i')));
        await tester.pumpAndSettle();
      }
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.mentionLimitReached), findsOneWidget);
      await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
    });

    testWidgets('🔴 快到 200 字时插不进去，提示字数上限而不是让用户永远重试', (tester) async {
      // 插入是直接写 controller.value，绕过 maxLength 的格式化器：不自己拦一次，
      // 提交必被服务端 @Size(max=200) 拒，而 toast 只有通用的「发送失败，请重试」。
      final (_, detail) = await pumpComposer(tester, candidates: [_c(42, 'Aurel')]);
      await tester.enterText(
          find.byKey(const ValueKey('detailCommentInput')), '${'a' * 197} @');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('mentionCandidate_42')));
      await tester.pumpAndSettle();

      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.commentLimitReached), findsOneWidget);
      // 文本没被改动（那个 @ 还留着），绑定也没记上。
      await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
      await tester.pumpAndSettle();
      expect(detail.lastCommentMentions, isEmpty);
      await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
    });

    testWidgets('发送成功后 @ 绑定一并清空（不跟下一条评论串味）', (tester) async {
      final (_, detail) = await pumpComposer(tester, candidates: [_c(42, 'Aurel')]);
      await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), '@');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('mentionCandidate_42')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
      await tester.pumpAndSettle();
      expect(detail.lastCommentMentions, [42]);

      // 第二条评论：正文里再写一次同样的字面量，但没有经过选择器 → 不该带 id。
      await tester.enterText(find.byKey(const ValueKey('detailCommentInput')), '@Aurel juga');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('detailCommentSend')));
      await tester.pumpAndSettle();
      expect(detail.lastCommentMentions, isEmpty);
    });
  });

  // ===== 触发点②：发布页正文输入框 =====

  group('AC1 触发点② 发布页正文', () {
    void tallView(WidgetTester tester) {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
    }

    Future<(PublishController, _RecordingContentRepo)> pumpCompose(
      WidgetTester tester, {
      List<MentionCandidate> candidates = const [],
    }) async {
      tallView(tester);
      final repo = _RecordingContentRepo();
      final controller =
          PublishController(repository: repo, uploadOne: (b) async => 'https://cdn/x.jpg');
      final container = ProviderContainer(overrides: [
        publishControllerProvider.overrideWithValue(controller),
        profileRepositoryProvider.overrideWithValue(_FakeProfileRepo()),
        mentionCandidateRepositoryProvider.overrideWithValue(_FakeMentions(candidates)),
      ]);
      addTearDown(container.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(body: PublishComposePage()),
        ),
      ));
      await tester.pumpAndSettle();
      return (controller, repo);
    }

    testWidgets('输入「@」→ 弹出同一个选择器浮层', (tester) async {
      await pumpCompose(tester, candidates: [_c(42, 'Aurel')]);
      expect(find.byKey(const ValueKey('mentionPicker')), findsNothing);
      await tester.enterText(find.byKey(const ValueKey('publishText')), 'hari ini @');
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('mentionPicker')), findsOneWidget);
    });

    testWidgets('🔴 AC4：正文插 @昵称、控制器里绑 userId', (tester) async {
      final (controller, _) = await pumpCompose(tester, candidates: [_c(42, 'Aurel')]);
      await tester.enterText(find.byKey(const ValueKey('publishText')), 'hari ini @');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('mentionCandidate_42')));
      await tester.pumpAndSettle();

      expect(controller.text, 'hari ini @Aurel ');
      expect(controller.mentions.refs.single.userId, 42);
      // 提交口径：文本里还留着 @Aurel → 发 id。
      expect(controller.mentions.userIdsIn(controller.text), [42]);
    });

    testWidgets('Story 3.5 AC4：插入成功 → mention_inserted(context=post)', (tester) async {
      final captured = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => captured.add((e, p));
      addTearDown(() => Analytics.debugCaptureSink = null);

      await pumpCompose(tester, candidates: [_c(42, 'Aurel')]);
      await tester.enterText(find.byKey(const ValueKey('publishText')), 'hari ini @');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('mentionCandidate_42')));
      await tester.pumpAndSettle();

      expect(captured.where((e) => e.$1 == 'mention_inserted').map((e) => e.$2),
          [{'context': 'post'}]);
    });

    testWidgets('AC5：第 6 个人插不进去并给出提示', (tester) async {
      final candidates = [for (int i = 1; i <= 6; i++) _c(i, 'U$i')];
      final (controller, _) = await pumpCompose(tester, candidates: candidates);
      for (int i = 1; i <= 6; i++) {
        // 同上：用关键词把浮层筛到只剩这一个人，避免行滚出可视区。
        await tester.enterText(find.byKey(const ValueKey('publishText')), 'hai @U$i');
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(ValueKey('mentionCandidate_$i')));
        await tester.pumpAndSettle();
      }
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.mentionLimitReached), findsOneWidget);
      expect(controller.mentions.refs, hasLength(5));
      await tester.pump(const Duration(seconds: 3)); // 走完 toast 定时器
    });
  });

  // ===== 提交形态（AC4）=====

  group('AC4 提交时只发 id', () {
    test('PublishController.publish 把文本里还在的 @ 换成 id 发出去', () async {
      final repo = _RecordingContentRepo();
      final controller =
          PublishController(repository: repo, uploadOne: (b) async => 'https://cdn/x.jpg');
      controller.setType(ContentType.daily);
      controller.setText('hai @Aurel');
      controller.mentions.insert('@', MentionDraft.queryAt('@', 1)!, 42, 'Aurel');
      await controller.publish(idempotencyKey: 'k1');
      expect(repo.lastMentions, [42]);
      expect(repo.lastText, 'hai @Aurel');
    });

    test('正文里没有那串 @昵称 → 名单为空（不给对方发无处可寻的通知）', () async {
      final repo = _RecordingContentRepo();
      final controller =
          PublishController(repository: repo, uploadOne: (b) async => 'https://cdn/x.jpg');
      controller.setType(ContentType.daily);
      controller.mentions.insert('@', MentionDraft.queryAt('@', 1)!, 42, 'Aurel');
      controller.setText('sudah aku hapus');
      await controller.publish(idempotencyKey: 'k2');
      expect(repo.lastMentions, isEmpty);
    });
  });
}
