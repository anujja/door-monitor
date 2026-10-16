"""MyGate: visitor alerts and gate approvals without the MyGate app.

Signs in to the resident's MyGate account as its own device, receives MyGate's push alerts
directly, publishes each one on MQTT (`<topic>/notification`, `<topic>/notification_removed`),
and answers gate approvals through the `mygate.respond` action or `<topic>/respond`. Keeps a
history of the alerts, which the bundled `custom:mygate-history-card` shows on a dashboard.
"""

from __future__ import annotations

import logging
from pathlib import Path

import voluptuous as vol

from homeassistant.components.frontend import add_extra_js_url
from homeassistant.components.http import StaticPathConfig
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.typing import ConfigType

from .api import MyGateApi, MyGateError, async_get_client
from .const import (
    ATTR_ACTION,
    ATTR_ID,
    CONF_API_KEY,
    CONF_DEVICE_ID,
    CONF_USER_ID,
    DOMAIN,
    RESPONSE_CODES,
    SERVICE_RESPOND,
    SERVICE_SEND_TEST_ALERT,
)
from .history import MyGateHistory
from .hub import MyGateHub

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [Platform.BINARY_SENSOR, Platform.SENSOR]
CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)

type MyGateConfigEntry = ConfigEntry[MyGateHub]

CARD_FILE = Path(__file__).parent / "frontend" / "mygate-history-card.js"
CARD_URL = f"/{DOMAIN}/{CARD_FILE.name}"

RESPOND_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_ACTION): vol.In(list(RESPONSE_CODES)),
        vol.Optional(ATTR_ID): cv.string,
    }
)


def _hub(hass: HomeAssistant) -> MyGateHub:
    entries = hass.config_entries.async_loaded_entries(DOMAIN)
    if not entries:
        raise ServiceValidationError("MyGate is not set up")
    return entries[0].runtime_data


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    async def respond(call: ServiceCall) -> None:
        await _hub(hass).async_respond(call.data[ATTR_ACTION], call.data.get(ATTR_ID))

    async def send_test_alert(call: ServiceCall) -> None:
        await _hub(hass).async_send_test_alert()

    hass.services.async_register(DOMAIN, SERVICE_RESPOND, respond, schema=RESPOND_SCHEMA)
    hass.services.async_register(DOMAIN, SERVICE_SEND_TEST_ALERT, send_test_alert)

    # The history card, loaded on every dashboard. Its modification time busts browser caches.
    await hass.http.async_register_static_paths(
        [StaticPathConfig(CARD_URL, str(CARD_FILE), cache_headers=False)]
    )
    mtime = await hass.async_add_executor_job(lambda: int(CARD_FILE.stat().st_mtime))
    add_extra_js_url(hass, f"{CARD_URL}?v={mtime}")
    return True


async def async_setup_entry(hass: HomeAssistant, entry: MyGateConfigEntry) -> bool:
    hub = MyGateHub(hass, entry)
    await hub.async_start()
    entry.runtime_data = hub
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: MyGateConfigEntry) -> bool:
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unloaded:
        await entry.runtime_data.async_stop()
    return unloaded


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Sign this device out of MyGate so it stops receiving pushes, and drop its history."""
    await MyGateHistory(hass, entry.entry_id, lambda: None).async_remove()
    api = MyGateApi(
        async_get_client(hass), entry.data[CONF_DEVICE_ID], entry.data[CONF_API_KEY]
    )
    try:
        await api.logout(entry.data[CONF_USER_ID])
    except MyGateError as err:
        _LOGGER.warning("Could not sign out of MyGate: %s", err)
