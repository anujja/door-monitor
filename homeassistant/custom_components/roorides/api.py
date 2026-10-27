"""Client for Roo Commuter's parent API (api.roorides.com).

Worked out from the Roo Commuter Android app (com.nvszones.zones 2.6.0) and Roo's own web
bundle. Only the handful of parent-facing calls the tablet needs:

- sign in with the account's phone number and password (`grantType` "password");
- keep the session alive by refreshing the JWT rather than signing in again;
- list the children on the account;
- read a child's live tracking for the day, which carries the bus's position while the
  child's trip is underway.

The server hands out the bus only while the child's own trip is active; off-trip the live
arrays come back empty. Everything here is read-only apart from the sign-in itself.
"""

from __future__ import annotations

import asyncio
import base64
import binascii
from datetime import date
import json
import logging
from typing import Any

import aiohttp

_LOGGER = logging.getLogger(__name__)

API_URL = "https://api.roorides.com"
LIVE_URL = "https://livetrack.roorides.com"

# Resemble the app's Dart HTTP client. The API ignores it, but it keeps us looking ordinary.
USER_AGENT = "Dart/3.13 (dart:io)"
TIMEOUT = aiohttp.ClientTimeout(total=25)


class RooError(Exception):
    """Roo refused a request or could not be reached."""


class RooAuthError(RooError):
    """The sign-in was rejected, or the session is no longer valid."""


def jwt_claims(token: str) -> dict[str, Any]:
    """Decode a JWT's payload without verifying it — only for its `exp`, `CorpId`, etc."""
    try:
        part = token.split(".")[1]
        part += "=" * (-len(part) % 4)
        return json.loads(base64.urlsafe_b64decode(part))
    except (IndexError, ValueError, binascii.Error, json.JSONDecodeError):
        return {}


class RooApi:
    """A signed-in Roo parent account.

    Holds the access and refresh tokens and knows how to renew them. Not tied to Home
    Assistant, so the config flow and the hub can both use it.
    """

    def __init__(
        self,
        session: aiohttp.ClientSession,
        username: str,
        password: str,
        access_token: str | None = None,
        refresh_token: str | None = None,
    ) -> None:
        self._session = session
        self._username = username
        self._password = password
        self.access_token = access_token
        self.refresh_token = refresh_token

    # ── requests ─────────────────────────────────────────────────────────────────────────────

    async def _request(
        self, method: str, url: str, *, auth: bool = True, json_body: Any = None
    ) -> Any:
        if not url.startswith("http"):
            url = API_URL + url
        headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        if auth and self.access_token:
            headers["Authorization"] = f"Bearer {self.access_token}"
        try:
            async with self._session.request(
                method, url, headers=headers, json=json_body, timeout=TIMEOUT
            ) as resp:
                text = await resp.text()
                if resp.status in (401, 403):
                    raise RooAuthError(f"{resp.status} on {url}")
                if resp.status >= 400:
                    raise RooError(f"{resp.status} on {url}: {text[:200]}")
                if not text:
                    return None
                try:
                    return json.loads(text)
                except json.JSONDecodeError:
                    return text
        except (aiohttp.ClientError, asyncio.TimeoutError) as err:
            raise RooError(f"Could not reach Roo: {err!r}") from err

    async def _authed(self, method: str, url: str, *, json_body: Any = None) -> Any:
        """A request that renews the session once if the token has gone stale."""
        if not self.access_token:
            await self.login()
        try:
            return await self._request(method, url, json_body=json_body)
        except RooAuthError:
            _LOGGER.debug("Roo session rejected; signing in again")
            await self.login()
            return await self._request(method, url, json_body=json_body)

    # ── auth ─────────────────────────────────────────────────────────────────────────────────

    async def login(self) -> dict[str, Any]:
        """Sign in with the account password. Fills in the access and refresh tokens."""
        body = {"username": self._username, "password": self._password, "grantType": "password"}
        data = await self._request("POST", "/api/Login/UserLogin", auth=False, json_body=body)
        if not isinstance(data, dict):
            raise RooAuthError("Unexpected sign-in response")
        token = data.get("accessToken") or data.get("token")
        if not token:
            raise RooAuthError("Sign-in did not return a token")
        self.access_token = token
        self.refresh_token = data.get("refreshToken")
        return data

    async def refresh(self) -> None:
        """Renew the access token with the refresh token, falling back to a full sign-in."""
        if not self.refresh_token:
            await self.login()
            return
        body = {"accessToken": self.access_token, "refreshToken": self.refresh_token}
        try:
            data = await self._request("PUT", "/api/Login/Refresh/", auth=False, json_body=body)
        except RooError:
            _LOGGER.debug("Roo token refresh failed; signing in again")
            await self.login()
            return
        if isinstance(data, dict) and (token := data.get("accessToken") or data.get("token")):
            self.access_token = token
            self.refresh_token = data.get("refreshToken", self.refresh_token)
        else:
            await self.login()

    @property
    def user_id(self) -> str | None:
        claims = jwt_claims(self.access_token or "")
        uid = claims.get("UserId") or claims.get("userId")
        return str(uid) if uid is not None else None

    @property
    def token_expiry(self) -> int | None:
        """The access token's `exp`, Unix seconds, if present."""
        exp = jwt_claims(self.access_token or "").get("exp")
        return int(exp) if exp is not None else None

    # ── data ─────────────────────────────────────────────────────────────────────────────────

    async def students(self) -> list[dict[str, Any]]:
        """The children on this account."""
        if not (uid := self.user_id):
            await self.login()
            uid = self.user_id
        data = await self._authed("GET", f"/api/StudentList/{uid}")
        return data if isinstance(data, list) else []

    async def live_tracking(self, student_id: str | int, day: date | None = None) -> dict[str, Any]:
        """A child's live tracking for `day` (today by default).

        Returns the raw object: `transportActivities`, `liveTrackings` (the bus positions,
        populated only while the trip is active) and `configuration`.
        """
        day = day or date.today()
        data = await self._authed(
            "GET", f"/api/LiveTrackingV3/{student_id}/{day.isoformat()}"
        )
        return data if isinstance(data, dict) else {}

    async def trip_status(self, student_id: str | int) -> str | None:
        """The child's current trip status, e.g. "Pickup Ongoing" / "Drop Ongoing".

        Comes from the student list. The live-tracking arrays stay empty until the child is
        aboard the bus, but this field flips as soon as the trip starts — so it's how we tell a
        pickup or drop is underway even while the bus is still on its way to the stop.
        """
        for s in await self.students():
            if str(s.get("studentid") or s.get("studentId") or s.get("id")) == str(student_id):
                return s.get("tripStatus")
        return None

    async def trip_timeline(
        self, student_id: str | int, day: date | None = None
    ) -> list[dict[str, Any]]:
        """The child's trip milestones for `day` (started, reached landmark, boarded, …)."""
        day = day or date.today()
        data = await self._authed("GET", f"/api/StudentTripInfoV3/{student_id}/{day.isoformat()}")
        return data if isinstance(data, list) else []
