"""Sign in to Roo Commuter with the account's phone number and password, then pick the child."""

from __future__ import annotations

from collections.abc import Mapping
import logging
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import SOURCE_REAUTH, ConfigFlow, ConfigFlowResult
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import RooApi, RooAuthError, RooError
from .const import (
    CONF_CORP_ID,
    CONF_PASSWORD,
    CONF_STUDENT_ID,
    CONF_STUDENT_NAME,
    CONF_TOPIC,
    CONF_USE_SOCKET,
    CONF_USERNAME,
    DEFAULT_TOPIC,
    DOMAIN,
)

_LOGGER = logging.getLogger(__name__)


class RooConfigFlow(ConfigFlow, domain=DOMAIN):
    """Phone + password → choose the child → one entry per child."""

    VERSION = 1

    def __init__(self) -> None:
        self._username = ""
        self._password = ""
        self._topic = DEFAULT_TOPIC
        self._use_socket = True
        self._students: list[dict[str, Any]] = []

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            self._username = user_input[CONF_USERNAME].strip().replace(" ", "")
            self._password = user_input[CONF_PASSWORD]
            self._topic = user_input[CONF_TOPIC].strip().rstrip("/")
            self._use_socket = user_input[CONF_USE_SOCKET]
            api = RooApi(async_get_clientsession(self.hass), self._username, self._password)
            try:
                await api.login()
                self._students = await api.students()
            except RooAuthError:
                errors["base"] = "invalid_auth"
            except RooError:
                errors["base"] = "cannot_connect"
            else:
                if not self._students:
                    errors["base"] = "no_students"
                else:
                    return await self.async_step_student()

        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_USERNAME, default=self._username): str,
                    vol.Required(CONF_PASSWORD): str,
                    vol.Required(CONF_TOPIC, default=self._topic): str,
                    vol.Required(CONF_USE_SOCKET, default=self._use_socket): bool,
                }
            ),
            errors=errors,
        )

    async def async_step_student(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        if len(self._students) == 1:
            return await self._create(self._students[0])
        if user_input is not None:
            chosen = next(
                s for s in self._students
                if str(s.get("studentid") or s.get("studentId")) == user_input[CONF_STUDENT_ID]
            )
            return await self._create(chosen)

        options = {
            str(s.get("studentid") or s.get("studentId")): (
                s.get("student_Name") or s.get("studentName") or "Child"
            )
            for s in self._students
        }
        return self.async_show_form(
            step_id="student",
            data_schema=vol.Schema({vol.Required(CONF_STUDENT_ID): vol.In(options)}),
        )

    async def _create(self, student: dict[str, Any]) -> ConfigFlowResult:
        sid = str(student.get("studentid") or student.get("studentId"))
        name = (student.get("student_Name") or student.get("studentName") or "Child").strip()
        await self.async_set_unique_id(f"{self._username}_{sid}")
        self._abort_if_unique_id_configured()
        return self.async_create_entry(
            title=f"{name}'s bus",
            data={
                CONF_USERNAME: self._username,
                CONF_PASSWORD: self._password,
                CONF_STUDENT_ID: sid,
                CONF_STUDENT_NAME: name,
                CONF_CORP_ID: student.get("corpId") or student.get("corpID"),
                CONF_TOPIC: self._topic,
                CONF_USE_SOCKET: self._use_socket,
            },
        )

    async def async_step_reauth(self, entry_data: Mapping[str, Any]) -> ConfigFlowResult:
        self._username = entry_data[CONF_USERNAME]
        self._topic = entry_data.get(CONF_TOPIC, DEFAULT_TOPIC)
        self._use_socket = entry_data.get(CONF_USE_SOCKET, True)
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            password = user_input[CONF_PASSWORD]
            api = RooApi(async_get_clientsession(self.hass), self._username, password)
            try:
                await api.login()
            except RooAuthError:
                errors["base"] = "invalid_auth"
            except RooError:
                errors["base"] = "cannot_connect"
            else:
                return self.async_update_reload_and_abort(
                    self._get_reauth_entry(), data_updates={CONF_PASSWORD: password}
                )
        return self.async_show_form(
            step_id="reauth_confirm",
            data_schema=vol.Schema({vol.Required(CONF_PASSWORD): str}),
            errors=errors,
            description_placeholders={"username": self._username},
        )
