"""Constants for the MyGate integration."""

DOMAIN = "mygate"

CONF_PHONE = "phone"
CONF_COUNTRY_CODE = "country_code"
CONF_OTP = "otp"
CONF_DEVICE_ID = "device_id"
CONF_API_KEY = "api_key"
CONF_USER_ID = "user_id"
CONF_FLAT_ID = "flat_id"
CONF_FCM_CREDENTIALS = "fcm_credentials"
CONF_TOPIC = "topic"

DEFAULT_COUNTRY_CODE = "91"
# Same topics the Door Monitor tablet used when it relayed MyGate's notifications itself, so the
# tablet's alert card and the event.doormonitor_mygate entity keep working unchanged.
DEFAULT_TOPIC = "doormonitor/mygate"

# Responses MyGate accepts for a gate approval, and the status code each one sends.
RESPONSE_APPROVE = "approve"
RESPONSE_DENY = "deny"
RESPONSE_LEAVE_AT_GATE = "leave_at_gate"
RESPONSE_CODES = {RESPONSE_APPROVE: "A", RESPONSE_DENY: "D", RESPONSE_LEAVE_AT_GATE: "L"}

# event_type values; the tablet's MQTT discovery declares exactly these.
TYPE_APPROVAL = "approval"
TYPE_EMERGENCY = "emergency"
TYPE_VEHICLE = "vehicle"
TYPE_GENERAL = "general"

SERVICE_RESPOND = "respond"
SERVICE_SEND_TEST_ALERT = "send_test_alert"
ATTR_ACTION = "action"
ATTR_ID = "id"

SIGNAL_PUSH_STATE = f"{DOMAIN}_push_state"
