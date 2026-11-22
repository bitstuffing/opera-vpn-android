# `:udp-protocol` — local relay wire format

A tiny Java library that defines the framed protocol used to carry UDP
datagrams over a TCP stream. Both the standalone [`:udp-relay`](../udp-relay/README.md)
and the app's in-app `LocalUdpRelay` speak this format.

## Frame format

```
[4-byte big-endian length][1-byte type][payload]
```

`length` counts the type byte plus the payload (1 … 65,535 bytes).

| Type | Value | Payload |
| --- | --- | --- |
| `TYPE_AUTH` | 0 | UTF-8 token |
| `TYPE_DATAGRAM` | 1 | `[8-byte session id][4-byte IPv4][2-byte port][UDP payload]` |
| `TYPE_CLOSE` | 2 | `[8-byte session id]` |
| `TYPE_ERROR` | 3 | UTF-8 error message |

`MAX_DATAGRAM_PAYLOAD = 65,535 − 1 − 14 = 65,520` bytes.

## API

`UdpRelayProtocol` is stateless; all methods are static:

| Method | Purpose |
| --- | --- |
| `auth(token)` / `error(text)` / `close(sessionId)` | Build control frames. |
| `datagram(sessionId, address, port, payload)` | Validate and build a datagram frame. |
| `readFrame(InputStream)` | Read one frame, with full-length reads and bounds checks. |
| `decodeDatagram(payload)` / `decodeSessionId(payload)` / `decodeText(payload)` | Decode frame payloads. |

`Frame` and `Datagram` are immutable value holders. Addresses are IPv4 only,
ports are 1–65,535, and malformed or truncated input throws `IOException`.

## Tests

`UdpRelayProtocolTest` covers encode/decode round-trips, frame boundaries,
truncated input and invalid arguments:

```bash
./gradlew :udp-protocol:test --offline
```

---

Made by @bitstuffing with love.

Licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
