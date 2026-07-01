"""极薄 Lark 开放平台客户端（纯标准库 urllib）。

读：取 tenant_access_token、分页拉记录、读单记录、下载附件媒体。
写：`_update_record`（Epic 3 起加，唯一裸写传输，仅供 writeback.restricted_update 调用；
    写边界由 writeback 的 FR9 白名单 + NFR8 乐观锁在代码层强制，本文件不做边界检查）。
不引入任何第三方依赖（NFR：零 pip 依赖）。
"""

import http.client
import json
import time
import urllib.error
import urllib.parse
import urllib.request

CN_BASE = "https://open.feishu.cn"
GLOBAL_BASE = "https://open.larksuite.com"
MAX_PAGE_SIZE = 500  # Lark Bitable list records 单页上限
DEFAULT_TIMEOUT = 30  # 秒；防止半开连接永久挂起（NFR7 定时场景尤其致命）

# 可重试的网络异常（超时/连接中断/读到一半断开）。HTTPError 单独先处理。
_RETRYABLE = (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException)


class LarkError(Exception):
    """Lark API 调用失败（含重试耗尽 / 业务码非 0）。"""


def base_url(region):
    """按 region 选 base URL（NFR5）。未知 region 直接报错。"""
    if region == "cn":
        return CN_BASE
    if region == "global":
        return GLOBAL_BASE
    raise ValueError(f"unknown region: {region!r} (expected 'cn' or 'global')")


