"""离线单测：反向同步安全脊柱（FR9 白名单 / NFR8 乐观锁 / NFR2 diff）。

fake client 记录调用，断言越权写 / 漂移时 _update_record【零调用】——
即写边界由代码层强制，不靠模型自觉。
"""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = os.path.dirname(HERE)
sys.path.insert(0, PKG)

import writeback  # noqa: E402


CFG = {
    "writeback_whitelist": ["fixed？/Closed？", "tech", "开发说明"],
    "writeback_max_batch": 5,
    "field_ids": {
        "fixed？/Closed？": "fld9MTjCpe",
        "tech": "fldFxOlz9J",
        "开发说明": "fldqy7v4CR",
        # 非白名单（提报/定级字段）
        "Urgent Level": "fldhAzsm5u",
        "Describe（what u did）": "fldDZ6UUAn",
    },
}


class FakeClient:
    """记录 get/write 调用的假客户端。current=None 模拟记录已删。"""

    def __init__(self, current_fields):
        self._current = (None if current_fields is None
                         else {"record_id": "rec1", "fields": dict(current_fields)})
        self.writes = []   # [(record_id, fields)]
        self.reads = 0

    def get_record(self, app, table, rid, tok):
        self.reads += 1
        return self._current

    def _update_record(self, app, table, rid, fields, tok):
        self.writes.append((rid, fields))
        return {"record_id": rid, "fields": fields}


def call(client, changes, baseline):
    return writeback.restricted_update(
        client, "app", "tbl", "rec1", changes, CFG, "tok", baseline)


class TestWhitelist(unittest.TestCase):
    def test_reject_non_whitelist_field_zero_write(self):
        client = FakeClient({"Urgent Level": "P3"})
        with self.assertRaises(writeback.WriteBlocked):
            call(client, {"Urgent Level": "P0"}, {})
        self.assertEqual(client.writes, [])   # 零写
        self.assertEqual(client.reads, 0)      # 白名单未过，连重读都不发生

    def test_mixed_batch_all_rejected(self):
        client = FakeClient({})
        with self.assertRaises(writeback.WriteBlocked):
            call(client, {"开发说明": "ok", "Describe（what u did）": "hack"}, {})
        self.assertEqual(client.writes, [])

    def test_unknown_field_rejected(self):
        client = FakeClient({})
        with self.assertRaises(writeback.WriteBlocked):
            call(client, {"NotAField": "x"}, {})
        self.assertEqual(client.writes, [])

    def test_check_whitelist_helper(self):
        self.assertEqual(writeback.check_whitelist({"tech": []}, CFG), [])
        self.assertEqual(
            writeback.check_whitelist({"Urgent Level": "P0"}, CFG), ["Urgent Level"])


class TestConfigValidation(unittest.TestCase):
    def test_whitelist_name_without_field_id_is_config_error(self):
        bad = {"writeback_whitelist": ["fixed?/Closed?"],  # 半角，与 field_ids 全角不符
               "field_ids": {"fixed？/Closed？": "fldX"}}
        with self.assertRaises(ValueError):
            writeback.validate_writeback_config(bad)


class TestOptimisticLock(unittest.TestCase):
    def test_drift_on_written_field_aborts_zero_write(self):
        # 要写 开发说明，且它自拉取后被他人改 → 漂移中止，零写
        client = FakeClient({"开发说明": "别人改过的值"})
        baseline = {"开发说明": "拉取时的值"}
        with self.assertRaises(writeback.DriftError):
            call(client, {"开发说明": "AI 定位…"}, baseline)
        self.assertEqual(client.reads, 1)
        self.assertEqual(client.writes, [])

    def test_unrelated_field_drift_does_not_block(self):
        # 只写 开发说明；tech 被他人改但不在本次 changes → 不拦，正常写
        client = FakeClient({"tech": [{"id": "b"}], "开发说明": "same"})
        baseline = {"tech": [{"id": "a"}], "开发说明": "same"}
        call(client, {"开发说明": "same"}, baseline)
        self.assertEqual(len(client.writes), 1)

    def test_no_drift_writes_once(self):
        client = FakeClient({"开发说明": "old"})
        baseline = {"开发说明": "old"}
        call(client, {"开发说明": "定位: file.dart:12｜置信度: 高"}, baseline)
        self.assertEqual(len(client.writes), 1)
        rid, fields = client.writes[0]
        self.assertEqual(rid, "rec1")
        self.assertIn("开发说明", fields)

    def test_close_bug_checkbox(self):
        client = FakeClient({"fixed？/Closed？": False})
        baseline = {"fixed？/Closed？": False}
        call(client, {"fixed？/Closed？": True}, baseline)
        self.assertEqual(client.writes[0][1], {"fixed？/Closed？": True})

    def test_deleted_record_aborts(self):
        # get_record 返回 None（记录已删）→ 判漂移中止，零写
        client = FakeClient(None)
        with self.assertRaises(writeback.DriftError):
            call(client, {"开发说明": "x"}, {"开发说明": None})
        self.assertEqual(client.writes, [])

    def test_empty_changes_noop(self):
        client = FakeClient({"开发说明": "x"})
        self.assertIsNone(call(client, {}, {}))
        self.assertEqual(client.reads, 0)
        self.assertEqual(client.writes, [])


