"""Client for MyGate's resident API, sending what the MyGate Android app sends.

Worked out from the decompiled app (7.39.0). Each request matches the app's: the same endpoints,
JSON bodies in the same field order and Gson encoding, the same headers in OkHttp's order, over
HTTP/2. The device it reports is the resident's own phone (see identity.py), signed in as one more
device on their account.
"""

from __future__ import annotations

import hashlib
import json
import logging
import secrets
import time
from typing import Any
import uuid

import httpx

from homeassistant.core import HomeAssistant
from homeassistant.helpers.httpx_client import create_async_httpx_client
from homeassistant.util.ssl import SSL_ALPN_HTTP11_HTTP2

# The phone the app would be running on: MyGate's version on it, Build.MODEL, Build.VERSION.SDK_INT
# and TelephonyManager.getNetworkOperatorName().
from .identity import APP_VERSION, PHONE_CARRIER, PHONE_MODEL, PHONE_SDK

_LOGGER = logging.getLogger(__name__)

API_URL = "https://app.mygate.in"
ACK_URL = "https://ack.mygate.in/acknowledge/v1/notification"

NETWORK_TYPE = "Wi-Fi"  # ConnectivityUtil.a() on Wi-Fi

USER_AGENT = "okhttp/4.12.0"
TIMEOUT = 20

# Gson escapes these inside strings by default (its "HTML-safe" mode).
_GSON_ESCAPES = str.maketrans(
    {"<": "\\u003c", ">": "\\u003e", "&": "\\u0026", "=": "\\u003d", "'": "\\u0027",
     " ": "\\u2028", " ": "\\u2029"}
)

_CLIENT_KEY = "mygate_http_client"


def async_get_client(hass: HomeAssistant) -> httpx.AsyncClient:
    """One HTTP/2 client for all MyGate calls, as the app's OkHttp would use."""
    if (client := hass.data.get(_CLIENT_KEY)) is None:
        client = hass.data[_CLIENT_KEY] = create_async_httpx_client(
            hass, alpn_protocols=SSL_ALPN_HTTP11_HTTP2, http2=True
        )
    return client


def new_device_id() -> str:
    """A device id as the app makes one: the name-based UUID of the phone's Android ID."""
    android_id = secrets.token_hex(8)
    return str(uuid.UUID(bytes=hashlib.md5(android_id.encode()).digest(), version=3))


def _without_nulls(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: _without_nulls(v) for k, v in value.items() if v is not None}
    if isinstance(value, list):
        return [_without_nulls(v) for v in value]
    return value


def gson_json(body: dict[str, Any]) -> bytes:
    """Encode like the app's Gson: compact, null fields left out, HTML characters escaped."""
    text = json.dumps(_without_nulls(body), separators=(",", ":"), ensure_ascii=False)
    return text.translate(_GSON_ESCAPES).encode()


class MyGateError(Exception):
    """MyGate refused a request or could not be reached."""


class MyGateAuthError(MyGateError):
    """The session key is no longer valid; sign in again."""


