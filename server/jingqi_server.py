#!/usr/bin/env python3
"""净启开发者平台：接收用户在 App 中主动选择上传的报告，并提供网页后台查看。

只依赖 Python 3.9+ 标准库。数据保存在本目录 data/ 下：
  data/config.json   后台登录口令与上传码（首次运行自动生成）
  data/reports.db    报告记录（SQLite）
  data/files/<id>/   用户勾选上传的截图

用法：python jingqi_server.py [--host 0.0.0.0] [--port 8787]
"""
import argparse
import base64
import hashlib
import hmac
import html
import json
import re
import secrets
import socket
import sqlite3
import threading
import time
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

DATA = Path(__file__).resolve().parent / "data"
FILES = DATA / "files"
MAX_BODY = 40 * 1024 * 1024
SESSION_SECONDS = 12 * 3600
ID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
KINDS = {"SKIP": "执行跳过", "RETURN": "确认返回", "EVIDENCE": "页面观察", "NOTICE": "操作提示"}

db_lock = threading.Lock()
sessions = {}
sessions_lock = threading.Lock()


def load_config():
    DATA.mkdir(parents=True, exist_ok=True)
    path = DATA / "config.json"
    if path.exists():
        return json.loads(path.read_text("utf-8"))
    config = {
        "admin_password": secrets.token_urlsafe(9),
        "upload_key": "".join(secrets.choice("0123456789") for _ in range(8)),
    }
    path.write_text(json.dumps(config, ensure_ascii=False, indent=2), "utf-8")
    return config


def connect():
    conn = sqlite3.connect(DATA / "reports.db", check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("""CREATE TABLE IF NOT EXISTS reports (
        id TEXT PRIMARY KEY, received REAL, time INTEGER, kind TEXT, label TEXT, source TEXT, app_name TEXT,
        target TEXT, detail TEXT, estimated INTEGER, demo INTEGER, report TEXT, app_version TEXT,
        manufacturer TEXT, model TEXT, sdk INTEGER, client_ip TEXT, shots TEXT)""")
    conn.commit()
    return conn


def text(value, limit):
    return str(value if value is not None else "")[:limit]


def store_report(conn, body, client_ip):
    """Validates an upload and stores it, replacing an earlier upload of the same report."""
    rid = text(body.get("id"), 64)
    if not ID_RE.match(rid):
        raise ValueError("报告编号无效")
    shots = []
    for shot in body.get("screenshots") or []:
        name = text(shot.get("name"), 80)
        if name not in (f"{rid}.png", f"{rid}_marked.png"):
            raise ValueError("截图文件名无效")
        data = base64.b64decode(shot.get("data") or "", validate=True)
        if not data.startswith(PNG_MAGIC):
            raise ValueError("截图不是 PNG")
        digest = hashlib.sha256(data).hexdigest()
        if digest != text(shot.get("sha256"), 64).lower():
            raise ValueError("截图校验值不一致，可能在传输中损坏")
        shots.append((name, digest, data))
    client = body.get("client") or {}
    folder = FILES / rid
    with db_lock:
        if folder.exists():
            for old in folder.iterdir():
                old.unlink()
        if shots:
            folder.mkdir(parents=True, exist_ok=True)
            for name, _, data in shots:
                (folder / name).write_bytes(data)
        conn.execute("INSERT OR REPLACE INTO reports VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (
            rid, time.time(), int(body.get("time") or 0), text(body.get("kind"), 20), text(body.get("label"), 40),
            text(body.get("source"), 200), text(body.get("appName"), 200), text(body.get("target"), 200),
            text(body.get("detail"), 5000), int(body.get("estimatedSeconds") or 0), 1 if body.get("demo") else 0,
            text(body.get("report"), 20000), text(client.get("appVersion"), 40), text(client.get("manufacturer"), 80),
            text(client.get("model"), 80), int(client.get("sdk") or 0), client_ip,
            json.dumps([{"name": n, "sha256": d} for n, d, _ in shots])))
        conn.commit()
    return rid


def fmt_time(ms):
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ms / 1000)) if ms else "未知"


