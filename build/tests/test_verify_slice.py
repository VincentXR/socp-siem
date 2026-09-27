"""The tenant slice must reject a failed current write even with old matching data."""

import base64
import contextlib
import hashlib
import io
import json
import os
import runpy
import sys
import types
import unittest
import urllib.request
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "verify-slice.py"
PREPARE_CREDENTIALS = Path(__file__).resolve().parents[1] / "prepare-ci-credentials.py"


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
        self.tokens = {}

    def urlopen(self, request, timeout=10):
        del timeout
        tenant = next((value for key, value in request.header_items()
                       if key.lower() == "x-tenant-id"), "t1")
        authorization = request.get_header("Authorization")
        if authorization is None:
            return Response(401, {"code": 401, "message": "unauthorized", "traceId": "trace-1"})
        self.tokens.setdefault(tenant, authorization.removeprefix("Bearer "))
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
    def run_fixture(self, reject_second_tenant, reject_first_tenant_list=False,
                    signing_jwk=""):
        fixture = SliceFixture(reject_second_tenant, reject_first_tenant_list)
        output = io.StringIO()
        fake_auth = types.SimpleNamespace(login_token=lambda *_args, **_kwargs: "fixture-token")
        with mock.patch.dict(sys.modules, {"auth_client": fake_auth}), \
             mock.patch.dict(os.environ, {
                 "SOCP_AUTH_SIGNING_JWK": signing_jwk,
                 "SOCP_AUTH_ISSUER": "https://ci.socp.invalid",
                 "SOCP_JWT_SECRET": "s" * 32,
                 "SOCP_LOGIN_SECRET": "s" * 32,
             }), \
             mock.patch.object(urllib.request, "urlopen", fixture.urlopen), \
             mock.patch("time.sleep", return_value=None), \
             mock.patch.object(sys, "argv", [str(SCRIPT), "http://fixture"]), \
             contextlib.redirect_stdout(output):
            with self.assertRaises(SystemExit) as exit_status:
                runpy.run_path(str(SCRIPT), run_name="__main__")
        return exit_status.exception.code, output.getvalue(), fixture

    def test_current_tenant_write_is_required_even_with_matching_history(self):
        code, output, _ = self.run_fixture(True)
        self.assertEqual(1, code)
        self.assertIn("[FAIL] t2 当前告警写入成功", output)

    def test_first_tenant_read_must_succeed_before_claiming_absence(self):
        code, output, _ = self.run_fixture(False, reject_first_tenant_list=True)
        self.assertEqual(1, code)
        self.assertIn("[FAIL] t1 本次告警列表读取成功", output)

    def test_current_tenant_alarm_identity_passes(self):
        code, output, _ = self.run_fixture(False)
        self.assertEqual(0, code, output)
        self.assertIn("t2 能看到本次独有告警", output)

    def test_disposable_rsa_jwk_mints_verifiable_rs256_tenant_token(self):
        generator = runpy.run_path(
            str(PREPARE_CREDENTIALS), run_name="credential_fixture"
        )["generate_rsa_jwk"]
        signing_jwk = generator()
        code, output, fixture = self.run_fixture(False, signing_jwk=signing_jwk)
        self.assertEqual(0, code, output)

        token = fixture.tokens["t1"]
        header_part, payload_part, signature_part = token.split(".")
        def decode(value):
            return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))

        header = json.loads(decode(header_part))
        payload = json.loads(decode(payload_part))
        jwk = json.loads(signing_jwk)
        self.assertEqual({"alg": "RS256", "typ": "JWT", "kid": jwk["kid"]}, header)
        self.assertEqual("https://ci.socp.invalid", payload["iss"])
        self.assertEqual("t1", payload["tenant"])

        modulus = int.from_bytes(decode(jwk["n"]), "big")
        exponent = int.from_bytes(decode(jwk["e"]), "big")
        size = (modulus.bit_length() + 7) // 8
        actual = pow(int.from_bytes(decode(signature_part), "big"), exponent, modulus)
        encoded_signature = actual.to_bytes(size, "big")
        digest_info = bytes.fromhex("3031300d060960864801650304020105000420")
        expected_tail = digest_info + hashlib.sha256(
            f"{header_part}.{payload_part}".encode()
        ).digest()
        self.assertTrue(encoded_signature.startswith(b"\x00\x01\xff"))
        self.assertTrue(encoded_signature.endswith(b"\x00" + expected_tail))


if __name__ == "__main__":
    unittest.main()
