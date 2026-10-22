"""Base for the Roo bus entities, all redrawn when the hub has a new position."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceEntryType, DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from . import RooConfigEntry
from .const import DOMAIN, SIGNAL_UPDATE
from .hub import RooHub


class RooEntity(Entity):
    """One entity of the child's bus."""

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, entry: RooConfigEntry) -> None:
        self.hub: RooHub = entry.runtime_data
        self._entry_id = entry.entry_id
        self._attr_unique_id = f"{entry.entry_id}_{self._attr_translation_key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            name=f"{self.hub.student_name}'s bus",
            manufacturer="Roo Commuter",
            model="School bus",
            entry_type=DeviceEntryType.SERVICE,
        )

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, f"{SIGNAL_UPDATE}_{self._entry_id}", self.async_write_ha_state
            )
        )
