"""End-to-end checks for the developer platform. Run: python server/test_server.py"""
import base64
import hashlib
import http.client
import json
import tempfile
import threading
import unittest
import uuid
from http.server import ThreadingHTTPServer
from pathlib import Path

import jingqi_server as platform

PNG = platform.PNG_MAGIC + b"fake-image-bytes"


class PlatformTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        platform.DATA = Path(cls.tmp.name)
        platform.FILES = platform.DATA / "files"
        cls.config = platform.load_config()
        platform.Handler.config = cls.config
        platform.Handler.conn = platform.connect()
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), platform.Handler)
        cls.port = cls.server.server_address[1]
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        platform.Handler.conn.close()
        cls.tmp.cleanup()

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request(method, path, body=body, headers=headers or {})
        response = conn.getresponse()
        data = response.read()
        conn.close()
        return response, data

    def upload(self, report, key=None):
        return self.request("POST", "/api/reports", json.dumps(report).encode(),
                            {"Content-Type": "application/json", "X-Upload-Key": key or self.config["upload_key"]})

    def report(self, with_shot=True):
        rid = str(uuid.uuid4())
        shots = [{"name": f"{rid}.png", "sha256": hashlib.sha256(PNG).hexdigest(),
                  "data": base64.b64encode(PNG).decode()}] if with_shot else []
        return {"id": rid, "time": 1759309507000, "kind": "NOTICE", "label": "操作提示", "source": "com.vivo.browser",
                "appName": "浏览器", "detail": "识别到跳过文字但未操作：跳过文字不可点击<script>", "demo": False,
                "report": "净启 · 本地观察报告", "client": {"appVersion": "0.1.0", "manufacturer": "vivo", "model": "V2309A", "sdk": 34},
                "screenshots": shots}

    def login(self):
        response, _ = self.request("POST", "/login", f"password={self.config['admin_password']}".encode(),
                                   {"Content-Type": "application/x-www-form-urlencoded"})
        self.assertEqual(303, response.status)
        return {"Cookie": response.getheader("Set-Cookie").split(";")[0]}

    def test_upload_requires_key_and_valid_content(self):
        self.assertEqual(401, self.upload(self.report(), key="wrong")[0].status)
        bad = self.report()
        bad["screenshots"][0]["sha256"] = "0" * 64
        self.assertEqual(400, self.upload(bad)[0].status)
        bad = self.report()
        bad["screenshots"][0]["name"] = "../../evil.png"
        self.assertEqual(400, self.upload(bad)[0].status)
        bad = self.report()
        bad["id"] = "../x"
        self.assertEqual(400, self.upload(bad)[0].status)

    def test_uploaded_report_is_visible_only_after_login(self):
        report = self.report()
        response, data = self.upload(report)
        self.assertEqual(201, response.status, data)
        self.assertEqual(303, self.request("GET", "/")[0].status)
        self.assertEqual(303, self.request("GET", f"/files/{report['id']}/{report['id']}.png")[0].status)
        cookie = self.login()
        _, listing = self.request("GET", "/?q=vivo", headers=cookie)
        self.assertIn("com.vivo.browser", listing.decode())
        _, detail = self.request("GET", f"/reports/{report['id']}", headers=cookie)
        self.assertIn("跳过文字不可点击&lt;script&gt;", detail.decode())
        self.assertNotIn("<script>", detail.decode())
        response, image = self.request("GET", f"/files/{report['id']}/{report['id']}.png", headers=cookie)
        self.assertEqual("image/png", response.getheader("Content-Type"))
        self.assertEqual(PNG, image)
        self.assertEqual(303, self.request("POST", f"/reports/{report['id']}/delete", b"", cookie)[0].status)
        self.assertEqual(404, self.request("GET", f"/reports/{report['id']}", headers=cookie)[0].status)
        self.assertFalse((platform.FILES / report["id"]).exists())

    def test_text_only_upload_and_reupload_replaces(self):
        report = self.report(with_shot=False)
        self.assertEqual(201, self.upload(report)[0].status)
        report["detail"] = "第二次上传"
        self.assertEqual(201, self.upload(report)[0].status)
        cookie = self.login()
        _, detail = self.request("GET", f"/reports/{report['id']}", headers=cookie)
        self.assertIn("第二次上传", detail.decode())
        self.assertIn("用户未附带截图", detail.decode())

    def test_wrong_password_is_rejected(self):
        response, _ = self.request("POST", "/login", b"password=nope", {"Content-Type": "application/x-www-form-urlencoded"})
        self.assertEqual(401, response.status)


if __name__ == "__main__":
    unittest.main()
