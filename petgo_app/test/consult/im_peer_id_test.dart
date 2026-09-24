import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/vet/domain/vet_inbox_item.dart';
import 'package:tailtopia/features/vet/domain/vet_workbench_lists.dart';

/// bug 519/521：IM 对端账号一律用后端下发（带环境前缀），App 不自拼；老后端未下发才回落旧格式。
void main() {
  test('ConsultSession 读取后端下发的 vetImUserId', () {
    final s = ConsultSession.fromJson({'id': 1, 'status': 'IN_PROGRESS', 'vetId': 9, 'vetImUserId': 'stg_v_9'});
    expect(s.vetImUserId, 'stg_v_9');
    final legacy = ConsultSession.fromJson({'id': 1, 'status': 'IN_PROGRESS', 'vetId': 9});
    expect(legacy.vetImUserId, isNull);
  });

  test('VetSession.imPeerId 优先后端下发，缺失回落 u_<userId>', () {
    expect(VetSession.fromJson({'id': 1, 'userId': 75, 'userImUserId': 'stg_u_75'}).imPeerId, 'stg_u_75');
    expect(VetSession.fromJson({'id': 1, 'userId': 75}).imPeerId, 'u_75');
    expect(VetSession.fromJson({'id': 1}).imPeerId, isNull);
  });

  test('VetActiveItem.imPeerId 优先后端下发且 copyWith 保留', () {
    final item = VetActiveItem.fromJson({'sessionId': 1, 'userId': 75, 'userImUserId': 'stg_u_75'});
    expect(item.imPeerId, 'stg_u_75');
    expect(item.copyWith(unread: 3).imPeerId, 'stg_u_75');
    expect(VetActiveItem.fromJson({'sessionId': 1, 'userId': 75}).imPeerId, 'u_75');
  });
}
