"""A dashboard button to stop following the bus for the journey underway."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import RooConfigEntry
from .entity import RooEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: RooConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([RooStopTracking(entry)])


class RooStopTracking(RooEntity, ButtonEntity):
    """Stop tracking this journey (the tablet goes back to its dashboard); the next one is
    tracked as usual. Only available while a journey is being tracked."""

    _attr_translation_key = "stop_tracking"
    _attr_icon = "mdi:map-marker-off"

    @property
    def available(self) -> bool:
        return self.hub.state.active

    async def async_press(self) -> None:
        await self.hub.async_stop_tracking()
