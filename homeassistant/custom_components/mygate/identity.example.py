"""Who this integration presents itself as. Copy to identity.py and fill it in.

identity.py is git-ignored: it holds values from MyGate's app and details of your own phone.
"""

# MyGate's Firebase project and Android app, from the MyGate APK: project_id, google_app_id,
# google_api_key and gcm_defaultSenderId in res/values/strings.xml, and the SHA-1 of the APK's
# signing certificate (`apksigner verify --print-certs`), in hex without colons.
FIREBASE_PROJECT_ID = ""
FIREBASE_APP_ID = ""
FIREBASE_API_KEY = ""
FIREBASE_SENDER_ID = ""
ANDROID_PACKAGE = "com.mygate.user"
ANDROID_CERT_SHA1 = ""

# Your phone, as the MyGate app on it reports itself.
APP_VERSION = ""  # MyGate's version: Settings → Apps → MyGate, e.g. "7.38.2"
PHONE_MODEL = ""  # Settings → About phone, e.g. "Pixel 9"
PHONE_SDK = 0  # Android's SDK level, e.g. 37 for Android 17
PHONE_CARRIER = ""  # About phone → SIM status → Network, exactly as shown
