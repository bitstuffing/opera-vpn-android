# `:opera-vpn` — preserved legacy implementation

This Android library module contains the **original Opera protocol code** from
the earliest version of this repository. It is kept intact for reference and
for provisioning; the app in [`:app`](../app/README.md) does not reuse its
network fetchers or its UI.

- **Namespace:** `com.github.bitstuffing.operavpn.legacy`
- Everything is under the `legacy` package so there is no accidental collision
  with the app's classes.

## Why it still exists

The app needs Opera's private provisioning protocol to obtain a proxy host,
port and credentials. Reimplementing that protocol would be risky and would
lose a working reference, so the library is kept as the single source of truth
for discovery. It is also a historical record of the project's first iteration.

## Files

| File | Function |
| --- | --- |
| `OperaSeClient.kt` | The provisioning protocol: anonymous subscriber registration, device registration, geo list, `discover(requested_geo)`, login, device password and credentials. Returns an `OperaConnection` (host, port, username, password, country code, SNI hostname). |
| `OperaTransport.kt` | Legacy outbound transport that opens TLS to the Opera proxy and issues HTTP requests through it. **Not used by the app.** |
| `HttpFetcher.kt` / `AppNetwork.kt` | Original HTTP fetchers and cached connection selection from the first iteration. **Not used by the app.** |
| `OperaSeClientTest.kt` | Live integration probes against the real Opera API (opt-in). |

## Provisioning phases

`OperaSeClient.initialize(countryCode)` performs:

1. `anonRegister()` — random subscriber at `*@se0316.best.vpn`.
2. `registerDevice()` — new device hash, gets `device_id` + password.
3. `geoList()` — available regions.
4. `discover(countryCode)` — candidate proxy IPs/ports for the requested geo.
5. `subscriber_login()` and `device_generate_password()` — final credentials.
6. Builds `OperaConnection` with SNI `${country.lowercase()}0.sec-tunnel.com`.

Because a fresh subscriber and device are created on each call, calling
`initialize()` again is how the app obtains a **new exit IP** when the user
presses "Reconnect" or changes region.

## What the app uses

Only `OperaSeClient` and its `OperaConnection` data. `OperaProxy` in `:app`
then opens its own protected socket, does TLS to the Opera endpoint and sends
an authenticated HTTP `CONNECT`. The legacy `HttpFetcher`, `AppNetwork` and
`OperaTransport` are not on any user path.

## Security note

`OperaTransport` deliberately disables certificate validation (it was written
for the earliest prototype). It is never used by the VPN app. The app's `OperaProxy` uses
the platform default trust store and SNI/hostname verification instead, and
adds a second TLS layer for DNS-over-HTTPS destinations.

## Tests

`OperaSeClientTest` talks to the real Opera API, so it is excluded from the
normal build to keep `./gradlew build` deterministic:

```bash
# normal build: legacy live probes skipped
./gradlew build --offline

# opt-in live probe (needs network and working Opera API)
./gradlew :opera-vpn:testDebugUnitTest -PliveOperaTests
```

---

Made by @bitstuffing with love.

Licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
