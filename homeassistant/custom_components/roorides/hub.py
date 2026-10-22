"""The running Roo account: polls live tracking, follows the bus, and publishes it.

While a child's trip is underway the hub polls `LiveTrackingV3` often and, when it learns the
bus's `vehicleId`, also opens the live-tracking socket for smoother updates. Off-trip it polls
slowly. Every update is pushed to the integration's entities and published on MQTT, so the
tablet (and anything else) can follow the bus by subscribing — the project's MQTT-first habit.
"""

from __future__ import annotations

import asyncio
from dataclasses import asdict, dataclass
from datetime import date
import json
import logging
import time
from typing import Any

from homeassistant.components import mqtt
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.event import async_call_later

from .api import RooApi, RooError
from .const import (
    CONF_CORP_ID,
    CONF_PASSWORD,
    CONF_STUDENT_ID,
    CONF_STUDENT_NAME,
    CONF_TOPIC,
    CONF_USE_SOCKET,
    CONF_USERNAME,
    DEFAULT_TOPIC,
    POLL_ACTIVE,
    POLL_IDLE,
    SIGNAL_UPDATE,
    TOKEN_REFRESH_MARGIN,
)
from .signalr import RooLiveSocket

_LOGGER = logging.getLogger(__name__)

# Case-insensitive field synonyms. Roo's exact casing is confirmed from the first live payload,
# which the hub logs once; until then we match generously so the bus still shows.
_LAT = ("latitude", "lat")
_LNG = ("longitude", "lng", "long", "lon")
_VID = ("vehicleid", "vehicle_id")
_SPEED = ("speed",)
_ADDR = ("address", "currentaddress", "fulladdress")
_WHEN = ("devicedatetime", "datetime", "timestamp", "time")
_BUS = ("busnumber", "vehiclenumber", "vehicle_reg_no", "vehicleregno", "busno")
_DRIVER = ("drivername", "driver_name", "driver")
_ETA = ("eta",)
_ETA_UNIT = ("etaunit",)
_ETA_MSG = ("etaextramessage", "message")


def _get(obj: dict[str, Any], names: tuple[str, ...]) -> Any:
    low = {k.lower(): v for k, v in obj.items()}
    for n in names:
        if low.get(n) not in (None, ""):
            return low[n]
    return None