class TestNormRobustness(unittest.TestCase):
    def test_none_and_empty_string_equal(self):
        self.assertEqual(writeback._norm(None), writeback._norm(""))

    def test_person_ignores_volatile_fields_and_order(self):
        base = [{"id": "a", "name": "张三", "avatar_url": "u1"},
                {"id": "b", "name": "李四"}]
        # 重读：字段集不同 + 顺序不同，但 id 集相同 → 不算漂移
        cur = [{"id": "b", "en_name": "Li"}, {"id": "a", "name": "张三改了显示名"}]
        drift = writeback.detect_drift({"tech": base}, {"tech": cur}, {"tech": None}, CFG)
        self.assertEqual(drift, [])

    def test_richtext_vs_plain_equal(self):
        drift = writeback.detect_drift(
            {"开发说明": "abc"}, {"开发说明": [{"text": "abc", "type": "text"}]},
            {"开发说明": None}, CFG)
        self.assertEqual(drift, [])

    def test_real_person_change_detected(self):
        drift = writeback.detect_drift(
            {"tech": [{"id": "a"}]}, {"tech": [{"id": "c"}]}, {"tech": None}, CFG)
        self.assertIn("tech", drift)


class TestDiffAndBatch(unittest.TestCase):
    def test_build_diff_old_new(self):
        diff = writeback.build_diff("rec1", {"开发说明": "新结论"}, {"开发说明": "旧值"})
        self.assertEqual(diff[0]["record_id"], "rec1")
        self.assertEqual(diff[0]["old"], "旧值")
        self.assertEqual(diff[0]["new"], "新结论")

    def test_build_diff_missing_old_shows_placeholder(self):
        diff = writeback.build_diff("rec1", {"开发说明": "x"}, {})
        self.assertEqual(diff[0]["old"], "∅")

    def test_build_diff_bool_readable(self):
        diff = writeback.build_diff("rec1", {"fixed？/Closed？": True},
                                    {"fixed？/Closed？": False})
        self.assertEqual(diff[0]["old"], "否")
        self.assertEqual(diff[0]["new"], "是")

    def test_over_batch_limit(self):
        self.assertFalse(writeback.over_batch_limit(5, CFG))
        self.assertTrue(writeback.over_batch_limit(6, CFG))


class TestInjectionCannotWrite(unittest.TestCase):
    def test_injection_derived_reporter_write_blocked(self):
        client = FakeClient({})
        with self.assertRaises(writeback.WriteBlocked):
            call(client, {"Describe（what u did）": "已被篡改"}, {})
        self.assertEqual(client.writes, [])


class TestClaim(unittest.TestCase):
    CFG = {
        "developer_field": "开发人员",
        "analyze_batch_warn": 10,
        "writeback_whitelist": ["开发人员"],
        "field_ids": {"开发人员": "fldDEV"},
    }

    def test_build_claim_changes_writes_developer(self):
        self.assertEqual(writeback.build_claim_changes("dai", self.CFG), {"开发人员": "dai"})

    def test_build_claim_changes_empty_developer_raises(self):
        with self.assertRaises(ValueError):
            writeback.build_claim_changes("  ", self.CFG)

    def test_build_release_changes_clears(self):
        self.assertEqual(writeback.build_release_changes(self.CFG), {"开发人员": ""})

    def test_owns_true_plain_and_richtext(self):
        self.assertTrue(writeback.owns({"开发人员": "dai"}, "dai", self.CFG))
        self.assertTrue(writeback.owns({"开发人员": [{"text": "dai"}]}, "dai", self.CFG))

    def test_owns_false_other_or_empty(self):
        self.assertFalse(writeback.owns({"开发人员": "someone"}, "dai", self.CFG))
        self.assertFalse(writeback.owns({}, "dai", self.CFG))

    def test_over_analyze_warn(self):
        self.assertFalse(writeback.over_analyze_warn(10, self.CFG))
        self.assertTrue(writeback.over_analyze_warn(11, self.CFG))

    def test_claim_conflict_is_drift_zero_write(self):
        # 别人在我 pull 后已认领（current=someone），基线为空 → 认领写触发 DriftError，零写。
        client = FakeClient({"开发人员": "someone"})
        changes = writeback.build_claim_changes("dai", self.CFG)
        baseline = {"开发人员": None}
        with self.assertRaises(writeback.DriftError):
            writeback.restricted_update(client, "app", "tbl", "rec1", changes,
                                        self.CFG, "tok", baseline)
        self.assertEqual(len(client.writes), 0)


if __name__ == "__main__":
    unittest.main()
