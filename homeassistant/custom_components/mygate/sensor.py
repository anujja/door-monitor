"""The latest MyGate alert, with the history of earlier ones for the history card."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback
from homeassistant.util import dt as dt_util

from . import MyGateConfigEntry
from .const import SIGNAL_HISTORY
from .entity import MyGateEntity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MyGateConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    async_add_entities([MyGateLastAlert(entry)])


class MyGateLastAlert(MyGateEntity, SensorEntity):
    """When the latest alert came; its visitor's photo is the entity picture.

    `history` lists the latest alerts, newest first. The recorder leaves it out: the
    integration keeps it itself, and it would bloat every recorded state.
    """

    _attr_translation_key = "last_alert"
    _attr_device_class = SensorDeviceClass.TIMESTAMP
    _attr_should_poll = False
    _unrecorded_attributes = frozenset({"history"})
    _signal = SIGNAL_HISTORY

    @property
    def _latest(self) -> dict[str, Any] | None:
        entries = self._hub.history.entries
        return entries[0] if entries else None

    @property
    def native_value(self) -> datetime | None:
        return dt_util.parse_datetime(latest["time"]) if (latest := self._latest) else None

    @property
    def entity_picture(self) -> str | None:
        return latest["image"] if (latest := self._latest) else None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        latest = self._latest or {}
        return {
            **{
                key: latest.get(key)
                for key in ("title", "text", "name", "company", "outcome", "outcome_text")
            },
            # Copies: entries change in place, and Home Assistant only writes a state that differs.
            "history": [dict(entry) for entry in self._hub.history.entries],
        }
