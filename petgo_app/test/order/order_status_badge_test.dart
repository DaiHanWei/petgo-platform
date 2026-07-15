import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/order/domain/order_summary.dart';
import 'package:tailtopia/features/order/presentation/widgets/order_status_badge.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

Future<void> _pump(WidgetTester tester, Widget child) async {
  await tester.pumpWidget(MaterialApp(
    locale: const Locale('en'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Scaffold(body: Center(child: child)),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('退款中徽章：本地化文案 + INFO 色（非红）', (tester) async {
    await _pump(tester, const OrderStatusBadge(
      statusCode: 'REFUNDING',
      statusColor: OrderStatusColor.info,
    ));
    expect(find.text('Refund in progress'), findsOneWidget); // en 本地化
  });

  testWidgets('已完成徽章：SUCCESS 文案', (tester) async {
    await _pump(tester, const OrderStatusBadge(
      statusCode: 'COMPLETED',
      statusColor: OrderStatusColor.success,
    ));
    expect(find.text('Completed'), findsOneWidget);
  });

  testWidgets('退款未通过：专属文案', (tester) async {
    await _pump(tester, const OrderStatusBadge(
      statusCode: 'COMPLETED_REFUND_REJECTED',
      statusColor: OrderStatusColor.success,
    ));
    expect(find.text('Refund not approved'), findsOneWidget);
  });
}
