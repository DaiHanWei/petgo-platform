import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/gift/presentation/gift_send_ceremony.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.5.0 gift-pack Story 2-3 · AC6（L0；Rive 用替身不渲染）。
void main() {
  late List<String> events;
  setUp(() => events = []);

  Future<void> pump(WidgetTester tester, {bool reduce = false, int exp = 25, VoidCallback? onRiveDone}) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      builder: (context, child) =>
          MediaQuery(data: MediaQuery.of(context).copyWith(disableAnimations: reduce), child: child!),
      home: Builder(
        builder: (context) => Scaffold(
          body: ElevatedButton(
            key: const ValueKey('open'),
            onPressed: () async {
              await Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => GiftSendCeremonyPage(
                  petName: 'Mochi',
                  senderExp: exp,
                  riveBuilder: (_, _, onDone, onError) => GestureDetector(
                    key: const ValueKey('fakeRive'),
                    onDoubleTap: onDone,
                    onLongPress: () => onError(StateError('x')),
                  ),
                ),
              ));
              events.add('closed');
            },
            child: const Text('open'),
          ),
        ),
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('open')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
  }

  testWidgets('reduce motion → E3 directly', (tester) async {
    await pump(tester, reduce: true);
    expect(find.byKey(const ValueKey('giftDeliveredPage')), findsOneWidget);
    expect(find.byKey(const ValueKey('fakeRive')), findsNothing);
  });

  testWidgets('Lewati jumps to E3; Lanjut pops', (tester) async {
    await pump(tester);
    expect(find.byKey(const ValueKey('fakeRive')), findsOneWidget);
    await tester.tap(find.text('Lewati'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftDeliveredPage')), findsOneWidget);
    expect(find.text('Kamu dapat 25 EXP'), findsOneWidget);
    expect(find.textContaining('Hadiahmu sudah sampai ke Mochi', findRichText: true), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('giftDeliveredContinue')));
    await tester.pumpAndSettle();
    expect(events, ['closed']);
  });

  testWidgets('fallback timer reaches E3', (tester) async {
    await pump(tester);
    await tester.pump(const Duration(seconds: 5));
    expect(find.byKey(const ValueKey('giftDeliveredPage')), findsNothing);
    await tester.pump(const Duration(milliseconds: 600));
    expect(find.byKey(const ValueKey('giftDeliveredPage')), findsOneWidget);
  });

  testWidgets('rive load error → E3', (tester) async {
    await pump(tester);
    await tester.longPress(find.byKey(const ValueKey('fakeRive')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('giftDeliveredPage')), findsOneWidget);
  });

  testWidgets('senderExp = 0 hides EXP row', (tester) async {
    await pump(tester, reduce: true, exp: 0);
    expect(find.byKey(const ValueKey('giftDeliveredExp')), findsNothing);
    expect(find.textContaining('Hadiahmu sudah sampai ke', findRichText: true), findsOneWidget);
  });
}
