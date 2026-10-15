"""Sign in to MyGate with phone number and OTP."""

from __future__ import annotations

from collections.abc import Mapping
import logging
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import SOURCE_REAUTH, ConfigFlow, ConfigFlowResult
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import MyGateApi, MyGateError, async_get_client, new_device_id
from .const import (
    CONF_API_KEY,
    CONF_COUNTRY_CODE,
    CONF_DEVICE_ID,
    CONF_FCM_CREDENTIALS,
    CONF_FLAT_ID,
    CONF_OTP,
    CONF_PHONE,
    CONF_TOPIC,
    CONF_USER_ID,
    DEFAULT_COUNTRY_CODE,
    DEFAULT_TOPIC,
    DOMAIN,
)
from .push import async_register, push_token

_LOGGER = logging.getLogger(__name__)


class MyGateConfigFlow(ConfigFlow, domain=DOMAIN):
    """Phone number → OTP → signed in as a new device on the account."""

    VERSION = 1

    def __init__(self) -> None:
        self._phone = ""
        self._country_code = DEFAULT_COUNTRY_CODE
        self._topic = DEFAULT_TOPIC
        self._device_id = new_device_id()
        self._fcm_credentials: dict[str, Any] | None = None

    def _api(self) -> MyGateApi:
        return MyGateApi(async_get_client(self.hass), self._device_id)

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            self._phone = user_input[CONF_PHONE].strip().replace(" ", "").lstrip("+")
            self._country_code = user_input[CONF_COUNTRY_CODE].strip().lstrip("+")
            self._topic = user_input[CONF_TOPIC].strip().rstrip("/")
            await self.async_set_unique_id(f"{self._country_code}{self._phone}")
            self._abort_if_unique_id_configured()
            if await self._send_otp(errors):
                return await self.async_step_otp()

        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_PHONE, default=self._phone): str,
                    vol.Required(CONF_COUNTRY_CODE, default=self._country_code): str,
                    vol.Required(CONF_TOPIC, default=self._topic): str,
                }
            ),
            errors=errors,
        )

    async def _send_otp(self, errors: dict[str, str]) -> bool:
        try:
            await self._api().request_otp(self._phone, self._country_code)
        except MyGateError as err:
            _LOGGER.warning("MyGate OTP request failed: %s", err)
            errors["base"] = "otp_request_failed"
            return False
        return True

    async def async_step_otp(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                if self._fcm_credentials is None:
                    self._fcm_credentials = await async_register(async_get_clientsession(self.hass))
            except Exception:  # noqa: BLE001 - library raises bare RuntimeErrors
                _LOGGER.exception("Push registration with MyGate's Firebase project failed")
                errors["base"] = "push_registration_failed"
            else:
                token = push_token(self._fcm_credentials)
                api = self._api()
                try:
                    profile = await api.login(
                        self._phone, self._country_code, user_input[CONF_OTP].strip(), token
                    )
                    await api.register_push_token(str(profile["userid"]), token)
                except MyGateError as err:
                    _LOGGER.warning("MyGate sign-in failed: %s", err)
                    errors["base"] = "invalid_otp" if "OTP" in str(err) else "login_failed"
                else:
                    data = {
                        CONF_PHONE: self._phone,
                        CONF_COUNTRY_CODE: self._country_code,
                        CONF_TOPIC: self._topic,
                        CONF_DEVICE_ID: self._device_id,
                        CONF_API_KEY: profile["api_key"],
                        CONF_USER_ID: str(profile["userid"]),
                        CONF_FLAT_ID: str(profile.get("flatid") or "") or None,
                        CONF_FCM_CREDENTIALS: self._fcm_credentials,
                    }
                    if self.source == SOURCE_REAUTH:
                        return self.async_update_reload_and_abort(
                            self._get_reauth_entry(), data=data
                        )
                    return self.async_create_entry(
                        title=f"MyGate (+{self._country_code} {self._phone})", data=data
                    )

        return self.async_show_form(
            step_id="otp",
            data_schema=vol.Schema({vol.Required(CONF_OTP): str}),
            errors=errors,
            description_placeholders={"phone": f"+{self._country_code} {self._phone}"},
        )

    async def async_step_reauth(self, entry_data: Mapping[str, Any]) -> ConfigFlowResult:
        """MyGate signed this device out: send a new OTP, keeping the device id and push token."""
        self._phone = entry_data[CONF_PHONE]
        self._country_code = entry_data[CONF_COUNTRY_CODE]
        self._topic = entry_data.get(CONF_TOPIC, DEFAULT_TOPIC)
        self._device_id = entry_data[CONF_DEVICE_ID]
        self._fcm_credentials = entry_data[CONF_FCM_CREDENTIALS]
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None and await self._send_otp(errors):
            return await self.async_step_otp()
        return self.async_show_form(
            step_id="reauth_confirm",
            errors=errors,
            description_placeholders={"phone": f"+{self._country_code} {self._phone}"},
        )