class LarkClient:
    def __init__(self, app_id, app_secret, *, region="cn", page_size=100,
                 max_retries=5, backoff_base=1.0, timeout=DEFAULT_TIMEOUT,
                 sleep=time.sleep, opener=None):
        self.app_id = app_id
        self.app_secret = app_secret
        self.base = base_url(region)
        self.page_size = min(page_size, MAX_PAGE_SIZE)
        self.max_retries = max_retries
        self.backoff_base = backoff_base
        self.timeout = timeout
        self._sleep = sleep
        self._opener = opener or urllib.request.build_opener()

    def _backoff(self, attempt):
        """末次尝试失败后不再 sleep（避免白等最大一档退避）。"""
        if attempt < self.max_retries - 1:
            self._sleep(self.backoff_base * (2 ** attempt))

    # ── 底层 HTTP（429/5xx/超时/连接中断 指数退避重试）────────────────
    def _http(self, method, url, *, headers=None, data=None):
        headers = dict(headers or {})
        body = None
        if data is not None:
            body = json.dumps(data).encode("utf-8")
            headers.setdefault("Content-Type", "application/json; charset=utf-8")

        last_err = None
        for attempt in range(self.max_retries):
            req = urllib.request.Request(url, data=body, headers=headers, method=method)
            try:
                with self._opener.open(req, timeout=self.timeout) as resp:
                    return resp.read()  # read 也纳入 try：大附件下载中途断开会在此抛
            except urllib.error.HTTPError as e:
                # 限流 / 服务端错误：退避重试；其余直接抛
                if e.code == 429 or 500 <= e.code < 600:
                    last_err = LarkError(f"HTTP {e.code} on {method} {url}")
                    self._backoff(attempt)
                    continue
                raise LarkError(f"HTTP {e.code} on {method} {url}: {e.reason}") from e
            except _RETRYABLE as e:
                last_err = LarkError(f"network error on {method} {url}: {e}")
                self._backoff(attempt)
                continue
        raise last_err or LarkError(f"exhausted retries on {method} {url}")

    def _json(self, method, url, *, headers=None, data=None):
        raw = self._http(method, url, headers=headers, data=data)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as e:
            raise LarkError(f"non-JSON response from {url}") from e
        if payload.get("code", 0) != 0:
            # 业务错误：不打印任何凭证，只带 code/msg
            raise LarkError(f"Lark API error {payload.get('code')}: {payload.get('msg')}")
        return payload

    # ── 鉴权 ──────────────────────────────────────────────────────────
    def get_token(self):
        """取 tenant_access_token（自建应用 internal）。"""
        url = f"{self.base}/open-apis/auth/v3/tenant_access_token/internal"
        payload = self._json("POST", url, data={
            "app_id": self.app_id,
            "app_secret": self.app_secret,
        })
        token = payload.get("tenant_access_token")
        if not token:
            raise LarkError("no tenant_access_token in response")
        return token

    # ── 拉记录（分页取全部）────────────────────────────────────────────
    def list_records(self, app_token, table_id, token, view_id=None):
        """分页拉取一张 Bitable 表的记录，返回 list。

        传 view_id 则只拉该视图筛选/排序后的记录（如「技术视图」= 已定级未关闭），
        表再大也只取工程师队列那批，无需在本地过滤。
        """
        headers = {"Authorization": f"Bearer {token}"}
        records = []
        page_token = None
        while True:
            url = (f"{self.base}/open-apis/bitable/v1/apps/{app_token}"
                   f"/tables/{table_id}/records?page_size={self.page_size}")
            if view_id:
                url += f"&view_id={urllib.parse.quote(view_id)}"
            if page_token:
                url += f"&page_token={urllib.parse.quote(page_token)}"
            payload = self._json("GET", url, headers=headers)
            data = payload.get("data") or {}  # data 可能显式为 null
            records.extend(data.get("items", []) or [])
            next_token = data.get("page_token")
            # page_token 必须前进，否则视为异常分页，中断防死循环
            if data.get("has_more") and next_token and next_token != page_token:
                page_token = next_token
            else:
                break
        return records

    # ── 读单条记录（乐观锁重读用）──────────────────────────────────────
    def get_record(self, app_token, table_id, record_id, token):
        """读单条记录，返回 {"record_id":..., "fields":{...}}（无则 None）。"""
        headers = {"Authorization": f"Bearer {token}"}
        url = (f"{self.base}/open-apis/bitable/v1/apps/{app_token}"
               f"/tables/{table_id}/records/{urllib.parse.quote(record_id)}")
        data = self._json("GET", url, headers=headers).get("data") or {}
        return data.get("record")

    # ── 写单条记录（PUT）────────────────────────────────────────────────
    # ⚠️ 全仓唯一写传输，前缀 _ 标记内部：仅供 writeback.restricted_update 调用，
    #    由其在代码层做字段 ID 白名单校验(FR9) + 乐观锁(NFR8)。此处不含任何边界检查。
    #    （Python 无硬私有；靠命名约定 + 单一调用点约束，勿从别处直接调。）
    def _update_record(self, app_token, table_id, record_id, fields, token):
        """PUT 更新一条记录的若干字段（body 按字段名）。返回更新后的 record。

        实测：飞书多维表格「更新记录」用 PUT（PATCH 返回 404）。
        """
        headers = {"Authorization": f"Bearer {token}"}
        url = (f"{self.base}/open-apis/bitable/v1/apps/{app_token}"
               f"/tables/{table_id}/records/{urllib.parse.quote(record_id)}")
        data = self._json("PUT", url, headers=headers, data={"fields": fields})
        return (data.get("data") or {}).get("record")

    # ── 下载附件媒体 ──────────────────────────────────────────────────
    def download_media(self, file_token, token, extra=None):
        """按 file_token 下载附件二进制，返回 bytes。

        多维表格附件必须带 extra 定位参数，否则 400/403（实测：仅 file_token 会 400）。
        extra 传 JSON 串，如 `{"bitablePerm":{"tableId":"tbl..."}}`；本函数负责 URL 编码。
        """
        headers = {"Authorization": f"Bearer {token}"}
        url = f"{self.base}/open-apis/drive/v1/medias/{file_token}/download"
        if extra:
            url += f"?extra={urllib.parse.quote(extra)}"
        return self._http("GET", url, headers=headers)
