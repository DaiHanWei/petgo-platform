"""原始 Lark 记录 → 结构化 dict 的纯函数层。

设计约束（spec / PRD §4.4）：
- 本模块【纯函数】，不碰网络、不碰文件，便于离线用样本数据全覆盖单测。
- 提报人字段（B 组）一律裹进 <<<UNTRUSTED_REPORT … >>> 定界块（NFR3 防注入）。
- 缺失/不可解析的提报字段稳健降级：不抛异常，标 _quality=insufficient 并列出缺项（FR13）。
- 每条记录同时带 record_id（机器主键）与 Bug ID（人读展示号），关联一律以 record_id 为准（FR8）。
"""

from datetime import datetime, timezone

# 定界块标记（分析命令据此识别不可信区，spec Design Notes）
_MARKER_TOKEN = "UNTRUSTED_REPORT"
_NEUTRAL_TOKEN = "UNTRUSTED-REPORT"  # 连字符变体：不构成定界标记，肉眼仍可读
UNTRUSTED_OPEN = f"<<<{_MARKER_TOKEN}"
UNTRUSTED_CLOSE = f"{_MARKER_TOKEN}>>>"


def neutralize_markers(text):
    """中和文本里已含的定界标记，杜绝提报人闭合定界块突破（NFR3 防注入）。

    把任何字面 `UNTRUSTED_REPORT` 替换成连字符变体——开/闭标记都含该子串，
    替换后正文里再不可能出现 `<<<UNTRUSTED_REPORT` 或 `UNTRUSTED_REPORT>>>`。
    """
    return str(text).replace(_MARKER_TOKEN, _NEUTRAL_TOKEN)


def wrap_untrusted(text):
    """把提报人文本裹进定界块。空串/None 不裹（视为缺失）。"""
    if text is None:
        return ""
    text = str(text)
    if text == "":
        return ""
    return f"{UNTRUSTED_OPEN}\n{neutralize_markers(text)}\n{UNTRUSTED_CLOSE}"


# ── 各字段类型 → 可读文本/结构 ────────────────────────────────────────

def _render_text(value):
    """文本字段。Lark 富文本可能返回 [{'text': ..., 'type': 'text'}, ...]。"""
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        parts = []
        for seg in value:
            if isinstance(seg, dict):
                parts.append(str(seg.get("text", "")))
            else:
                parts.append(str(seg))
        return "".join(parts)
    return str(value)


def _render_select(value):
    """单选：Lark 返回选项文本字符串。"""
    if value is None:
        return ""
    if isinstance(value, list):  # 兼容偶发以列表返回
        return ", ".join(str(v) for v in value)
    return str(value)


def _render_multi_select(value):
    """多选：返回列表 → 逗号连接的可读文本。"""
    if value is None:
        return ""
    if isinstance(value, list):
        return ", ".join(str(v) for v in value)
    return str(value)


def _multi_select_list(value):
    """多选 → 原始 list（用于控制键，如 platform/reported_on）。"""
    if value is None:
        return []
    if isinstance(value, list):
        return [str(v) for v in value]
    return [str(value)]


def _render_person(value):
    """人员字段：Lark 返回 [{'id','name','en_name', ...}, ...] → 姓名连接。"""
    if value is None:
        return ""
    if isinstance(value, dict):
        value = [value]
    if isinstance(value, list):
        names = []
        for p in value:
            if isinstance(p, dict):
                names.append(str(p.get("name") or p.get("en_name") or p.get("id") or ""))
            else:
                names.append(str(p))
        return ", ".join(n for n in names if n)
    return str(value)


def _render_datetime(value):
    """时间字段：Lark 返回 epoch 毫秒 → ISO8601 UTC 文本。"""
    if value is None or value == "":
        return ""
    try:
        ms = int(value)
    except (TypeError, ValueError):
        return str(value)
    dt = datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _attachment_list(value):
    """附件字段 → 结构化 list（保留 file_token 供下载）。"""
    out = []
    if not isinstance(value, list):
        return out
    for a in value:
        if not isinstance(a, dict):
            continue
        out.append({
            "name": a.get("name", ""),
            "file_token": a.get("file_token", ""),
            "type": a.get("type", ""),
            "url": a.get("url", ""),
        })
    return out


def _render_auto_number(value):
    if value is None:
        return ""
    return str(value)


def _render_checkbox(value):
    """复选框：bool → 「是」/「否」。"""
    return "是" if value is True else "否"


