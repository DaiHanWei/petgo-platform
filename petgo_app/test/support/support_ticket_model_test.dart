import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/support/domain/support_ticket.dart';

/// Story 4.2 客服工单 model fromJson（对应 4-1 SupportTicketView）。
void main() {
  test('SupportTicket.fromJson: 全字段 + 附件数量 + 标签 parse + 时间解析', () {
    final t = SupportTicket.fromJson({
      'ticketToken': 'tok-abc',
      'subject': '订单有问题',
      'body': '兽医没回复我',
      'contactType': 'WHATSAPP',
      'contactValue': '+62123',
      'needContactCustomer': true,
      'contactedCustomer': false,
      'status': 'OPEN',
      'labels': ['BUG', 'REFUND'],
      'attachmentObjectKeys': ['k1', 'k2', 'k3'],
      'csatScore': null,
      'createdAt': '2026-07-13T10:01:00Z',
      'updatedAt': '2026-07-13T10:02:00Z',
    });

    expect(t.ticketToken, 'tok-abc');
    expect(t.subject, '订单有问题');
    expect(t.body, '兽医没回复我');
    expect(t.contactType, ContactType.whatsapp);
    expect(t.contactValue, '+62123');
    expect(t.needContactCustomer, isTrue);
    expect(t.contactedCustomer, isFalse);
    expect(t.status, TicketStatus.open);
    expect(t.labels, [TicketLabelType.bug, TicketLabelType.refund]);
    expect(t.attachmentCount, 3);
    expect(t.createdAt, DateTime.utc(2026, 7, 13, 10, 1).toLocal());
  });

  test('SupportTicket.fromJson: 缺省字段优雅降级', () {
    final t = SupportTicket.fromJson({
      'ticketToken': 'tok-2',
      'body': '正文',
      'contactType': 'EMAIL',
      'contactValue': 'a@b.com',
      'status': 'RESOLVED',
    });

    expect(t.subject, isNull);
    expect(t.contactType, ContactType.email);
    expect(t.needContactCustomer, isTrue); // 默认 true
    expect(t.contactedCustomer, isFalse);
    expect(t.status, TicketStatus.resolved);
    expect(t.labels, isEmpty);
    expect(t.attachmentObjectKeys, isEmpty);
    expect(t.attachmentCount, 0);
    expect(t.createdAt, isNull);
  });

  test('SupportTicket.fromJson: 未知标签/状态优雅忽略', () {
    final t = SupportTicket.fromJson({
      'ticketToken': 'tok-3',
      'body': '正文',
      'contactType': 'EMAIL',
      'contactValue': 'a@b.com',
      'status': 'WEIRD',
      'labels': ['BUG', 'NOPE', 'PRAISE'],
    });
    expect(t.status, TicketStatus.unknown);
    expect(t.labels, [TicketLabelType.bug, TicketLabelType.praise]); // NOPE 被忽略
  });

  test('enum toApi 往返', () {
    expect(ContactType.email.toApi(), 'EMAIL');
    expect(ContactType.whatsapp.toApi(), 'WHATSAPP');
    expect(TicketLabelType.consultComplaint.toApi(), 'CONSULT_COMPLAINT');
    expect(TicketLabelType.other.toApi(), 'OTHER');
  });
}
