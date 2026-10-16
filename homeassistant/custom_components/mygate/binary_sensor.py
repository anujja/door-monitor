"""Whether MyGate's alerts can reach Home Assistant: push is up and the sign-in is valid."""

from __future__ import annotations

from datetime import timedelta

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import MyGateConfigEntry
from .const import SIGNAL_PUSH_STATE
from .entity import MyGateEntity

SCAN_INTERVAL = timedelta(seconds=30)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: MyGateConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    async_add_entities([MyGatePushConnected(entry)])


class MyGatePushConnected(MyGateEntity, BinarySensorEntity):
    """On while MyGate alerts can reach Home Assistant. Alert on this going off."""

    _attr_translation_key = "push_connected"
    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _signal = SIGNAL_PUSH_STATE

    @property
    def is_on(self) -> bool:
        return self._hub.connected

    @property
    def extra_state_attributes(self) -> dict[str, object]:
        return {
            "last_alert": self._hub.last_alert.isoformat() if self._hub.last_alert else None,
            "pending_approvals": list(self._hub.pending),
        }
