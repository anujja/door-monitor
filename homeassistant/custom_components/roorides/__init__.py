"""Roo Commuter: follow the school bus on the wall tablet.

Signs in to the parent's Roo (roorides.com) account, polls the child's live tracking, and while
the bus is underway follows it — over the live-tracking socket when it can — publishing the
position to MQTT (`<topic>/bus`) and exposing a device_tracker, an ETA/status sensor and an
"en route" binary_sensor. Put a Map card bound to the device_tracker on the dashboard and the
bus shows up on the tablet while it is on its way.
"""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant

from .hub import RooHub

PLATFORMS = [Platform.DEVICE_TRACKER, Platform.SENSOR, Platform.BINARY_SENSOR, Platform.BUTTON]

type RooConfigEntry = ConfigEntry[RooHub]


async def async_setup_entry(hass: HomeAssistant, entry: RooConfigEntry) -> bool:
    hub = RooHub(hass, entry)
    await hub.async_start()
    entry.runtime_data = hub
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: RooConfigEntry) -> bool:
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unloaded:
        await entry.runtime_data.async_stop()
    return unloaded