STYLE = """
body{margin:0;font-family:system-ui,"Microsoft YaHei",sans-serif;background:#f6f7f2;color:#1d332c}
header{background:#176954;color:#fff;padding:16px 28px;display:flex;align-items:center;gap:16px}
header h1{font-size:20px;margin:0;flex:1} header a{color:#dff7a5}
main{max-width:1100px;margin:24px auto;padding:0 20px}
.card{background:#fff;border-radius:16px;padding:20px 24px;margin-bottom:16px}
table{width:100%;border-collapse:collapse;background:#fff;border-radius:16px;overflow:hidden}
th,td{text-align:left;padding:10px 12px;border-bottom:1px solid #eef1ea;font-size:14px;vertical-align:top}
th{background:#ebf4e7;color:#176954} tr:hover td{background:#fafcf7}
.tag{display:inline-block;padding:2px 8px;border-radius:8px;background:#ebf4e7;color:#176954;font-size:12px;margin-right:4px}
.demo{background:#fff1d6;color:#8a5a00} .muted{color:#718077;font-size:13px}
pre{white-space:pre-wrap;background:#f6f7f2;padding:14px;border-radius:12px;font-size:13px}
img{max-width:100%;border-radius:12px;border:1px solid #e3e8df}
input,select,button{font:inherit;padding:8px 12px;border-radius:10px;border:1px solid #cfd8cc}
button{background:#176954;color:#fff;border:none;cursor:pointer} button.danger{background:#a33}
form.inline{display:inline} .grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}
"""


def page(title, body, logged_in=True):
    nav = '<a href="/">报告列表</a> <a href="/logout">退出</a>' if logged_in else ""
    return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{html.escape(title)} · 净启开发者平台</title>
<style>{STYLE}</style></head><body><header><h1>净启 · 开发者平台</h1>{nav}</header><main>{body}</main></body></html>"""


class Handler(BaseHTTPRequestHandler):
    config = {}
    conn = None
    server_version = "JingQiPlatform/1.0"

    # ---- helpers ----
    def send(self, status, body, content_type="text/html; charset=utf-8", headers=None):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-store")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def json(self, status, payload):
        self.send(status, json.dumps(payload, ensure_ascii=False), "application/json; charset=utf-8")

    def redirect(self, location, headers=None):
        self.send(303, "", headers={"Location": location, **(headers or {})})

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY:
            raise ValueError("上传内容过大")
        return self.rfile.read(length)

    def session(self):
        cookie = SimpleCookie(self.headers.get("Cookie") or "")
        token = cookie["jq_session"].value if "jq_session" in cookie else ""
        with sessions_lock:
            expiry = sessions.get(token)
            if expiry and expiry > time.time():
                return token
            sessions.pop(token, None)
        return None

    def require_login(self):
        if self.session():
            return True
        self.redirect("/login")
        return False

    def log_message(self, fmt, *args):
        print(f"[{time.strftime('%H:%M:%S')}] {self.client_address[0]} {fmt % args}")

    # ---- routes ----
    def do_GET(self):
        url = urlparse(self.path)
        parts = [p for p in url.path.split("/") if p]
        if url.path == "/health":
            return self.json(200, {"ok": True})
        if url.path == "/login":
            return self.send(200, self.login_page())
        if url.path == "/logout":
            token = self.session()
            with sessions_lock:
                sessions.pop(token, None)
            return self.redirect("/login", {"Set-Cookie": "jq_session=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict"})
        if not self.require_login():
            return
        if url.path == "/":
            return self.send(200, self.list_page(parse_qs(url.query)))
        if len(parts) == 2 and parts[0] == "reports" and ID_RE.match(parts[1]):
            return self.detail_page(parts[1])
        if len(parts) == 3 and parts[0] == "files" and ID_RE.match(parts[1]) and \
                parts[2] in (f"{parts[1]}.png", f"{parts[1]}_marked.png"):
            path = FILES / parts[1] / parts[2]
            if path.exists():
                return self.send(200, path.read_bytes(), "image/png")
        self.send(404, page("未找到", '<div class="card">页面不存在。</div>'))

    def do_POST(self):
        url = urlparse(self.path)
        parts = [p for p in url.path.split("/") if p]
        try:
            if url.path == "/api/reports":
                return self.receive_upload()
            if url.path == "/login":
                return self.login(parse_qs(self.read_body().decode("utf-8")))
            if not self.require_login():
                return
            if len(parts) == 3 and parts[0] == "reports" and ID_RE.match(parts[1]) and parts[2] == "delete":
                with db_lock:
                    self.conn.execute("DELETE FROM reports WHERE id = ?", (parts[1],))
                    self.conn.commit()
                    folder = FILES / parts[1]
                    if folder.exists():
                        for f in folder.iterdir():
                            f.unlink()
                        folder.rmdir()
                return self.redirect("/")
            self.send(404, page("未找到", '<div class="card">页面不存在。</div>'))
        except ValueError as error:
            self.json(400, {"error": str(error)})

    def receive_upload(self):
        key = self.headers.get("X-Upload-Key") or ""
        if not hmac.compare_digest(key.encode(), self.config["upload_key"].encode()):
            return self.json(401, {"error": "上传码不正确"})
        try:
            body = json.loads(self.read_body().decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return self.json(400, {"error": "内容格式无效"})
        try:
            rid = store_report(self.conn, body, self.client_address[0])
        except (ValueError, TypeError) as error:
            return self.json(400, {"error": str(error)})
        print(f"  ↳ 收到报告 {rid}（{body.get('appName') or body.get('source')}，截图 {len(body.get('screenshots') or [])} 张）")
        self.json(201, {"ok": True, "id": rid})

    def login(self, form):
        password = (form.get("password") or [""])[0]
        if not hmac.compare_digest(password.encode(), self.config["admin_password"].encode()):
            time.sleep(1)
            return self.send(401, self.login_page("口令不正确"))
        token = secrets.token_urlsafe(24)
        with sessions_lock:
            sessions[token] = time.time() + SESSION_SECONDS
        self.redirect("/", {"Set-Cookie": f"jq_session={token}; Max-Age={SESSION_SECONDS}; Path=/; HttpOnly; SameSite=Strict"})

    # ---- pages ----
    def login_page(self, error=""):
        message = f'<p style="color:#a33">{html.escape(error)}</p>' if error else ""
        return page("登录", f"""<div class="card" style="max-width:420px;margin:60px auto">
