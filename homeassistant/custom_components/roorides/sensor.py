"""Status / ETA of the child's bus, as a sensor for the dashboard."""

from __future__ import annotations

from typing import Any

from homeassistant.components.sensor import SensorEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import RooConfigEntry
from .entity import RooEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: RooConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([RooBusStatus(entry)])


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
        eta = None if s.eta is None else " ".join(filter(None, [s.eta, s.eta_unit]))
        return {
            "eta": eta,
            "eta_message": s.eta_message,
            "address": s.address,
            "bus_number": s.bus_number,
            "driver": s.driver_name,
        }