def _num(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


@dataclass
class BusState:
    """The latest known state of the child's bus."""

    active: bool = False
    latitude: float | None = None
    longitude: float | None = None
    vehicle_id: str | None = None
    bus_number: str | None = None
    driver_name: str | None = None
    speed: float | None = None
    address: str | None = None
    eta: str | None = None
    eta_unit: str | None = None
    eta_message: str | None = None
    status: str | None = None
    device_datetime: str | None = None
    updated: float | None = None


class RooHub:
    """One Roo account following one child's bus."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self.student_id = str(entry.data[CONF_STUDENT_ID])
        self.student_name = entry.data.get(CONF_STUDENT_NAME) or "Bus"
        self.corp_id = entry.data.get(CONF_CORP_ID)
        self.topic = (entry.data.get(CONF_TOPIC) or DEFAULT_TOPIC).rstrip("/")
        self.use_socket = entry.data.get(CONF_USE_SOCKET, True)
        self.api = RooApi(
            async_get_clientsession(hass),
            entry.data[CONF_USERNAME],
            entry.data[CONF_PASSWORD],
        )
        self.state = BusState()
        self._cancel_poll = None
        self._socket: RooLiveSocket | None = None
        self._socket_task: asyncio.Task | None = None
        self._socket_vehicle: str | None = None
        self._logged_raw = False

    # ── lifecycle ────────────────────────────────────────────────────────────────────────────

    async def async_start(self) -> None:
        await self.api.login()
        await self._publish_available(True)
        await self._tick()

    async def async_stop(self) -> None:
        if self._cancel_poll is not None:
            self._cancel_poll()
            self._cancel_poll = None
        await self._stop_socket()
        await self._publish_available(False)

    # ── polling ──────────────────────────────────────────────────────────────────────────────

    async def _tick(self, _now: Any = None) -> None:
        try:
            await self._refresh_token_if_due()
            raw = await self.api.live_tracking(self.student_id, date.today())
            self._ingest_live_tracking(raw)
        except RooError as err:
            _LOGGER.warning("Roo live-tracking poll failed: %s", err)
        interval = POLL_ACTIVE if self.state.active else POLL_IDLE
        self._cancel_poll = async_call_later(self.hass, interval, self._tick)

    async def _refresh_token_if_due(self) -> None:
        expiry = self.api.token_expiry
        if expiry is not None and time.time() > expiry - TOKEN_REFRESH_MARGIN:
            _LOGGER.debug("Refreshing Roo token")
            await self.api.refresh()

    def _ingest_live_tracking(self, raw: dict[str, Any]) -> None:
        trackings = raw.get("liveTrackings") or []
        if not self._logged_raw and trackings:
            _LOGGER.info("Roo first live payload (confirming field names): %s", json.dumps(raw)[:1500])
            self._logged_raw = True
        entry = self._pick(trackings)
        if entry:
            self._apply(entry, active=True)
            self._update_eta(raw)
            if self.use_socket:
                self.hass.async_create_task(self._ensure_socket())
        else:
            # Trip over or not started: keep the last position but mark the bus not underway.
            if self.state.active:
                self.state.active = False
                self.state.status = "Not running"
                self._broadcast()
            self.hass.async_create_task(self._stop_socket())

    # ── parsing ──────────────────────────────────────────────────────────────────────────────

    def _pick(self, trackings: list[Any]) -> dict[str, Any] | None:
        """Choose our vehicle's entry (by id if we know it), else the first with coordinates."""
        entries = [t for t in trackings if isinstance(t, dict)]
        if self.state.vehicle_id:
            for t in entries:
                if str(_get(t, _VID)) == self.state.vehicle_id:
                    return t
        for t in entries:
            if _num(_get(t, _LAT)) is not None and _num(_get(t, _LNG)) is not None:
                return t
        return entries[0] if entries else None

    def _apply(self, entry: dict[str, Any], *, active: bool) -> None:
        lat, lng = _num(_get(entry, _LAT)), _num(_get(entry, _LNG))
        vid = _get(entry, _VID)
        self.state.active = active
        if lat is not None and lng is not None:
            self.state.latitude, self.state.longitude = lat, lng
        if vid is not None:
            self.state.vehicle_id = str(vid)
        self.state.speed = _num(_get(entry, _SPEED))
        self.state.address = _get(entry, _ADDR)
        self.state.bus_number = _get(entry, _BUS) or self.state.bus_number
        self.state.driver_name = _get(entry, _DRIVER) or self.state.driver_name
        self.state.device_datetime = _get(entry, _WHEN)
        self.state.status = "En route"
        self.state.updated = time.time()
        self._broadcast()

    def _update_eta(self, raw: dict[str, Any]) -> None:
        for source in (raw.get("liveTrackings") or []) + (raw.get("transportActivities") or []):
            if isinstance(source, dict) and (eta := _get(source, _ETA)) is not None:
                self.state.eta = str(eta)
                self.state.eta_unit = _get(source, _ETA_UNIT)
                self.state.eta_message = _get(source, _ETA_MSG)
                return

    # ── live socket ──────────────────────────────────────────────────────────────────────────

    async def _ensure_socket(self) -> None:
        vid = self.state.vehicle_id
        if not vid or (self._socket_task and self._socket_vehicle == vid):
            return
        await self._stop_socket()
        self._socket_vehicle = vid
        self._socket = RooLiveSocket(
            async_get_clientsession(self.hass), vid, self.api.access_token, self._on_socket
        )
        self._socket_task = self.hass.async_create_background_task(
            self._socket_loop(), f"roorides socket {vid}"
        )

    async def _socket_loop(self) -> None:
        assert self._socket is not None
        while self.state.active:
            try:
                await self._socket.run()
            except Exception as err:  # noqa: BLE001 - never let the socket kill the task
                _LOGGER.debug("Roo live socket dropped: %s", err)
            if self.state.active:
                await asyncio.sleep(5)

    async def _on_socket(self, positions: Any) -> None:
        entries = positions if isinstance(positions, list) else [positions]
        entry = self._pick([e for e in entries if isinstance(e, dict)])
        if entry:
            self._apply(entry, active=True)

    async def _stop_socket(self) -> None:
        self._socket_vehicle = None
        if self._socket is not None:
            await self._socket.close()
            self._socket = None
        if self._socket_task is not None:
            self._socket_task.cancel()
            self._socket_task = None

    # ── outputs ──────────────────────────────────────────────────────────────────────────────

    @callback
    def _broadcast(self) -> None:
        async_dispatcher_send(self.hass, f"{SIGNAL_UPDATE}_{self.entry.entry_id}")
        self.hass.async_create_task(self._publish_state())

    async def _publish_state(self) -> None:
        await self._publish(f"{self.topic}/bus", asdict(self.state), retain=True)

    async def _publish_available(self, online: bool) -> None:
        await self._publish(
            f"{self.topic}/available", {"online": online}, retain=True, as_text="online" if online else "offline"
        )

    async def _publish(
        self, topic: str, payload: dict[str, Any], *, retain: bool = False, as_text: str | None = None
    ) -> None:
        try:
            await mqtt.async_publish(
                self.hass, topic, as_text if as_text is not None else json.dumps(payload),
                qos=1, retain=retain,
            )
        except HomeAssistantError as err:
            _LOGGER.debug("Could not publish Roo state to %s: %s", topic, err)
