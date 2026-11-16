"""The running Roo account: watches the child's trip, follows the bus, and publishes it.

A parent sees the bus's live position over Roo's SignalR hub (see `signalr.py`), which streams it
as soon as a trip starts — before the child boards too. So the hub works from the trip *status*:
it polls the student's `tripStatus` ("Pickup Ongoing" / "Drop Ongoing") to know when a trip is
underway, and while it is, keeps the socket open and pushes every position to the integration's
entities and to MQTT, so the tablet (and anything else) can follow the bus by subscribing — the
project's MQTT-first habit. Off-trip it polls slowly and holds the last known spot.

A parent can also stop tracking the journey in progress (the dashboard's "Stop tracking" button),
e.g. when the child didn't board or Roo never closes the trip. That journey is then treated as
over: the bus is marked not underway, so the tablet goes back to its dashboard. Tracking picks up
again on its own with the next journey — once Roo's trip status moves off the stopped one. The
stop survives a Home Assistant restart.
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
from homeassistant.helpers.storage import Store

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
    DOMAIN,
    POLL_ACTIVE,
    POLL_IDLE,
    SIGNAL_UPDATE,
    TOKEN_REFRESH_MARGIN,
)
from .signalr import RooLiveSocket

_LOGGER = logging.getLogger(__name__)

# Case-insensitive field synonyms for a hub position object. Roo's live socket sends
# latitude/longitude/vehicleId/vehicleNumber/message/deviceDatetime; the synonyms also cover the
# REST shapes in case we read a position from there.
_LAT = ("latitude", "lat")
_LNG = ("longitude", "lng", "long", "lon")
_VID = ("vehicleid", "vehicle_id")
_SPEED = ("speed",)
_ADDR = ("address", "currentaddress", "fulladdress")
_WHEN = ("devicedatetime", "datetime", "timestamp", "time")
_BUS = ("vehiclenumber", "vehiclename", "busnumber", "vehicle_reg_no", "vehicleregno", "busno")
_DRIVER = ("drivername", "driver_name", "driver")
_MSG = ("message", "statusmessage")

# Traffic-aware driving ETA to home, computed from the live position via Waze. Region "EU" is
# Waze's rest-of-world routing server, which covers India (verified for Bengaluru).
WAZE_REGION = "EU"
ETA_INTERVAL = 45  # seconds between ETA recalculations while a trip is underway

STATUS_STOPPED = "Tracking stopped"
STORAGE_VERSION = 1


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


def _plausible(lat: float, lng: float) -> bool:
    """Reject Roo's "no fix" sentinels so a status-only message can't erase the real position."""
    if abs(lat) > 90 or abs(lng) > 180:
        return False
    return (lat, lng) not in ((0.0, 0.0), (-1.0, -1.0))


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
    eta: int | None = None
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
        self._waze_calc: Any = None
        self._last_eta: float = 0.0
        # Roo's raw tripStatus for the journey underway ("Pickup Ongoing" / "Drop Ongoing").
        # `state.status` can't stand in for it: the live socket overwrites that with its messages.
        self._trip_status: str | None = None
        # The tripStatus of the journey the user stopped tracking, until that journey is over.
        self._stopped_trip: str | None = None
        self._store: Store[dict[str, Any]] = Store(
            hass, STORAGE_VERSION, f"{DOMAIN}.{entry.entry_id}"
        )

    # ── lifecycle ────────────────────────────────────────────────────────────────────────────

    async def async_start(self) -> None:
        if (saved := await self._store.async_load()) and saved.get("stopped_trip"):
            self._stopped_trip = saved["stopped_trip"]
            self.state.status = STATUS_STOPPED
        await self.api.login()
        await self._publish_available(True)
        await self._tick()

    async def async_stop(self) -> None:
        if self._cancel_poll is not None:
            self._cancel_poll()
            self._cancel_poll = None
        await self._stop_socket()
        if self._waze_calc is not None:
            await self._waze_calc.close()
            self._waze_calc = None
        await self._publish_available(False)

    async def async_stop_tracking(self) -> None:
        """Stop following the journey underway; the next journey is tracked as usual."""
        if not self.state.active or not self._trip_status:
            raise HomeAssistantError("No bus journey is being tracked right now")
        self._stopped_trip = self._trip_status
        await self._store.async_save({"stopped_trip": self._stopped_trip})
        _LOGGER.info(
            "Stopped tracking %s's bus for this journey (%s); resuming with the next one",
            self.student_name, self._stopped_trip,
        )
        # Mark the bus not underway before closing the socket, so its loop doesn't reconnect.
        self.state.active = False
        await self._stop_socket()
        self.state.status = STATUS_STOPPED
        self.state.eta = None
        self._broadcast()

    async def _resume_tracking(self) -> None:
        self._stopped_trip = None
        await self._store.async_save({"stopped_trip": None})
        _LOGGER.info("The stopped bus journey is over; tracking %s's bus again", self.student_name)
        self.state.status = "Not running"

    # ── polling ──────────────────────────────────────────────────────────────────────────────

    async def _tick(self, _now: Any = None) -> None:
        # Always schedule the next poll, even if this one fails unexpectedly — a single stray
        # exception here used to end polling for good, so a later trip went unnoticed.
        try:
            await self._poll()
        except Exception:  # noqa: BLE001
            _LOGGER.exception("Roo trip poll crashed")
        interval = POLL_ACTIVE if self.state.active else POLL_IDLE
        self._cancel_poll = async_call_later(self.hass, interval, self._tick)

    async def _poll(self) -> None:
        try:
            await self._refresh_token_if_due()
            status = await self.api.trip_status(self.student_id)
        except RooError as err:
            _LOGGER.warning("Roo trip poll failed: %s", err)
            if self._stopped_trip is not None:
                return  # can't tell whether the stopped journey is over yet; stay stopped
            status = self._trip_status if self.state.active else None
        ongoing = bool(status) and "ongoing" in status.lower()
        if self._stopped_trip is not None:
            if ongoing and status == self._stopped_trip:
                return  # still the journey the user stopped tracking
            await self._resume_tracking()
            if not ongoing:
                self._broadcast()  # "Tracking stopped" → "Not running"; a new journey broadcasts below
        if ongoing:
            first = not self.state.active
            self.state.active = True
            self._trip_status = status
            if status:
                self.state.status = status
            if first:
                await self._apply_timeline()
                self._broadcast()
            if self.use_socket:
                self.hass.async_create_task(self._ensure_socket())
            self._maybe_update_eta()
        else:
            await self._stop_socket()
            self._trip_status = None
            if self.state.active:
                # Trip over: keep the last known position but mark the bus not underway.
                self.state.active = False
                self.state.status = "Not running"
                self.state.eta = None
                self._broadcast()

    async def _refresh_token_if_due(self) -> None:
        expiry = self.api.token_expiry
        if expiry is not None and time.time() > expiry - TOKEN_REFRESH_MARGIN:
            _LOGGER.debug("Refreshing Roo token")
            await self.api.refresh()

    async def _apply_timeline(self) -> None:
        """Set a human status hint (e.g. "Pickup started") from the latest reached milestone."""
        try:
            timeline = await self.api.trip_timeline(self.student_id, date.today())
        except RooError:
            return
        done = [e for e in timeline if isinstance(e, dict) and e.get("checked")]
        if done:
            last = done[-1]
            self.state.eta_message = last.get("message") or last.get("label")

    # ── ETA to home (traffic-aware, via Waze) ────────────────────────────────────────────────

    def _maybe_update_eta(self) -> None:
        """Throttle to one ETA recalculation per ETA_INTERVAL while the bus has a position."""
        if self.state.latitude is None or self.state.longitude is None:
            return
        now = time.time()
        if now - self._last_eta < ETA_INTERVAL:
            return
        self._last_eta = now
        self.hass.async_create_task(self._update_eta())

    def _home_coords(self) -> tuple[float, float] | None:
        zone = self.hass.states.get("zone.home")
        if zone is None:
            return None
        lat, lng = zone.attributes.get("latitude"), zone.attributes.get("longitude")
        if lat is None or lng is None:
            return None
        return (lat, lng)

    def _waze(self) -> Any:
        if self._waze_calc is None:
            from pywaze.route_calculator import WazeRouteCalculator

            self._waze_calc = WazeRouteCalculator(region=WAZE_REGION)
        return self._waze_calc

    async def _update_eta(self) -> None:
        """Set a traffic-aware driving ETA (whole minutes) from the bus to home, via Waze."""
        home = self._home_coords()
        lat, lng = self.state.latitude, self.state.longitude
        if home is None or lat is None or lng is None:
            return
        try:
            routes = await self._waze().calc_routes(f"{lat},{lng}", f"{home[0]},{home[1]}")
        except Exception as err:  # noqa: BLE001 - routing is best-effort; never break the poll
            _LOGGER.debug("Roo ETA (Waze) failed: %s", err)
            return
        if routes and self.state.active:  # not if tracking stopped while Waze was answering
            self.state.eta = round(routes[0].duration)
            self.state.eta_unit = "min"
            self._broadcast()

    # ── parsing ──────────────────────────────────────────────────────────────────────────────

    def _pick(self, positions: list[Any]) -> dict[str, Any] | None:
        """Choose our vehicle's entry (by id if we know it), else the first with coordinates."""
        entries = [t for t in positions if isinstance(t, dict)]
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
        if lat is not None and lng is not None and _plausible(lat, lng):
            self.state.latitude, self.state.longitude = lat, lng
        if vid is not None:
            self.state.vehicle_id = str(vid)
        self.state.speed = _num(_get(entry, _SPEED))
        self.state.address = _get(entry, _ADDR)
        self.state.bus_number = _get(entry, _BUS) or self.state.bus_number
        self.state.driver_name = _get(entry, _DRIVER) or self.state.driver_name
        self.state.device_datetime = _get(entry, _WHEN)
        if msg := _get(entry, _MSG):
            self.state.status = msg
        self.state.updated = time.time()
        self._broadcast()

    # ── live socket ──────────────────────────────────────────────────────────────────────────

    async def _ensure_socket(self) -> None:
        if not self.state.active:
            return  # tracking was stopped before this got to run
        if self._socket_task is not None and not self._socket_task.done():
            return
        self._socket = RooLiveSocket(
            async_get_clientsession(self.hass),
            self.student_id,
            self.api.user_id,
            self._on_socket,
        )
        self._socket_task = self.hass.async_create_background_task(
            self._socket_loop(), f"roorides socket {self.student_id}"
        )

    async def _socket_loop(self) -> None:
        assert self._socket is not None
        while self.state.active:
            try:
                await self._socket.run()
            except Exception as err:  # noqa: BLE001 - never let the socket kill the task
                _LOGGER.debug("Roo live socket dropped: %s", err)
            if self.state.active:
                await asyncio.sleep(3)

    async def _on_socket(self, positions: Any) -> None:
        if not self.state.active:
            return  # a late message after tracking stopped
        entries = positions if isinstance(positions, list) else [positions]
        entry = self._pick([e for e in entries if isinstance(e, dict)])
        if entry:
            self._apply(entry, active=True)

    async def _stop_socket(self) -> None:
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
