"""Small shared authentication helper for local verification and demo scripts.

The browser login endpoint intentionally keeps the JWT in the HttpOnly
``SOCP_SESSION`` cookie.  Command-line checks still need the same JWT when
they call service ports directly, so this module extracts the cookie without
making the login endpoint expose bearer credentials in its JSON response.
"""

from __future__ import annotations

import http.cookies
import json
import urllib.error
import urllib.request


SESSION_COOKIE = "SOCP_SESSION"


def _cookie_token(headers) -> str | None:
    values = []
    if hasattr(headers, "get_all"):
        values = headers.get_all("Set-Cookie") or []
    elif hasattr(headers, "getheaders"):
        values = headers.getheaders("Set-Cookie") or []
    elif isinstance(headers, dict):
        value = headers.get("Set-Cookie") or headers.get("set-cookie")
        values = [value] if value else []
    for value in values:
        jar = http.cookies.SimpleCookie()
        jar.load(value)
        morsel = jar.get(SESSION_COOKIE)
        if morsel and morsel.value:
            return morsel.value
    return None


def login_token(gateway: str, username: str = "demo", password: str = "demo123",
                timeout: float = 15) -> str:
    """Authenticate and return the JWT from the session cookie.

    A legacy JSON ``token`` field is accepted for compatibility with older
    gateways, but failed login is always raised; callers must not silently use
    a fake token because that masks a broken authentication contract.
    """
    request = urllib.request.Request(
        gateway.rstrip("/") + "/auth/login",
        data=json.dumps({"username": username, "password": password}).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
            body = json.loads(raw) if raw.strip() else {}
            token = _cookie_token(response.headers)
            if not token and isinstance(body, dict):
                token = body.get("token")
            if response.status != 200 or not token:
                raise RuntimeError(f"login returned HTTP {response.status} without session token")
            return token
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        try:
            body = json.loads(raw) if raw.strip() else {}
        except json.JSONDecodeError:
            body = raw[:200]
        raise RuntimeError(f"login failed HTTP {error.code}: {body}") from error