_RENDERERS = {
    "text": _render_text,
    "select": _render_select,
    "multi_select": _render_multi_select,
    "person": _render_person,
    "datetime": _render_datetime,
    "auto_number": _render_auto_number,
    "checkbox": _render_checkbox,
}


def render_field(value, ftype):
    """按字段类型渲染为可读文本。附件类不在此（单独走 _attachment_list）。"""
    renderer = _RENDERERS.get(ftype, _render_text)
    return renderer(value)


# ── 主入口 ────────────────────────────────────────────────────────────

def parse_record(raw, cfg):
    """原始 Lark 记录 → 结构化 dict。

    raw 形如 Lark list records 单条：{"record_id": "...", "fields": {字段名: 值}}。
    cfg 为 load_config() 的结果（含 field_types/reporter_fields/各关键字段名）。

    返回 dict：record_id + bug_id + 控制键(severity/status/...) + fields(渲染后,
    提报字段裹定界块) + attachments + _quality + _missing。
    单条解析永不抛异常（FR13 稳健降级）；解析内部异常落到 _missing。
    """
    field_types = cfg.get("field_types", {})
    reporter_fields = set(cfg.get("reporter_fields", []))
    raw_fields = raw.get("fields") or {}

    record_id = raw.get("record_id", "")
    bug_id_field = cfg.get("bug_id_field", "Bug ID")
    screenshots_field = cfg.get("screenshots_field", "Screenshots")
    steps_field = cfg.get("steps_field", "Steps to Reproduce")
    module_field = cfg.get("module_field", "Module")

    rendered = {}
    attachments = []

    for name, value in raw_fields.items():
        ftype = field_types.get(name, "text")
        try:
            if ftype == "attachment":
                items = _attachment_list(value)
                # 文件名是提报人可控自由文本，同视为不可信：中和其定界标记（NFR3）。
                # file_token 保持原样（机器主键，下载用，非展示文本）。
                rendered[name] = [neutralize_markers(a["name"]) for a in items]
                for idx, a in enumerate(items, start=1):
                    attachments.append({
                        "field": name,
                        "index": idx,
                        "name": neutralize_markers(a["name"]),
                        "file_token": a["file_token"],
                        "type": a["type"],
                        "url": a["url"],
                    })
                continue
            text = render_field(value, ftype)
            if name in reporter_fields:
                rendered[name] = wrap_untrusted(text)
            else:
                rendered[name] = text
        except Exception as exc:  # 单字段解析失败不拖垮整条（FR13）
            rendered[name] = ""
            rendered.setdefault("_parse_errors", []).append(f"{name}: {exc}")

    # 控制键：来自受约束的单选/多选，非自由文本，可安全外露供排序/路由
    bug_id = _render_auto_number(raw_fields.get(bug_id_field))
    severity = _render_select(raw_fields.get(cfg.get("severity_field", "Severity")))
    status = _render_select(raw_fields.get(cfg.get("status_field", "Status")))
    bug_type = _render_select(raw_fields.get(cfg.get("bug_type_field", "Bug Type")))
    module = _render_select(raw_fields.get(module_field)).strip()
    platform = _multi_select_list(raw_fields.get(cfg.get("platform_field", "Platform")))
    reported_on = _multi_select_list(raw_fields.get(cfg.get("reported_on_field", "Reported On")))

    # 质量降级判定（FR13）：缺复现 / 缺截图 / Module=Other 或空
    missing = []
    if _render_text(raw_fields.get(steps_field)).strip() == "":
        missing.append("steps_to_reproduce")
    screenshot_atts = [a for a in attachments if a["field"] == screenshots_field]
    if not screenshot_atts:
        missing.append("screenshots")
    if module == "" or module == "Other":
        missing.append("module")

    return {
        "record_id": record_id,
        "bug_id": bug_id,
        "severity": severity,
        "status": status,
        "bug_type": bug_type,
        "module": module,
        "platform": platform,
        "reported_on": reported_on,
        "fields": rendered,
        "attachments": attachments,
        "_quality": "insufficient" if missing else "ok",
        "_missing": missing,
    }


def severity_rank(severity, cfg):
    """严重级别 → 排序键（越小越靠前）。未知排最后。"""
    order = cfg.get("severity_order", [])
    try:
        return order.index(severity)
    except ValueError:
        return len(order)


def is_closed(record, cfg):
    """该记录是否处于已关闭状态。"""
    return record.get("status", "") in set(cfg.get("closed_statuses", []))
