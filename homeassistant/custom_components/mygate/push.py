"""Receives MyGate's push alerts through Firebase Cloud Messaging.

MyGate's server pushes visitor alerts to a token registered in its own Firebase project. We
register a token there the way Home Assistant's Ring integration does for Ring (as a web push
client, via the firebase-messaging library), and hand it to MyGate as this device's token.

MyGate's Firebase API key only accepts calls that name MyGate's Android app, so the Firebase
calls carry the app's package name and signing-certificate fingerprint, as the app's own do.
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable
import logging
from typing import Any
from urllib.parse import urlsplit

from aiohttp import ClientSession, ClientTimeout
from firebase_messaging import (
    FcmPushClient,
    FcmPushClientConfig,
    FcmPushClientRunState,
    FcmRegisterConfig,
)
from firebase_messaging.fcmregister import FcmRegister

from . import identity

_LOGGER = logging.getLogger(__name__)

FCM_CONFIG = FcmRegisterConfig(
    project_id=identity.FIREBASE_PROJECT_ID,
    app_id=identity.FIREBASE_APP_ID,
    api_key=identity.FIREBASE_API_KEY,
    messaging_sender_id=identity.FIREBASE_SENDER_ID,
)
ANDROID_APP_HEADERS = {
    "X-Android-Package": identity.ANDROID_PACKAGE,
    "X-Android-Cert": identity.ANDROID_CERT_SHA1,
}


class _AndroidAppSession:
    """Adds MyGate's app identity to Firebase API calls (not to the GCM/MCS ones)."""

    def __init__(self, session: ClientSession) -> None:
        self._session = session

    def post(self, url: str, headers: dict[str, str] | None = None, **kwargs: Any):
        if urlsplit(url).hostname.endswith(".googleapis.com"):
            headers = {**(headers or {}), **ANDROID_APP_HEADERS}
        return self._session.post(url, headers=headers, **kwargs)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._session, name)


def push_token(credentials: dict[str, Any]) -> str:
    return credentials["fcm"]["registration"]["token"]


_logged_key_format = False


def _b64_param(header: str, name: str) -> str:
    """One base64url parameter of a web-push `crypto-key`/`encryption` field, padded to decode."""
    for part in header.replace(",", ";").split(";"):
        key, _, value = part.strip().partition("=")
        if key == name:
            value = value.rstrip("=")
            return value + "=" * (-len(value) % 4)
    return ""


def _shape(header: str) -> str:
    """The field with each value replaced by its length, e.g. `dh=<87>;p256ecdsa=<87>`."""
    return ";".join(
        f"{key}=<{len(value)}>"
        for key, _, value in (part.strip().partition("=") for part in header.split(";"))
    )


class _FcmPushClient(FcmPushClient):
    """Reads a push's encryption fields in full.

    The library expects `crypto-key` to be exactly `dh=<padded key>` (and `encryption` exactly
    `salt=<padded salt>`), and stops for good when one isn't, e.g. unpadded or with a second
    parameter. Hand it just the padded value it decodes.
    """

    def _app_data_by_key(self, p: Any, key: str, do_not_raise: bool = False) -> str:
        global _logged_key_format  # noqa: PLW0603
        value = super()._app_data_by_key(p, key, do_not_raise)
        if key == "crypto-key":
            if not _logged_key_format:
                _logged_key_format = True
                _LOGGER.warning("MyGate push crypto-key format: %s", _shape(value))
            return "dh=" + _b64_param(value, "dh")
        if key == "encryption":
            return "salt=" + _b64_param(value, "salt")
        return value


async def async_register(session: ClientSession) -> dict[str, Any]:
    """Create a push registration in MyGate's Firebase project. Takes ~10 s."""
    reg = FcmRegister(FCM_CONFIG, http_client_session=_AndroidAppSession(session))
    # The library's 3 s default is too short for Google's register call.
    reg.CLIENT_TIMEOUT = ClientTimeout(total=20)

    checkin = await reg.gcm_check_in()
    if not checkin:
        raise RuntimeError("Google check-in failed")
    # Registering straight after a fresh check-in fails with PHONE_REGISTRATION_ERROR.
    await asyncio.sleep(5)
    gcm = None
    for attempt in range(4):
        if gcm := await reg.gcm_register(checkin, retries=1):
            break
        await asyncio.sleep(3 * (attempt + 1))
    if not gcm:
        raise RuntimeError("Google push registration failed")

    keys = reg.generate_keys()
    fcm = await reg.fcm_install_and_register(gcm, keys)
    if not fcm:
        raise RuntimeError("Firebase registration in MyGate's project failed")
    return {
        "keys": keys,
        "gcm": gcm,
        "fcm": fcm,
        "config": {
            "bundle_id": FCM_CONFIG.bundle_id,
            "project_id": FCM_CONFIG.project_id,
            "vapid_key": FCM_CONFIG.vapid_key,
        },
    }


class MyGatePushListener:
    """Keeps a connection open to Google's push servers and passes MyGate's data messages on.

    The library gives up after repeated connection errors; [check] restarts it.
    """

    def __init__(
        self,
        session: ClientSession,
        credentials: dict[str, Any],
        on_message: Callable[[dict[str, Any]], None],
        on_credentials: Callable[[dict[str, Any]], None],
    ) -> None:
        self._session = _AndroidAppSession(session)
        self._credentials = credentials
        self._on_message = on_message
        self._on_credentials = on_credentials
        self._client: FcmPushClient | None = None
        self._stopping = False
        # Google redelivers a message until it is acknowledged; keep the ids we've seen across
        # reconnects so a redelivery isn't treated as a new visitor.
        self._persistent_ids: list[str] = []

    @property
    def connected(self) -> bool:
        return bool(self._client and self._client.is_started())

    async def start(self) -> None:
        self._stopping = False
        client = _FcmPushClient(
            self._handle,
            FCM_CONFIG,
            self._credentials,
            self._credentials_updated,
            received_persistent_ids=list(self._persistent_ids),
            config=FcmPushClientConfig(abort_on_sequential_error_count=10),
            http_client_session=self._session,
        )
        await client.checkin_or_register()
        await client.start()
        self._client = client

    async def stop(self) -> None:
        self._stopping = True
        if self._client:
            await self._client.stop()
            self._client = None

    async def check(self) -> None:
        """Restart the client if it stopped on its own.

        After an unexpected error the library shuts itself down but stays STOPPING for good.
        """
        if self._stopping or (
            self._client
            and self._client.run_state
            not in (FcmPushClientRunState.STOPPING, FcmPushClientRunState.STOPPED)
        ):
            return
        _LOGGER.warning("MyGate push connection stopped; restarting it")
        await self.start()

    def _credentials_updated(self, credentials: dict[str, Any]) -> None:
        self._credentials = credentials
        self._on_credentials(credentials)

    def _handle(self, message: dict[str, Any], persistent_id: str, _context: Any) -> None:
        self._persistent_ids = [*self._persistent_ids[-49:], persistent_id]
        data = message.get("data") if isinstance(message, dict) else None
        if not isinstance(data, dict):
            _LOGGER.debug("Ignoring push without a data payload: %s", sorted(message))
            return
        self._on_message(data)
