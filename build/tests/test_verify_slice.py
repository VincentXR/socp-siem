"""The tenant slice must reject a failed current write even with old matching data."""

import contextlib
import io
import json
import runpy
import sys
import types
import unittest
import urllib.request
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "verify-slice.py"


class Response:
    def __init__(self, status, body, headers=None):
        self.status = status
        self.body = json.dumps(body).encode()
        self.headers = headers or {"X-Trace-Id": "trace-1"}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.body


class SliceFixture:
    def __init__(self, reject_second_tenant, reject_first_tenant_list=False):
        self.reject_second_tenant = reject_second_tenant
        self.reject_first_tenant_list = reject_first_tenant_list
        self.t1_reads = 0
        self.t2_rule = None
        self.t2_alarm = None
        self.t1_writes = 0

    def urlopen(self, request, timeout=10):
        del timeout
        tenant = next((value for key, value in request.header_items()
                       if key.lower() == "x-tenant-id"), "t1")
        if request.get_header("Authorization") is None:
            return Response(401, {"code": 401, "message": "unauthorized", "traceId": "trace-1"})
        if request.get_method() == "POST":
            payload = json.loads(request.data)
            if tenant == "t2":
                if self.reject_second_tenant:
                    return Response(503, {"code": 503, "message": "write unavailable"})
                self.t2_rule = payload["ruleId"]
                self.t2_alarm = "current-t2"
                return Response(200, {"code": 0, "data": {"id": self.t2_alarm}})
            self.t1_writes += 1
            return Response(200, {"code": 0, "data": {
                "id": "current-t1-%s" % self.t1_writes,
                "occurredAt": payload.get("occurredAt", "2026-09-23T00:00:00Z"),
            }})
        if tenant == "t2":
            items = [{"id": "historic-t2", "ruleId": "R-T2"}]
            if self.t2_alarm:
                items.append({"id": self.t2_alarm, "ruleId": self.t2_rule})
            return Response(200, {"code": 0, "data": {"items": items}, "traceId": "trace-1"})
        self.t1_reads += 1
        if self.reject_first_tenant_list and self.t1_reads == 2:
            return Response(503, {"code": 503, "message": "read unavailable"})
        if 4 <= self.t1_reads <= 23 and self.t1_reads > 13:
            return Response(429, {"code": 429, "message": "limited"},
                            {"X-Trace-Id": "trace-1", "Retry-After": "1"})
        return Response(200, {"code": 0, "data": {"items": [
            {"id": "current-t1-1", "ruleId": "R-SSH-BRUTE"},
            {"id": "current-t1-2", "ruleId": "R-PORTSCAN"},
        ]}, "traceId": "trace-1"})


class VerifySliceTest(unittest.TestCase):
    def run_fixture(self, reject_second_tenant, reject_first_tenant_list=False):
        fixture = SliceFixture(reject_second_tenant, reject_first_tenant_list)
        output = io.StringIO()
        fake_auth = types.SimpleNamespace(login_token=lambda *_args, **_kwargs: "fixture-token")
        with mock.patch.dict(sys.modules, {"auth_client": fake_auth}), \
             mock.patch.object(urllib.request, "urlopen", fixture.urlopen), \
             mock.patch("time.sleep", return_value=None), \
             mock.patch.object(sys, "argv", [str(SCRIPT), "http://fixture"]), \
             contextlib.redirect_stdout(output):
            with self.assertRaises(SystemExit) as exit_status:
                runpy.run_path(str(SCRIPT), run_name="__main__")
        return exit_status.exception.code, output.getvalue()

    def test_current_tenant_write_is_required_even_with_matching_history(self):
        code, output = self.run_fixture(True)
        self.assertEqual(1, code)
        self.assertIn("[FAIL] t2 当前告警写入成功", output)

    def test_first_tenant_read_must_succeed_before_claiming_absence(self):
        code, output = self.run_fixture(False, reject_first_tenant_list=True)
        self.assertEqual(1, code)
        self.assertIn("[FAIL] t1 本次告警列表读取成功", output)

    def test_current_tenant_alarm_identity_passes(self):
        code, output = self.run_fixture(False)
        self.assertEqual(0, code, output)
        self.assertIn("t2 能看到本次独有告警", output)


if __name__ == "__main__":
    unittest.main()
