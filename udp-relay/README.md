# `:udp-relay` — standalone reference UDP relay

A small Java server that accepts the framed protocol from [`:udp-protocol`](../udp-protocol/README.md)
over TCP and performs the real UDP sends. It was the original remote relay for
Opera's CONNECT-only proxy and is kept as a reference tool and test backend.

> The Android app does **not** require this server. It runs an equivalent relay
> in-process (`LocalUdpRelay` in [`:app`](../app/README.md)) with `protect()`ed
> sockets, so there is no external dependency at runtime.

## What it does

1. Accepts a TCP client and authenticates with `TYPE_AUTH` (constant-time token
   comparison).
2. Reads `TYPE_DATAGRAM` frames, opens a `DatagramSocket` per session and sends
   the UDP payload to the IPv4/port in the frame.
3. Reads replies from those sockets and sends them back as `TYPE_DATAGRAM` with
   the same session id.
4. Enforces a maximum number of sessions and an idle timeout; `TYPE_CLOSE`
   removes a session.
5. One daemon thread per client, all sessions in concurrent maps.

`UdpRelayServer` is also a library class (`start()`, `getLocalPort()`,
`close()`), which is how `UdpRelayServerTest` starts an ephemeral instance.

## Running

```bash
./gradlew :udp-relay:run --offline
```

Configuration (environment variables or CLI flags):

| Env var | Flag | Default | Meaning |
| --- | --- | --- | --- |
| `UDP_RELAY_BIND` | `--bind` | `0.0.0.0` | Listen address |
| `UDP_RELAY_PORT` | `--port` | `1080` | Listen port |
| `UDP_RELAY_TOKEN` | `--token` | `change-me` | Auth token |
| `UDP_RELAY_MAX_SESSIONS` | `--max-sessions` | `512` | Concurrent UDP sessions |
| `UDP_RELAY_TIMEOUT` | `--timeout` | `120` | Idle timeout in seconds |

A shutdown hook closes the listener and all client handlers.

## Tests

`UdpRelayServerTest` starts the server on an ephemeral port and verifies an
end-to-end UDP echo through the framed protocol:

```bash
./gradlew :udp-relay:test --offline
```

---

Made by @bitstuffing with love.

Licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
