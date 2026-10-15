"""Whether MyGate's alerts can reach Home Assistant: push is up and the sign-in is valid."""

from __future__ import annotations

from datetime import timedelta

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.device_registry import DeviceEntryType, DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import MyGateConfigEntry
from .const import DOMAIN, SIGNAL_PUSH_STATE

SCAN_INTERVAL = timedelta(seconds=30)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MyGateConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    async_add_entities([MyGatePushConnected(entry)])


class MyGatePushConnected(BinarySensorEntity):
    """On while MyGate alerts can reach Home Assistant. Alert on this going off."""

    _attr_has_entity_name = True
    _attr_translation_key = "push_connected"
    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, entry: MyGateConfigEntry) -> None:
        self._hub = entry.runtime_data
        self._entry_id = entry.entry_id
        self._attr_unique_id = f"{entry.unique_id}_push_connected"
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
                self.hass, f"{SIGNAL_PUSH_STATE}_{self._entry_id}", self.async_write_ha_state
            )
        )

    @property
    def is_on(self) -> bool:
        return self._hub.connected

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        return {
            "last_alert": self._hub.last_alert.isoformat() if self._hub.last_alert else None,
            "pending_approvals": list(self._hub.pending),
        }