<h2>登录后台</h2><p class="muted">口令在启动服务器的窗口中显示，也保存在 server/data/config.json。</p>{message}
<form method="post" action="/login"><input type="password" name="password" placeholder="后台口令" autofocus style="width:100%;box-sizing:border-box">
<p><button type="submit">登录</button></p></form></div>""", logged_in=False)

    def list_page(self, query):
        q = (query.get("q") or [""])[0].strip()
        kind = (query.get("kind") or [""])[0]
        demo = (query.get("demo") or [""])[0]
        sql, args = "SELECT * FROM reports WHERE 1=1", []
        if q:
            sql += " AND (app_name LIKE ? OR source LIKE ? OR detail LIKE ? OR model LIKE ?)"
            args += [f"%{q}%"] * 4
        if kind in KINDS:
            sql += " AND kind = ?"
            args.append(kind)
        if demo in ("0", "1"):
            sql += " AND demo = ?"
            args.append(int(demo))
        with db_lock:
            rows = self.conn.execute(sql + " ORDER BY received DESC LIMIT 500", args).fetchall()
            total = self.conn.execute("SELECT COUNT(*) FROM reports").fetchone()[0]
            top = self.conn.execute("SELECT app_name, source, COUNT(*) c FROM reports WHERE demo = 0 "
                                    "GROUP BY source ORDER BY c DESC LIMIT 5").fetchall()
        kind_options = "".join(f'<option value="{k}"{" selected" if k == kind else ""}>{v}</option>' for k, v in KINDS.items())
        demo_options = "".join(f'<option value="{v}"{" selected" if v == demo else ""}>{label}</option>'
                               for v, label in (("0", "仅真实记录"), ("1", "仅演示记录")))
        top_html = "、".join(f"{html.escape(r['app_name'] or r['source'])}（{r['c']}）" for r in top) or "暂无"
        body_rows = "".join(f"""<tr><td>{fmt_time(r['time'])}<div class="muted">收到 {time.strftime('%m-%d %H:%M', time.localtime(r['received']))}</div></td>
