"""The bus as a GPS device_tracker, so a Map card follows it on the tablet."""

from __future__ import annotations

from typing import Any

from homeassistant.components.device_tracker import SourceType, TrackerEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import RooConfigEntry
from .entity import RooEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: RooConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([RooBusTracker(entry)])


class RooBusTracker(RooEntity, TrackerEntity):
    """Where the bus is. Holds its last known spot even after the trip ends."""

    _attr_translation_key = "bus"
    _attr_icon = "mdi:bus-school"

    @property
    def source_type(self) -> SourceType:
        return SourceType.GPS

    @property
    def latitude(self) -> float | None:
        return self.hub.state.latitude

    @property
    def longitude(self) -> float | None:
        return self.hub.state.longitude

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        s = self.hub.state
        return {
            "en_route": s.active,
            "status": s.status,
            "speed": s.speed,
            "address": s.address,
            "eta": s.eta,
            "bus_number": s.bus_number,
            "driver": s.driver_name,
            "device_time": s.device_datetime,
        }
