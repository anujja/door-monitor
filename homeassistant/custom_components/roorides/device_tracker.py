"""The bus as a GPS device_tracker, so a Map card follows it on the tablet."""

from __future__ import annotations

import base64
from typing import Any

from homeassistant.components.device_tracker import SourceType, TrackerEntity
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import RooConfigEntry
from .entity import RooEntity

# A Home Assistant map marker shows the entity's `entity_picture` if it has one,
# otherwise the initials of its name. So to put a bus on the map (instead of the
# child's initials) we hand the tracker a picture: an MDI "bus-school" glyph on a
# school-bus-amber disc, inlined as a data URI so there's no file to host.
_BUS_MARKER_SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">'
    '<rect width="48" height="48" fill="#F7B500"/>'
    '<g transform="translate(8,8) scale(1.3333)" fill="#1b2430">'
    '<path d="M3,6C1.89,6 1,6.89 1,8V15H3A3,3 0 0,0 6,18A3,3 0 0,0 9,15H15A3,3 0 0,0 '
    "18,18A3,3 0 0,0 21,15H23V12C23,10.89 22.11,10 21,10H19V8C19,6.89 18.11,6 17,6H3M13.5,"
    "7.5H17.5V10H13.5V7.5M2.5,7.5H6.5V10H2.5V7.5M8,7.5H12V10H8V7.5M6,13.5A1.5,1.5 0 0,1 7.5,"
    "15A1.5,1.5 0 0,1 6,16.5A1.5,1.5 0 0,1 4.5,15A1.5,1.5 0 0,1 6,13.5M18,13.5A1.5,1.5 0 0,1 "
    '19.5,15A1.5,1.5 0 0,1 18,16.5A1.5,1.5 0 0,1 16.5,15A1.5,1.5 0 0,1 18,13.5Z"/></g></svg>'
)
_BUS_MARKER = "data:image/svg+xml;base64," + base64.b64encode(
    _BUS_MARKER_SVG.encode()
).decode()


async def async_setup_entry(
    hass: HomeAssistant, entry: RooConfigEntry, async_add_entities: AddConfigEntryEntitiesCallback
) -> None:
    async_add_entities([RooBusTracker(entry)])


class RooBusTracker(RooEntity, TrackerEntity):
    """Where the bus is. Holds its last known spot even after the trip ends."""

    _attr_translation_key = "bus"
    _attr_icon = "mdi:bus-school"
    _attr_entity_picture = _BUS_MARKER

    @property
    def source_type(self) -> SourceType:
        return SourceType.GPS

    @property
    def latitude(self) -> float | None:
        return self.hub.state.latitude

    @property
    def longitude(self) -> float | None:
        return self.hub.state.longitude

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        s = self.hub.state
        return {
            "en_route": s.active,
            "status": s.status,
            "speed": s.speed,
            "address": s.address,
            "eta": s.eta,
            "bus_number": s.bus_number,
            "driver": s.driver_name,
            "device_time": s.device_datetime,
        }
