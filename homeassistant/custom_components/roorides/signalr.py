"""A small SignalR (JSON protocol) client over aiohttp, for Roo's live-tracking hub.

Roo streams the bus's position from a SignalR hub at `livetrack.roorides.com/notify`. A parent
connects with their **user id as the access token** and their **child's `studentId`** in the
query; the server maps that to the child's currently-active trip and pushes `LiveTracking`
messages for just that bus — so there's no need to know the vehicle id first, and it works even
before the child boards (while a morning pickup bus is still on its way to the stop).

This is only as much of SignalR as that needs: negotiate, the JSON handshake, and reading
`LiveTracking` invocations. Roo's hub authenticates loosely (the "token" is just the user id).
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
import json
import logging
from typing import Any

import aiohttp

from .api import LIVE_URL, USER_AGENT

_LOGGER = logging.getLogger(__name__)

RECORD_SEP = "\x1e"
HANDSHAKE = json.dumps({"protocol": "json", "version": 1}) + RECORD_SEP
PING = json.dumps({"type": 6}) + RECORD_SEP
# The hub pushes the parent's bus under this target (note the capital L).
_TARGETS = ("LiveTracking", "liveTracking")


class RooLiveSocket:
    """Streams `LiveTracking` position messages for one child's bus until stopped."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        student_id: str | int,
        token: str | None,
        on_message: Callable[[list[Any]], Awaitable[None]],
    ) -> None:
        self._session = session
        self._student_id = student_id
        # The hub's "access token" is the parent's user id, not the API's JWT.
        self._token = str(token) if token else "0"
        self._on_message = on_message
        self._ws: aiohttp.ClientWebSocketResponse | None = None

    async def _negotiate(self) -> str | None:
        """Return the connection token, or None to connect without one."""
        url = f"{LIVE_URL}/notify/negotiate?negotiateVersion=1&access_token={self._token}"
        try:
            async with self._session.post(
                url, headers={"User-Agent": USER_AGENT}
            ) as resp:
                data = await resp.json(content_type=None)
        except (aiohttp.ClientError, json.JSONDecodeError) as err:
            _LOGGER.debug("Roo hub negotiate failed: %s", err)
            return None
        if isinstance(data, dict):
            return data.get("connectionToken") or data.get("connectionId")
        return None

    async def run(self) -> None:
        """Connect, handshake and read messages until the socket closes. Raises on failure."""
        conn = await self._negotiate()
        qs = f"studentId={self._student_id}&access_token={self._token}"
        if conn:
            qs += f"&id={conn}"
        url = f"{LIVE_URL}/notify?{qs}"
        async with self._session.ws_connect(
            url, headers={"User-Agent": USER_AGENT}, heartbeat=15, autoping=True
        ) as ws:
            self._ws = ws
            await ws.send_str(HANDSHAKE)
            async for msg in ws:
                if msg.type == aiohttp.WSMsgType.TEXT:
                    await self._handle(ws, msg.data)
                elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                    break
        self._ws = None

    async def _handle(self, ws: aiohttp.ClientWebSocketResponse, data: str) -> None:
        for record in data.split(RECORD_SEP):
            record = record.strip()
            if not record:
                continue
            try:
                obj = json.loads(record)
            except json.JSONDecodeError:
                continue
            kind = obj.get("type")
            if kind == 1 and obj.get("target") in _TARGETS:
                args = obj.get("arguments") or []
                # The hub sends the position list as the first argument.
                await self._on_message(args[0] if args else args)
            elif kind == 6:  # ping — keep the connection alive
                await ws.send_str(PING)

    async def close(self) -> None:
        if self._ws is not None and not self._ws.closed:
            await self._ws.close()
