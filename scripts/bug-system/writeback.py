"""受限写回：FR9 字段 ID 白名单 + NFR8 乐观锁 + NFR2 旧→新 diff。

安全脊柱（PRD Epic 3）：
- 写回【只经】本模块 restricted_update；larkapi.update_record 是裸写传输，不得直接调。
- 白名单按【字段 ID】维护（改名也守得住），不在白名单的字段 ID 直接抛错、绝不发请求。
- 写前重读目标记录，与拉取基线比对白名单字段；漂移即中止，不覆盖他人改动。
纯 stdlib，可离线单测（fake client 断言越权/漂移时 update_record 零调用）。
"""

import json


class WriteBlocked(Exception):
    """FR9：试写非白名单字段，代码层拒绝（未发任何请求）。"""


class DriftError(Exception):
    """NFR8：线上记录自拉取后已被他人改动，中止写回。"""


def _whitelist_ids(cfg):
    """白名单字段名 → 其字段 ID 集合。"""
    field_ids = cfg.get("field_ids", {})
    ids = set()
    for name in cfg.get("writeback_whitelist", []):
        fid = field_ids.get(name)
        if fid:
            ids.add(fid)
    return ids


def validate_writeback_config(cfg):
    """启动校验：白名单每一项都须在 field_ids 有映射，否则显式报配置错误。

    杜绝「白名单字段名与 field_ids 键拼写不一致（如全角/半角 ？）→ 合法字段被静默
    永久 WriteBlocked、报错却像越权」这类难排查故障。
    """
    field_ids = cfg.get("field_ids", {})
    missing = [n for n in cfg.get("writeback_whitelist", []) if not field_ids.get(n)]
    if missing:
        raise ValueError(
            f"config 错误：writeback_whitelist 中这些字段在 [field_ids] 无映射: {missing}")


def check_whitelist(changes, cfg):
    """返回被拒字段名列表（字段 ID 不在白名单，或无 ID 映射）。空=全通过。"""
    field_ids = cfg.get("field_ids", {})
    allowed = _whitelist_ids(cfg)
    blocked = []
    for name in changes:
        fid = field_ids.get(name)
        if fid is None or fid not in allowed:
            blocked.append(name)
    return blocked


def _canon(v):
    """把字段值投影成稳定可比形态，消除跨端点(list_records vs get_record)表示差异。

    - None 与空串折叠为同一值（都视为空）
    - 人员对象只取 id（忽略 name/en_name/avatar 等易变字段），数组顺序无关
    - 富文本段数组 [{text,...}] → 纯文本
    - 其它多选/数组顺序无关
    """
    if v is None or v == "":
        return None
    if isinstance(v, list):
        if v and all(isinstance(x, dict) and "text" in x for x in v):
            return "".join(str(x.get("text", "")) for x in v)  # 富文本→纯文本
        return sorted((_canon(x) for x in v),
                      key=lambda x: json.dumps(x, sort_keys=True, ensure_ascii=False))
    if isinstance(v, dict):
        if "id" in v:
            return {"id": v.get("id")}          # 人员：只认 id
        if "text" in v:
            return str(v.get("text", ""))
        return v
    return v


def _norm(v):
    """归一化字符串用于漂移比对（None 与空串等价）。"""
    return json.dumps(_canon(v), sort_keys=True, ensure_ascii=False)


def detect_drift(baseline_fields, current_fields, changes, cfg):
    """只比对【本次要写的字段】(changes 的键，均∈白名单)的 baseline vs current。

    仅比对将写的字段——写 A 不该因他人改了无关字段 B 而被拦（避免误报把有负责人的
    记录永久锁死写不进）；写 A 前若 A 自拉取后已被他人改则如实报漂移、不覆盖。
    """
    baseline_fields = baseline_fields or {}
    current_fields = current_fields or {}
    drift = []
    for name in changes:
        if _norm(baseline_fields.get(name)) != _norm(current_fields.get(name)):
            drift.append(name)
    return drift


def _show(v):
    """人读呈现（NFR2 旧→新）。空/None 显 ∅。"""
    if v is None or v == "":
        return "∅"
    if isinstance(v, bool):
        return "是" if v else "否"
    if isinstance(v, list):
        return ", ".join(_show(x) for x in v) if v else "∅"
    if isinstance(v, dict):
        return str(v.get("name") or v.get("en_name") or v.get("id") or v)
    return str(v)


def build_diff(record_id, changes, current_fields):
    """NFR2：逐字段 record_id | 字段 | 旧值→新值。旧值缺失以 ∅ 呈现。"""
    current_fields = current_fields or {}
    return [
        {"record_id": record_id, "field": name,
         "old": _show(current_fields.get(name)), "new": _show(new)}
        for name, new in changes.items()
    ]


def over_batch_limit(n, cfg):
    """批次记录数是否超上限（NFR2，超则须二次确认）。"""
    return n > cfg.get("writeback_max_batch", 5)


def restricted_update(client, app_token, table_id, record_id, changes, cfg,
                      token, baseline):
    """受限写回：FR9 白名单 → NFR8 重读比对 → 写。任一关卡不过则不写。

    client 须提供 get_record / _update_record。baseline = 该记录拉取时白名单字段原始值。
    """
    if not changes:
        return None  # 空写：不触网

    validate_writeback_config(cfg)

    # FR9：白名单校验在【发请求前】，不在白名单的字段 ID 直接拒绝
    blocked = check_whitelist(changes, cfg)
    if blocked:
        raise WriteBlocked(f"字段不在写白名单（拒绝，未发请求）: {blocked}")

    # NFR8：写前重读，与拉取基线比对本次要写的字段，漂移即中止
    current = client.get_record(app_token, table_id, record_id, token)
    if current is None:
        raise DriftError("目标记录已不存在或无权限，请重新拉取后再写")
    current_fields = current.get("fields", {})
    drift = detect_drift(baseline, current_fields, changes, cfg)
    if drift:
        raise DriftError(f"线上已漂移，字段 {drift}；请重新拉取后再写，勿覆盖")

    # 通过双关卡才写（_update_record 是内部裸写传输，仅本函数可调）
    return client._update_record(app_token, table_id, record_id, changes, token)
