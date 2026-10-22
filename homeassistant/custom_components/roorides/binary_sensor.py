"""Whether the bus is currently underway — the hook for automations (wake the tablet, chime…)."""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import RooConfigEntry
from .entity import RooEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: RooConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([RooBusEnRoute(entry)])


class RooBusEnRoute(RooEntity, BinarySensorEntity):
    """On while the child's trip is in progress."""

    _attr_translation_key = "en_route"
    _attr_device_class = BinarySensorDeviceClass.MOVING
    _attr_icon = "mdi:bus-marker"

    @property
    def is_on(self) -> bool:
        return self.hub.state.active
