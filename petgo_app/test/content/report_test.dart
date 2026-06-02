import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/features/content/data/detail_repository.dart';
import 'package:petgo/features/content/domain/comment.dart';
import 'package:petgo/features/content/domain/content_detail.dart';
import 'package:petgo/features/content/presentation/report_sheet.dart';
import 'package:petgo/l10n/app_localizations.dart';
import 'package:petgo/shared/widgets/login_hard_dialog.dart';

class _ReportRepo implements DetailRepository {
  String? lastReason;
  int reportCalls = 0;

  @override
  Future<void> submitReport(int postId, String reasonType) async {
    reportCalls++;
    lastReason = reasonType;
  }

  @override
  Future<ContentDetail> getDetail(int id) => throw UnimplementedError();
  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) => throw UnimplementedError();
  @override
  Future<CommentPage> getReplies(int parentId, {String? cursor}) => throw UnimplementedError();
  @override
  Future<Comment> postComment(int postId, String body) => throw UnimplementedError();
  @override
  Future<Comment> postReply(int parentId, String body) => throw UnimplementedError();
  @override
  Future<void> deleteComment(int commentId) async {}
  @override
  Future<void> deleteContent(int postId) async {}
}

LoginResponse _user() => const LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false, onboardingCompleted: true,
    profile: UserProfile(id: 1, onboardingCompleted: true));

Future<void> _pumpTrigger(WidgetTester tester, ProviderContainer container) async {
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Consumer(
        builder: (context, ref, _) => Scaffold(
          body: Center(
            child: ElevatedButton(
              key: const ValueKey('openReport'),
              onPressed: () => openReport(context, ref, 5),
              child: const Text('report'),
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('AC1: 登录用户选类型提交举报 → 调 submitReport + 反馈', (tester) async {
    final repo = _ReportRepo();
    final container = ProviderContainer(overrides: [detailRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose);
    container.read(authControllerProvider.notifier).applyLogin(_user());

    await _pumpTrigger(tester, container);
    await tester.tap(find.byKey(const ValueKey('openReport')));
    await tester.pumpAndSettle();

    // 5 类型单选
    expect(find.byKey(const ValueKey('reportReason_harassment')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('reportReason_harassment')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('reportSubmit')));
    await tester.pumpAndSettle();

    expect(repo.reportCalls, 1);
    expect(repo.lastReason, 'HARASSMENT');
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    expect(find.text(l10n.reportSuccess), findsOneWidget); // 反馈 toast
  });

  testWidgets('AC1: 游客举报 → FR-0C 强登录弹窗，不弹 sheet', (tester) async {
    final repo = _ReportRepo();
    final container = ProviderContainer(overrides: [detailRepositoryProvider.overrideWithValue(repo)]);
    addTearDown(container.dispose); // 游客

    await _pumpTrigger(tester, container);
    await tester.tap(find.byKey(const ValueKey('openReport')));
    await tester.pumpAndSettle();

    expect(find.byType(LoginHardDialog), findsOneWidget);
    expect(repo.reportCalls, 0);
  });
}
