"""Turns MyGate push alerts into MQTT messages and sends approve/deny responses back."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from functools import partial
import json
import logging
import time
from typing import Any

from homeassistant.components import mqtt
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import CALLBACK_TYPE, HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.event import async_call_later, async_track_time_interval
from homeassistant.util import dt as dt_util

from .api import MyGateApi, MyGateAuthError, MyGateError, async_get_client
from .const import (
    CONF_API_KEY,
    CONF_DEVICE_ID,
    CONF_FCM_CREDENTIALS,
    CONF_FLAT_ID,
    CONF_TOPIC,
    CONF_USER_ID,
    DEFAULT_TOPIC,
    RESPONSE_CODES,
    RESPONSE_LEAVE_AT_GATE,
    SIGNAL_PUSH_STATE,
    TYPE_APPROVAL,
    TYPE_EMERGENCY,
    TYPE_GENERAL,
    TYPE_VEHICLE,
)
from .push import MyGatePushListener, push_token

_LOGGER = logging.getLogger(__name__)

# click_action values the app shows as a gate approval (see FcmMessagingHandler in the app).
APPROVAL_ACTIONS = {
    "com.mygate.user.action.mygate_validate_visitor",
    "com.mygate.user.action.mygate_kid",
    "test_mygate_validate_visitor",
}
# Sent when someone else (another resident, or the guard) has answered an approval.
STATUS_ACTIONS = {"com.mygate.user.action.mygate_validate_visitor_status"}
TEST_ACTION = "test_mygate_validate_visitor"

DEFAULT_KEEP_ALIVE = 60
HEALTH_INTERVAL = timedelta(minutes=1)
# Also how soon we notice MyGate signing us out, after which it silently stops sending alerts.
TOKEN_REFRESH_INTERVAL = timedelta(hours=1)

# guest.usertypeid → what the app calls the visitor.
VISITOR_TYPES = {"1213": "guest", "1008": "delivery", "1022": "cab"}


@dataclass
class PendingAlert:
    """An approval that is still waiting for an answer."""

    id: str
    payload: dict[str, Any]
    data: dict[str, Any]
    responses: list[str]
    test: bool
    expire: CALLBACK_TYPE | None = field(default=None, repr=False)


def _num(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _epoch_ms(value: Any) -> int | None:
    """MyGate timestamps are epoch milliseconds; accept seconds too."""
    n = _num(value)
    if n is None or n <= 0:
        return None
    return int(n if n > 1e12 else n * 1000)


def _text(data: dict[str, Any], *keys: str) -> str | None:
    for key in keys:
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _classify(data: dict[str, Any]) -> str:
    action = str(data.get("click_action") or "")
    if action in APPROVAL_ACTIONS:
        return TYPE_APPROVAL
    hints = " ".join(
        str(data.get(k) or "")
        for k in ("click_action", "android_channel_id", "notification_type", "MESSAGE_TYPE", "alert_type")
    ).lower()
    if "emergency" in hints or "sos" in hints.split("_"):
        return TYPE_EMERGENCY
    if "vehicle" in hints:
        return TYPE_VEHICLE
    if data.get("MESSAGE_TYPE") == "VISITOR_VALIDATION" and data.get("replyto"):
        return TYPE_APPROVAL
    return TYPE_GENERAL


class MyGateHub:
    """Owns the push connection, the pending approvals and the MQTT side for one account."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self.api = MyGateApi(
            async_get_client(hass), entry.data[CONF_DEVICE_ID], entry.data[CONF_API_KEY]
        )
        self.topic = entry.data.get(CONF_TOPIC, DEFAULT_TOPIC).rstrip("/")
        self.pending: dict[str, PendingAlert] = {}
        self.last_alert: datetime | None = None
        self._listener = MyGatePushListener(
            async_get_clientsession(hass),
            entry.data[CONF_FCM_CREDENTIALS],
            self._on_push,
            self._on_credentials,
        )
        self._unsubs: list[CALLBACK_TYPE] = []
        self._was_connected = False
        self._signed_out = False

    @property
    def user_id(self) -> str:
        return self.entry.data[CONF_USER_ID]

    @property
    def flat_id(self) -> str | None:
        return self.entry.data.get(CONF_FLAT_ID) or None

    @property
    def push_token(self) -> str:
        return push_token(self.entry.data[CONF_FCM_CREDENTIALS])

    @property
    def connected(self) -> bool:
        """Whether MyGate's alerts can reach us: push is up and MyGate hasn't signed us out."""
        return self._listener.connected and not self._signed_out

    # ── lifecycle ────────────────────────────────────────────────────────────────────────────

    async def async_start(self) -> None:
        await self._refresh_token_registration()
        try:
            await self._listener.start()
        except Exception as err:  # noqa: BLE001 - the health check retries
            _LOGGER.warning("Could not connect to MyGate push yet (%s); will retry", err)
        self._unsubs.append(
            async_track_time_interval(self.hass, self._health_check, HEALTH_INTERVAL)
        )
        self._unsubs.append(
            async_track_time_interval(
                self.hass, self._refresh_token_registration, TOKEN_REFRESH_INTERVAL
            )
        )
        if await mqtt.async_wait_for_mqtt_client(self.hass):
            self._unsubs.append(
                await mqtt.async_subscribe(self.hass, f"{self.topic}/respond", self._on_respond_command)
            )
        else:
            _LOGGER.error("MQTT is not available; MyGate alerts will not be published")

    async def async_stop(self) -> None:
        for unsub in self._unsubs:
            unsub()
        self._unsubs.clear()
        for alert in self.pending.values():
            if alert.expire:
                alert.expire()
        self.pending.clear()
        await self._listener.stop()

    async def _health_check(self, _now: datetime | None = None) -> None:
        try:
            await self._listener.check()
        except Exception as err:  # noqa: BLE001
            _LOGGER.warning("MyGate push reconnect failed: %s", err)
        self._update_state()

    @callback
    def _update_state(self) -> None:
        if self.connected != self._was_connected:
            self._was_connected = self.connected
            async_dispatcher_send(self.hass, f"{SIGNAL_PUSH_STATE}_{self.entry.entry_id}")

    @callback
    def _on_signed_out(self) -> None:
        """MyGate rejected our session key. It won't send us alerts until we sign in again."""
        if self._signed_out:
            return
        self._signed_out = True
        _LOGGER.error("MyGate signed this device out; sign in again from the integration")
        self.entry.async_start_reauth(self.hass)
        self._update_state()

    async def _refresh_token_registration(self, _now: datetime | None = None) -> None:
        """Re-send the push token, as the app does when it starts. A 401 means we were signed out."""
        if self._signed_out:
            return  # signing in again reloads the entry with a fresh hub
        try:
            await self.api.register_push_token(self.user_id, self.push_token)
        except MyGateAuthError:
            self._on_signed_out()
        except MyGateError as err:
            _LOGGER.warning("Could not refresh MyGate push registration: %s", err)

    @callback
    def _on_credentials(self, credentials: dict[str, Any]) -> None:
        self.hass.config_entries.async_update_entry(
            self.entry, data={**self.entry.data, CONF_FCM_CREDENTIALS: credentials}
        )

    # ── incoming alerts ──────────────────────────────────────────────────────────────────────

    @callback
    def _on_push(self, data: dict[str, Any]) -> None:
        self.hass.async_create_task(self._handle_push(data), "mygate push")

    async def _handle_push(self, data: dict[str, Any]) -> None:
        _LOGGER.debug(
            "Push: click_action=%s MESSAGE_TYPE=%s notification_type=%s channel=%s keys=%s",
            data.get("click_action"),
            data.get("MESSAGE_TYPE"),
            data.get("notification_type"),
            data.get("android_channel_id"),
            sorted(data),
        )
        if data.get("send_acknowledgement") == "1" and data.get("notification_id"):
            self.hass.async_create_task(self._acknowledge(data), "mygate ack")

        now_ms = int(time.time() * 1000)
        expiry = _epoch_ms(data.get("expiry_time"))
        if expiry and expiry < now_ms:
            _LOGGER.debug("Dropping expired push")
            return
        if (
            data.get("is_silent_notification") in ("1", "true")
            or data.get("action") == "refresh"
            or not _text(data, "title", "body")
        ):
            _LOGGER.debug("Ignoring silent or empty push")
            return

        if data.get("click_action") in STATUS_ACTIONS:
            await self._handle_status(data)
            return

        event_type = _classify(data)
        created = _epoch_ms(data.get("crtTime")) or _epoch_ms(data.get("time")) or now_ms
        keep_alive = int(_num(data.get("keep_alive")) or 0)
        if event_type == TYPE_APPROVAL and not keep_alive:
            keep_alive = DEFAULT_KEEP_ALIVE
        if keep_alive and created + keep_alive * 1000 < now_ms:
            _LOGGER.debug("Dropping push older than its keep_alive")
            return

        test = data.get("click_action") == TEST_ACTION
        alert_id = (
            _text(data, "validationid", "notification_id")
            or f"{data.get('click_action', 'mygate')}-{created}"
        )
        existing = self.pending.get(alert_id)
        responses = self._responses_for(data) if event_type == TYPE_APPROVAL else []
        payload = self._payload(data, event_type, alert_id, created, keep_alive, responses, test)
        payload["update"] = existing is not None
        self.last_alert = dt_util.utcnow()
        await self._publish(f"{self.topic}/notification", payload)

        if event_type == TYPE_APPROVAL and responses:
            if existing and existing.expire:
                existing.expire()
            alert = PendingAlert(alert_id, payload, data, responses, test)
            remaining = max(1.0, (created + keep_alive * 1000 - now_ms) / 1000)
            alert.expire = async_call_later(self.hass, remaining, partial(self._expire, alert_id))
            self.pending[alert_id] = alert

    async def _handle_status(self, data: dict[str, Any]) -> None:
        """Someone else answered: close our pending approval and pass the outcome on quietly."""
        alert_id = _text(data, "validationid")
        if alert_id and (alert := self.pending.pop(alert_id, None)):
            if alert.expire:
                alert.expire()
            await self._publish_removed(alert, "answered_elsewhere")
        created = _epoch_ms(data.get("crtTime")) or int(time.time() * 1000)
        payload = self._payload(data, TYPE_GENERAL, alert_id or f"status-{created}", created, 0, [], False)
        payload["update"] = True  # an outcome, not a new visitor: no chime on the tablet
        await self._publish(f"{self.topic}/notification", payload)

    @callback
    def _expire(self, alert_id: str, _now: datetime | None = None) -> None:
        if alert := self.pending.pop(alert_id, None):
            self.hass.async_create_task(self._publish_removed(alert, "timeout"), "mygate expire")

    def _responses_for(self, data: dict[str, Any]) -> list[str]:
        test = data.get("click_action") == TEST_ACTION
        if not test and not (data.get("replyto") and data.get("validationid")):
            return []
        responses = ["approve", "deny"]
        if data.get("delegate_delivery") == "1":
            responses.append(RESPONSE_LEAVE_AT_GATE)
        return responses

    def _payload(
        self,
        data: dict[str, Any],
        event_type: str,
        alert_id: str,
        created: int,
        keep_alive: int,
        responses: list[str],
        test: bool,
    ) -> dict[str, Any]:
        guest: dict[str, Any] = {}
        if isinstance(data.get("guest"), str):
            try:
                guest = json.loads(data["guest"])
            except ValueError:
                guest = {}
        # Field meanings follow MyGate's own approval screen (NotificationResponseActivity):
        # the photo is the alert's `image`, and guest.vaddress is where the visitor is from
        # ("Swiggy", "Amazon"…), shown next to `company_logo`.
        visitor = {
            "name": _text(guest, "name") or _text(data, "visitor_title"),
            "company": _text(guest, "vaddress"),
            "company_logo": _text(data, "company_logo") or _text(guest, "company_logo"),
            "image": _text(data, "image") or _text(guest, "image") or _text(data, "big_img", "small_img"),
            "type": VISITOR_TYPES.get(str(guest.get("usertypeid")), guest.get("usertypeid")),
            "nature": _text(guest, "nature"),
        }
        actions = [a for a in (_text(data, "action1"), _text(data, "action2")) if a]
        if RESPONSE_LEAVE_AT_GATE in responses:
            actions.append("Leave at gate")
        return {
            "event_type": event_type,
            "key": f"mygate:{alert_id}",
            "id": alert_id,
            "postTime": created,
            "time": dt_util.as_local(dt_util.utc_from_timestamp(created / 1000)).isoformat(),
            "title": _text(data, "title"),
            "text": _text(data, "body"),
            "bigText": None,
            "subText": _text(data, "subtitle", "visitor_subtitle"),
            "actions": actions,
            "channel": data.get("android_channel_id"),
            "category": data.get("click_action"),
            "timeoutMs": keep_alive * 1000,
            "update": False,
            "visitor": visitor if any(visitor.values()) else None,
            "responses": responses,
            "test": test,
            "source": "home_assistant",
        }

    async def _acknowledge(self, data: dict[str, Any]) -> None:
        try:
            await self.api.acknowledge(
                self.user_id,
                self.push_token,
                str(data["notification_id"]),
                str(data.get("notification_attempt", "0")),
            )
        except MyGateError as err:
            _LOGGER.debug("MyGate delivery acknowledgement failed: %s", err)

    # ── responses ────────────────────────────────────────────────────────────────────────────

    async def async_respond(self, response: str, alert_id: str | None = None) -> None:
        """Answer a pending approval; with no id, the most recent one.

        Every attempt is reported on `<topic>/response` (`ok`, and `error` when it failed), so
        whoever asked over MQTT — the tablet's card — can tell a failure from a slow reply.
        """
        if alert_id is None and self.pending:
            alert_id = next(reversed(self.pending))
        try:
            await self._respond(response, alert_id)
        except HomeAssistantError as err:
            await self._publish(
                f"{self.topic}/response",
                {"id": alert_id, "action": response, "ok": False, "error": str(err)},
            )
            raise
        await self._publish(
            f"{self.topic}/response", {"id": alert_id, "action": response, "ok": True}
        )

    async def _respond(self, response: str, alert_id: str | None) -> None:
        if response not in RESPONSE_CODES:
            raise ServiceValidationError(f"Unknown response {response!r}")
        if alert_id is None:
            if not self.pending:
                raise ServiceValidationError("No MyGate visitor is waiting for an answer")
            alert_id = next(reversed(self.pending))
        alert = self.pending.get(alert_id)
        if alert is None:
            raise ServiceValidationError(f"No pending MyGate visitor with id {alert_id}")
        if response not in alert.responses:
            raise ServiceValidationError(f"{response} is not offered for this visitor")

        if not alert.test:
            # MyGate's own test alert is answered on the phone without telling the server.
            data = alert.data
            try:
                await self.api.respond(
                    data["replyto"],
                    user_id=self.user_id,
                    flat_id=_text(data, "flat_id", "flatid", "flatId") or self.flat_id,
                    validation_id=data["validationid"],
                    visit_id=_text(data, "visitId"),
                    notification_id=_text(data, "notification_id"),
                    status=RESPONSE_CODES[response],
                    invited=bool(data.get("invite_validation")),
                )
            except MyGateAuthError as err:
                self._on_signed_out()
                raise HomeAssistantError("MyGate signed this device out; sign in again") from err
            except MyGateError as err:
                raise HomeAssistantError(f"MyGate did not accept the {response}: {err}") from err

        _LOGGER.info("Answered MyGate visitor %s: %s%s", alert_id, response, " (test)" if alert.test else "")
        self.pending.pop(alert_id, None)
        if alert.expire:
            alert.expire()
        await self._publish_removed(alert, "responded", response)

    @callback
    def _on_respond_command(self, msg: mqtt.ReceiveMessage) -> None:
        self.hass.async_create_task(self._respond_command(msg), "mygate respond command")

    async def _respond_command(self, msg: mqtt.ReceiveMessage) -> None:
        """`<topic>/respond`: "approve" / "deny" / "leave_at_gate", or {"action": …, "id": …}."""
        raw = msg.payload.strip() if isinstance(msg.payload, str) else msg.payload.decode().strip()
        alert_id = None
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = raw
        if isinstance(parsed, dict):
            response, alert_id = parsed.get("action"), parsed.get("id")
        else:
            response = str(parsed)
        try:
            await self.async_respond(str(response).lower(), alert_id)
        except HomeAssistantError as err:
            _LOGGER.warning("MyGate respond command %r failed: %s", raw[:80], err)

    # ── MQTT ─────────────────────────────────────────────────────────────────────────────────

    async def _publish_removed(self, alert: PendingAlert, reason: str, response: str | None = None) -> None:
        payload = {**alert.payload, "reason": reason, "response": response}
        await self._publish(f"{self.topic}/notification_removed", payload)

    async def _publish(self, topic: str, payload: dict[str, Any]) -> None:
        try:
            await mqtt.async_publish(self.hass, topic, json.dumps(payload), qos=1, retain=False)
        except HomeAssistantError as err:
            _LOGGER.error("Could not publish MyGate alert to %s: %s", topic, err)

    # ── testing ──────────────────────────────────────────────────────────────────────────────

    async def async_send_test_alert(self) -> None:
        try:
            await self.api.send_test_alert(self.user_id, self.flat_id, self.push_token)
        except MyGateAuthError as err:
            self._on_signed_out()
            raise HomeAssistantError("MyGate signed this device out; sign in again") from err
        except MyGateError as err:
            raise HomeAssistantError(f"MyGate refused the test alert: {err}") from err
