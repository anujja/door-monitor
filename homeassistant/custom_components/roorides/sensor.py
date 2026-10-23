"""Status / ETA of the child's bus, as a sensor for the dashboard."""

from __future__ import annotations

from typing import Any

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import RooConfigEntry
from .entity import RooEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: RooConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([RooBusStatus(entry), RooBusEta(entry)])


class RooBusStatus(RooEntity, SensorEntity):
    """"En route" while a trip is on, otherwise "Not running"; the ETA rides along."""

    _attr_translation_key = "status"
    _attr_icon = "mdi:bus-clock"

    @property
    def native_value(self) -> str | None:
        return self.hub.state.status or ("En route" if self.hub.state.active else "Not running")

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        s = self.hub.state
        eta = None if s.eta is None else f"{s.eta} {s.eta_unit or 'min'}".strip()
        return {
            "eta": eta,
            "eta_message": s.eta_message,
            "address": s.address,
            "bus_number": s.bus_number,
            "driver": s.driver_name,
        }


class RooBusEta(RooEntity, SensorEntity):
    """Live driving ETA from the bus to home — traffic-aware, via Waze (minutes)."""

    _attr_translation_key = "eta"
    _attr_icon = "mdi:map-marker-distance"
    _attr_native_unit_of_measurement = "min"
    _attr_device_class = SensorDeviceClass.DURATION
    _attr_suggested_display_precision = 0

    @property
    def native_value(self) -> int | None:
        return self.hub.state.eta
