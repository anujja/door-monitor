"""A record of MyGate's alerts and how each visitor was answered, kept across restarts.

Shown by the history card (frontend/mygate-history-card.js) through the "Last alert" sensor.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from homeassistant.core import HomeAssistant
from homeassistant.helpers.storage import Store

from .const import DOMAIN

STORAGE_VERSION = 1
MAX_ENTRIES = 100
SAVE_DELAY = 10

# Outcomes of an approval, as shown on the card.
OUTCOME_WAITING = "waiting"
OUTCOME_APPROVED = "approved"
OUTCOME_DENIED = "denied"
OUTCOME_LEFT_AT_GATE = "left_at_gate"
OUTCOME_TIMEOUT = "timeout"
OUTCOME_ANSWERED = "answered"  # answered elsewhere, and MyGate's text doesn't say how


def _entry(payload: dict[str, Any], outcome: str | None) -> dict[str, Any]:
    visitor = payload.get("visitor") or {}
    return {
        "id": payload["id"],
        "time": payload["time"],
        "type": payload["event_type"],
        "title": payload["title"],
        "text": payload["text"],
        "name": visitor.get("name"),
        "company": visitor.get("company"),
        "company_logo": visitor.get("company_logo"),
        "image": visitor.get("image"),
        "visitor_type": visitor.get("type"),
        "outcome": outcome,
        "outcome_text": None,
        "test": payload["test"],
    }


class MyGateHistory:
    """The latest alerts, newest first."""

    def __init__(self, hass: HomeAssistant, entry_id: str, on_change: Callable[[], None]) -> None:
        self._store: Store[list[dict[str, Any]]] = Store(
            hass, STORAGE_VERSION, f"{DOMAIN}.history.{entry_id}"
        )
        self._on_change = on_change
        self.entries: list[dict[str, Any]] = []

    async def async_load(self) -> None:
        self.entries = await self._store.async_load() or []

    async def async_remove(self) -> None:
        await self._store.async_remove()

    def _find(self, alert_id: str) -> dict[str, Any] | None:
        return next((e for e in self.entries if e["id"] == alert_id), None)

    def record(self, payload: dict[str, Any], outcome: str | None = None) -> None:
        """A new alert, or MyGate re-sending one we have (which keeps its outcome so far)."""
        new = _entry(payload, outcome)
        if old := self._find(new["id"]):
            old.update({k: v for k, v in new.items() if v is not None and k not in ("time", "outcome")})
        else:
            self.entries.insert(0, new)
            del self.entries[MAX_ENTRIES:]
        self._changed()

    def resolve(
        self,
        alert_id: str,
        outcome: str,
        text: str | None = None,
        *,
        unless_answered: bool = False,
        payload: dict[str, Any] | None = None,
    ) -> None:
        """Record how a visitor was answered.

        `unless_answered`: leave an outcome already recorded alone (a timeout after an answer).
        `payload`: the outcome's own alert; recorded as a new entry when the visitor isn't in
        the history (they arrived before Home Assistant started).
        """
        entry = self._find(alert_id)
        if entry is None:
            if payload is None:
                return
            self.record(payload)
            entry = self.entries[0]
        elif unless_answered and entry["outcome"] not in (None, OUTCOME_WAITING):
            return
        entry["outcome"] = outcome
        entry["outcome_text"] = text
        self._changed()

    def _changed(self) -> None:
        self._store.async_delay_save(lambda: self.entries, SAVE_DELAY)
        self._on_change()