<td><span class="tag{' demo' if r['demo'] else ''}">{'演示 · ' if r['demo'] else ''}{html.escape(r['label'] or r['kind'])}</span></td>
<td><b>{html.escape(r['app_name'] or '')}</b><div class="muted">{html.escape(r['source'])}</div></td>
<td>{html.escape((r['detail'] or '')[:90])}{'…' if len(r['detail'] or '') > 90 else ''}</td>
<td>{html.escape(f"{r['manufacturer']} {r['model']}")}<div class="muted">Android SDK {r['sdk']}</div></td>
<td>{len(json.loads(r['shots'] or '[]'))}</td><td><a href="/reports/{r['id']}">查看</a></td></tr>""" for r in rows)
        empty = '<tr><td colspan="7" class="muted">还没有收到报告。在 App 的“取证”页选择报告并上传后会出现在这里。</td></tr>'
        return page("报告列表", f"""<div class="card"><b>共 {total} 条报告</b>
<span class="muted">　真实记录中最常出现的应用：{top_html}</span>
<form method="get" style="margin-top:12px"><input name="q" value="{html.escape(q)}" placeholder="搜索应用、包名、说明、机型">
<select name="kind"><option value="">全部类型</option>{kind_options}</select>
<select name="demo"><option value="">真实与演示</option>{demo_options}</select> <button>筛选</button></form></div>
<table><tr><th>发生时间</th><th>类型</th><th>应用</th><th>说明</th><th>机型</th><th>截图</th><th></th></tr>{body_rows or empty}</table>""")

    def detail_page(self, rid):
        with db_lock:
            r = self.conn.execute("SELECT * FROM reports WHERE id = ?", (rid,)).fetchone()
        if not r:
            return self.send(404, page("未找到", '<div class="card">报告不存在或已删除。</div>'))
        shots = json.loads(r["shots"] or "[]")
        images = "".join(f"""<div><p class="muted">{'标注副本' if s['name'].endswith('_marked.png') else '原始截图'} · SHA-256 {s['sha256'][:16]}…</p>
<a href="/files/{rid}/{quote(s['name'])}" target="_blank"><img src="/files/{rid}/{quote(s['name'])}" alt="截图"></a></div>""" for s in shots)
        info = [("报告编号", r["id"]), ("发生时间", fmt_time(r["time"])),
                ("收到时间", time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(r["received"]))),
                ("类型", ("演示 · " if r["demo"] else "") + (r["label"] or r["kind"])), ("应用", f"{r['app_name']}（{r['source']}）"),
                ("跳转目标", r["target"] or "未记录"), ("机型", f"{r['manufacturer']} {r['model']}"),
                ("系统", f"Android SDK {r['sdk']}"), ("净启版本", r["app_version"]), ("来源 IP", r["client_ip"])]
        rows = "".join(f"<tr><th>{k}</th><td>{html.escape(str(v))}</td></tr>" for k, v in info)
        return self.send(200, page("报告详情", f"""<div class="card"><h2>{html.escape(r['app_name'] or r['source'])}</h2>
<table>{rows}</table><h3>观察说明</h3><pre>{html.escape(r['detail'] or '')}</pre>
<h3>完整报告</h3><pre>{html.escape(r['report'] or '')}</pre>
<form class="inline" method="post" action="/reports/{rid}/delete" onsubmit="return confirm('删除这条报告及其截图？')">
<button class="danger">删除此报告</button></form></div>
{'<div class="card"><h3>截图</h3><div class="grid">' + images + '</div></div>' if shots else '<div class="card muted">用户未附带截图。</div>'}"""))


def lan_addresses():
    found = set()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))  # No packet is sent; this only picks the outgoing interface.
            found.add(s.getsockname()[0])
    except OSError:
        pass
    try:
        found.update(a for a in socket.gethostbyname_ex(socket.gethostname())[2] if not a.startswith("127."))
    except OSError:
        pass
    return sorted(found)


def main():
    parser = argparse.ArgumentParser(description="净启开发者平台")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    config = load_config()
    Handler.config = config
    Handler.conn = connect()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print("净启开发者平台已启动")
    print(f"  后台口令：{config['admin_password']}")
    print(f"  上传码：  {config['upload_key']}")
    print("  在手机净启 → 设置 → 开发者平台 中填写以下任一地址（手机需与电脑在同一 WiFi）：")
    for address in lan_addresses() or ["<本机局域网 IP>"]:
        print(f"    http://{address}:{args.port}")
    print(f"  电脑浏览器打开后台：http://127.0.0.1:{args.port}/")
    print("  按 Ctrl+C 停止。")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")


if __name__ == "__main__":
    main()
