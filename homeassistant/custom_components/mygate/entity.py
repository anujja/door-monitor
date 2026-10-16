"""Base for the entities of a MyGate account."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceEntryType, DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from . import MyGateConfigEntry
from .const import DOMAIN


class MyGateEntity(Entity):
    """One entity of the account's device; redrawn whenever the hub sends [_signal]."""

    _attr_has_entity_name = True
    _signal: str

    def __init__(self, entry: MyGateConfigEntry) -> None:
        self._hub = entry.runtime_data
        self._entry_id = entry.entry_id
        self._attr_unique_id = f"{entry.unique_id}_{self._attr_translation_key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.unique_id or entry.entry_id)},
            name="MyGate",
            manufacturer="MyGate",
            model="Resident account",
            entry_type=DeviceEntryType.SERVICE,
        )

    async def async_added_to_hass(self) -> None:
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, f"{self._signal}_{self._entry_id}", self.async_write_ha_state
            )
        )