class MyGateApi:
    """One signed-in device on a MyGate account."""

    def __init__(self, client: httpx.AsyncClient, device_id: str, api_key: str | None = None) -> None:
        self._client = client
        self.device_id = device_id
        self.api_key = api_key

    def _headers(self, content: bytes | None) -> list[tuple[str, str]]:
        """The app's headers, in the order its interceptor and OkHttp add them."""
        headers = [("access-key", self.api_key)] if self.api_key else []
        headers += [
            ("accept", "application/json"),
            ("app-client", "N"),
            ("app-version", APP_VERSION),
            ("app-client-os-version", str(PHONE_SDK)),
        ]
        if content is not None:
            headers += [
                ("content-type", "application/json; charset=UTF-8"),
                ("content-length", str(len(content))),
            ]
        return headers + [("accept-encoding", "gzip"), ("user-agent", USER_AGENT)]

    async def _call(self, method: str, url: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        if not url.startswith("http"):
            url = API_URL + url
        content = gson_json(body) if body is not None else None
        # Built by hand and sent as is, so the client adds none of its default headers.
        request = httpx.Request(
            method,
            url,
            headers=self._headers(content),
            content=content,
            extensions={"timeout": httpx.Timeout(TIMEOUT).as_dict()},
        )
        try:
            resp = await self._client.send(request)
            if resp.status_code == 401:
                raise MyGateAuthError("MyGate rejected the session key")
            data = resp.json()
            status = resp.status_code
        except (httpx.HTTPError, ValueError) as err:
            raise MyGateError(f"{method} {url.split('?')[0]} failed: {err}") from err

        if not isinstance(data, dict):
            raise MyGateError(f"{url} returned {type(data).__name__}, expected an object")
        # The app treats es=1 as an error (with a message) and es=2 as "no data".
        if status >= 400 or str(data.get("es")) == "1":
            raise MyGateError(data.get("message") or f"HTTP {status}")
        return data

    async def request_otp(self, phone: str, country_code: str) -> None:
        """Text a sign-in OTP to the resident's phone.

        The account lookup itself sends the OTP. The app follows it with /user/v3/forgetpassword,
        but that sends a second code and the first one then fails with "OTP does not match".
        """
        data = await self._call(
            "POST",
            "/auth/v2/user/validate",
            {"uname": phone, "country_code": country_code, "device_type": "N"},
        )
        if not data.get("is_existing_user"):
            raise MyGateError("No MyGate account uses this phone number")

    async def login(self, phone: str, country_code: str, otp: str, push_token: str) -> dict[str, Any]:
        """Sign in with the OTP. Returns the profile, including `api_key`, `userid`, `flatid`."""
        data = await self._call(
            "POST",
            "/auth/v2/login",
            {
                "username": phone,
                "country_code": country_code,
                "version": 3,
                "password": otp,
                "deviceid": self.device_id,
                "type": "N",
                "carrier_name": PHONE_CARRIER,
                "osversion": PHONE_SDK,
                "model": PHONE_MODEL,
                "appversion": APP_VERSION,
                "gcmid": push_token,
            },
        )
        if not data.get("api_key"):
            raise MyGateError(data.get("message") or "Sign-in returned no session key")
        self.api_key = data["api_key"]
        return data

    async def register_push_token(self, user_id: str, push_token: str) -> None:
        """Tell MyGate where to push this device's alerts. The app repeats this daily."""
        await self._call(
            "POST",
            "/user/v3/gcm",
            {
                "userid": user_id,
                "deviceid": self.device_id,
                "gcmid": push_token,
                "type": "N",
                "appversion": APP_VERSION,
            },
        )

    async def send_test_alert(self, user_id: str, flat_id: str | None, push_token: str) -> None:
        """Ask MyGate to push its "Visitor Notification Test" to this device only."""
        await self._call(
            "POST",
            "/notif/v2/eintercom/test/notification",
            {
                "userid": user_id,
                "flatid": flat_id or "-1",
                "gcmid": push_token,
                "deviceid": self.device_id,
            },
        )

    async def acknowledge(
        self, user_id: str, push_token: str, notification_id: str, attempt: str
    ) -> None:
        """Confirm an alert arrived, as the app does when the push asks for it."""
        await self._call(
            "POST",
            ACK_URL,
            {
                "ack": [
                    {
                        "userid": user_id,
                        "gcmid": push_token,
                        "deviceid": self.device_id,
                        "notification_id": notification_id,
                        "notification_attempt": attempt,
                        "appversion": APP_VERSION,
                        "acknowledgement_time": int(time.time() * 1000),
                        "carrier_name": PHONE_CARRIER,
                    }
                ]
            },
        )

    async def respond(
        self,
        reply_to: str,
        *,
        user_id: str,
        flat_id: str | None,
        validation_id: str,
        visit_id: str | None,
        notification_id: str | None,
        status: str,
        invited: bool = False,
    ) -> None:
        """Approve ("A"), deny ("D") or leave at gate ("L") a visitor waiting at the gate.

        `reply_to` comes from the alert itself; the app posts there rather than to a fixed path.
        `invited`: the alert carried `invite_validation`, for which the app adds its device id.
        """
        await self._call(
            "POST",
            reply_to,
            {
                "action_screen": "NOTIFICATION",
                "device_number": self.device_id,
                "network_type": NETWORK_TYPE,
                "notification_id": notification_id,
                "userid": user_id,
                "flatid": flat_id,
                "deviceid": self.device_id if invited else None,
                "validationid": validation_id.strip(),
                "visitId": visit_id,
                "valstatus": status,
            },
        )

    async def logout(self, user_id: str) -> None:
        """Remove this device from the account so MyGate stops pushing to it."""
        await self._call("POST", "/user/v3/logout", {"deviceid": self.device_id, "userid": user_id})
