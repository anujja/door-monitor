# Local HTTP API

Door Monitor runs a local JSON HTTP server (default port **2323**, configurable in Settings).
All endpoints are `GET` and return `application/json`.

Base URL: `http://<tablet-ip>:2323`

## Authentication
If an **API password** is set, include it as either:
- query param: `?password=YOURPASS`
- header: `Authorization: Bearer YOURPASS`

Unauthorized requests return HTTP `401` with `{"status":"error","message":"unauthorized"}`.

## Endpoints

| Method & path | Description | Example response |
|---------------|-------------|------------------|
| `GET /` | List endpoints | `{"app":"Door Monitor","endpoints":[...]}` |
| `GET /status` | Device + battery status | see below |
| `GET /screenOn` | Wake screen | `{"status":"ok","action":"screenOn"}` |
| `GET /screenOff` | Sleep screen | `{"status":"ok","action":"screenOff"}` |
| `GET /wake` | Permanent wake | `{"status":"ok","action":"wake"}` |
| `GET /wake?seconds=30` | Temporary wake (30 s) | `{"status":"ok","action":"wake"}` |
| `GET /sleep` | Alias for screenOff | `{"status":"ok","action":"screenOff"}` |
| `GET /brightness?value=20` | Set brightness 0–255 | `{"status":"ok","action":"brightness","value":20}` |
| `GET /reload` | Reload dashboard | `{"status":"ok","action":"reload"}` |
| `GET /camera?id=front_door` | Open camera full-screen | `{"status":"ok","action":"camera","id":"front_door"}` |
| `GET /event?type=motion` | Motion wake | `{"status":"ok","action":"event"}` |
| `GET /event?type=doorbell` | Doorbell wake | `{"status":"ok","action":"event"}` |

Errors return `{"status":"error","message":"..."}` with `400`/`404`/`500` as appropriate.

## `GET /status` example
```json
{
  "appId": "com.doormonitor",
  "deviceId": "doormonitor",
  "model": "Lenovo TB330FU",
  "androidVersion": "14",
  "screenOn": true,
  "brightness": 160,
  "dashboardUrl": "https://homeassistant.local:8123/lovelace/wall",
  "batteryLevel": 92,
  "batteryCharging": true,
  "batteryHealth": "good",
  "batteryTemperatureC": 29.5,
  "plugged": true,
  "highBatteryWarning": false,
  "mqttEnabled": true,
  "cameras": "front_door,garage"
}
```

## curl quick reference
```bash
IP=192.168.1.50; PORT=2323; PASS=secret   # omit ?password if no password set

curl "http://$IP:$PORT/status?password=$PASS"
curl "http://$IP:$PORT/screenOn?password=$PASS"
curl "http://$IP:$PORT/screenOff?password=$PASS"
curl "http://$IP:$PORT/brightness?value=20&password=$PASS"
curl "http://$IP:$PORT/wake?seconds=30&password=$PASS"
curl "http://$IP:$PORT/reload?password=$PASS"
curl "http://$IP:$PORT/camera?id=front_door&password=$PASS"

# header auth instead of query param:
curl -H "Authorization: Bearer $PASS" "http://$IP:$PORT/status"
```
