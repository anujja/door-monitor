"""Constants for the Roo Commuter (roorides.com) integration."""

DOMAIN = "roorides"

CONF_USERNAME = "username"
CONF_PASSWORD = "password"
CONF_STUDENT_ID = "student_id"
CONF_STUDENT_NAME = "student_name"
CONF_CORP_ID = "corp_id"
CONF_TOPIC = "topic"
CONF_USE_SOCKET = "use_socket"

# Where the bus position is published, so the tablet and anything else can subscribe. Mirrors the
# MyGate integration's `doormonitor/mygate`.
DEFAULT_TOPIC = "doormonitor/roorides"

# How often LiveTrackingV3 is polled. Fast while the child's trip is live (the array has data),
# slow otherwise — the bus is parked most of the day.
POLL_ACTIVE = 15  # seconds
POLL_IDLE = 300  # seconds

# The access token is a JWT; refresh it this long before it expires rather than on a 401.
TOKEN_REFRESH_MARGIN = 120  # seconds

SIGNAL_UPDATE = f"{DOMAIN}_update"
