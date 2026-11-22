# Opera VPN for Android

> **Work in progress** This app is not mature and is based
> on opera-vpn-proxy investigation published by @bitstuffing.

A small, user-facing Android VPN app built on the platform `VpnService` / TUN
interface. It routes TCP through Opera's `sec-tunnel.com` proxy, handles DNS
itself (private DNS-over-HTTPS with an optional ad blocker), and sends the UDP
that Opera cannot carry through a protected in-app relay.

This repository has no TV or streaming features: no player, provider catalogue,
EPG, adult/content filter or remote fetcher. The only user-visible features
are: **connect / disconnect**, **choose region**, **get a new IP**, **DNS ad
blocking** and **a DNS query log**.

```
┌──────────────────────┐        Android VpnService (TUN)        ┌────────────────────────────┐
│ Apps (browser, ...)  │ ───────────── IPv4 packets ──────────▶ │ VpnEngine  (user-space IP) │
└──────────────────────┘                                        └─────────────┬──────────────┘
                                                                              │
             ┌────────────────────────────────────────────────────────────────┼─────────────────┐
             │                      │                          │              │                 │
             ▼                      ▼                          ▼              ▼                 │
      TCP SYN/ACK          UDP/53 (DNS)               UDP (QUIC…)        ICMP echo               │
             │                      │                          │              │                 │
             ▼                      ▼                          ▼              ▼                 │
   OperaProxy CONNECT      DnsFilter → DoH            LocalUdpRelay     protected ping         │
   (TLS → Opera)           (TLS inside tunnel)        (protected UDP)   socket                 │
             │                      │                          │              │                 │
             ▼                      ▼                          ▼              ▼                 │
   ┌──────────────────────────────────┐              ┌──────────────────┐   replies injected    │
   │ *.sec-tunnel.com : port          │              │ real network     │   into the TUN        │
   │ TLS + Proxy-Authorization        │              │ (device route)   │                       │
   └──────────────────────────────────┘              └──────────────────┘                       │
```

---

## Contents

- [What the app does](#what-the-app-does)
- [User interface](#user-interface)
- [Connection lifecycle: connect, region change, reconnect, stop](#connection-lifecycle-connect-region-change-reconnect-stop)
- [Traffic policy](#traffic-policy)
- [DNS: interception, filtering, DoH and fallback](#dns-interception-filtering-doh-and-fallback)
- [Ad-block manager](#ad-block-manager)
- [Named lists and subscriptions](#named-lists-and-subscriptions)
- [DNS log (AdAway-style sniff log)](#dns-log-adaway-style-sniff-log)
- [Architecture and modules](#architecture-and-modules)
- [Build and test](#build-and-test)
- [GitHub Actions: tests, artifacts and releases](#github-actions-tests-artifacts-and-releases)
- [Diagnostics and troubleshooting](#diagnostics-and-troubleshooting)
- [Privacy and security](#privacy-and-security)
- [Limitations](#limitations)
- [Source map](#source-map)

---

## What the app does

1. **Provisions** an Opera connection using the legacy `OperaSeClient` (anonymous
   subscriber → device registration → geo list → discover → login → device
   password). This happens *before* the TUN exists so the API calls never enter
   the tunnel they are creating.
2. **Establishes** an IPv4-only TUN (`192.0.2.2/24`, default route, MTU 1500).
3. **Runs a tiny user-space IPv4 stack** on the TUN fd:
   - TCP is proxied through an authenticated HTTP `CONNECT` to Opera.
   - UDP/53 is parsed as DNS, optionally filtered, and answered via
     DNS-over-HTTPS inside the Opera tunnel.
   - Other UDP is handed to a local relay that owns `protect()`ed
     `DatagramSocket`s.
   - ICMP echo is handled by a best-effort protected ping socket.
4. **Exposes a minimal UI** for toggling the VPN, changing region, forcing a new
   IP, editing DNS rules and inspecting queries.

The user can change all of this while the VPN is running; region changes and
"Reconnect" tear down the old tunnel and build a fresh one.

---

## User interface

### Main screen

| Control | Behaviour |
| --- | --- |
| **Enable VPN** | Requests notification permission (Android 13+) and Android's VPN permission if needed, then starts `OperaVpnService` with `ACTION_CONNECT`. Turning it off sends `ACTION_STOP`. |
| **Region** | Spinner with exactly three options: `AM — Americas`, `EU — Europe`, `AS — Asia` (default `EU`). The selection is saved in `DnsSettings.region`. If the VPN is connected or connecting, picking a different region immediately triggers `ACTION_RECONNECT`. |
| **Status** | `Disconnected`, `Connecting…`, `Connected · host:port · region` or `Error · reason`. |
| **Traffic (this session)** | `↓ total · ↑ total` and the current `↓ speed · ↑ speed`, refreshed once a second. |
| **DNS counters** | `DNS: N blocked / M allowed`, taken from the bounded DNS log. |
| **Reconnect (new IP)** | Forces a new anonymous Opera subscriber/device, which gives a new proxy endpoint and exit IP. Available while connected; otherwise it behaves like a normal connect. |
| **DNS Filter** | Opens the ad-block manager (custom rules + link to lists). |
| **DNS Log** | Opens the query log. |

### List manager screen (Block lists)

Reached from **DNS Filter → Manage lists**. It holds two kinds of named
lists:

- **Local lists** — you create a name and paste domains (one per line, hosts
  syntax accepted).
- **URL subscriptions** — you add a name plus an `http(s)://` address pointing
  to an AdAway/hosts file. The app downloads, parses and caches it; a failed
  refresh keeps the previous domains and records the error.

Each row shows whether the list is active and how many domains it has, with
buttons to enable/disable, edit/refresh and delete. **Refresh all**
refreshes every subscription on a worker thread. The union of all enabled
lists is used by the filter, and the DNS log records which list matched.

Traffic totals count whole IP packets read from the TUN as **upload** and
written to it as **download**, including headers and locally synthesised DNS
replies. They restart on every connect/reconnect and are kept on screen after a
disconnect; the speed is the byte delta between one-second samples.

The status switch always mirrors the real service state (`VpnState`), so it
cannot get out of sync after a failure or a system-initiated restart.

### Filter screen (DNS Filter)

- Master switch **Block DNS ads**.
- **Blocked domains** and **Allowed domains** lists with per-rule
  **Remove** buttons.
- Rule input + **Add** for each list.
- **Import hosts / domains**: paste any AdAway/hosts-style text; comments,
  bare domains and `0.0.0.0`/`127.0.0.1` prefixed lines are accepted. A Toast
  reports how many *new* rules were added. Re-importing the same list is
  idempotent.
- A read-only preview of the bundled defaults (12 shown) with a note that they
  cannot be deleted.
- **Open DNS log** shortcut.

### Log screen (DNS Log)

- Scrollable list of `HH:mm:ss  domain  [TYPE]  DECISION  detail`.
- **Refresh**, **Clear log** (with confirmation), **Back**.
- Tapping an entry offers **Block domain**, **Allow domain**,
  **Copy domain** and **Cancel**, so false positives are one tap away from
  being fixed.

---

## Connection lifecycle: connect, region change, reconnect, stop

All commands arrive at `OperaVpnService.onStartCommand()` on the main thread and
are serialised there. Each attempt carries a **generation number** that is
incremented by every teardown. A slow provisioning call from an older generation
is discarded instead of resurrecting a tunnel the user has already replaced.

| Intent action | Effect |
| --- | --- |
| `ACTION_CONNECT` | No-op if a tunnel or worker is already running; otherwise provisions and establishes a TUN. |
| `ACTION_RECONNECT` | Tears down the current tunnel **first**, then provisions a fresh Opera session and establishes a new TUN. Used by the region spinner and the "new IP" button. |
| `ACTION_STOP` | Tears down everything, publishes `DISCONNECTED`, removes the foreground notification and returns `START_NOT_STICKY`. |
| `null` (system restart) | Treated as `ACTION_CONNECT` with the saved region. |

Why the order matters:

1. **Teardown before provisioning** — the provisioning HTTP calls must not go
   through the old TUN; otherwise a region change could be routed through the
   very exit node we are trying to leave.
2. **Provision before `Builder.establish()`** — once the TUN exists, every
   socket would be routed into it unless explicitly protected.
3. **New session on reconnect** — `OperaSeClient.initialize()` generates a new
   anonymous subscriber and device each time, so `discover()` returns a new
   endpoint. That is what makes "new IP" real rather than cosmetic.
4. **Generation guard** — if the user changes region twice quickly, only the
   second attempt can publish `CONNECTED`, close a descriptor or report an
   error.

Failures publish `ERROR` with the exception text and stop the foreground
service. A stale error from a previous attempt is ignored.

---

## Traffic policy

| Traffic | Path | Exit |
| --- | --- | --- |
| TCP (HTTP, HTTPS, SSH, DoT on 853…) | `OperaProxy.openTunnel()` — TLS to `*.sec-tunnel.com` plus authenticated `CONNECT` | Opera proxy |
| UDP/53 (DNS) | `DnsFilter` → `DnsOverHttps` (second TLS layer to `cloudflare-dns.com:443`) | Opera proxy |
| UDP/53 when DoH fails | `LocalUdpRelay` (protected `DatagramSocket`) | Device network |
| UDP (QUIC/HTTP3, games, media) | Framed to `127.0.0.1` local relay, then protected socket | Device network |
| ICMP echo (`ping`) | Protected Linux ping socket; replies injected into the TUN | Device network |
| IPv6 | Not configured at all (no address, route or DNS server) | Blocked while VPN is active |

The Opera control socket is `protect()`ed before it is connected. The local
relay's sockets and the ping socket are protected too. `OperaProxy.openTunnel()`
binds the raw socket to `0.0.0.0:0` **before** calling `protect(Socket)`, because
a fresh Java `Socket` has no file descriptor until it is bound or connected.

### Why UDP is direct

Opera's proxy only offers HTTP `CONNECT`: a TCP byte stream. There is no UDP or
SOCKS-UDP relay on the remote side. To carry arbitrary UDP without running an
external relay server, the app uses the same local-proxy pattern as
RethinkDNS/Orbot: the engine speaks a small framed protocol to an in-app relay
on loopback, and that relay performs the real sends with protected sockets. The
trade-off is that UDP does not share Opera's exit IP.

---

## DNS: interception, filtering, DoH and fallback

Every UDP packet to port 53 is decoded with the minimal DNS wire reader in
`DnsPacket`:

1. Extract the **question name and type** (A, AAAA, TXT, MX, HTTPS, …).
2. Ask `DnsFilter.decide(domain)`.
3. If **BLOCK**, synthesise a response from the original query: QR bit set,
   `RCODE = NXDOMAIN`, counts anchored, no resource records, and write it back
   into the TUN. The packet never leaves the device.
4. If **ALLOW**, query `DnsOverHttps`:
   - open a fresh Opera `CONNECT` to `cloudflare-dns.com:443`;
   - wrap that plaintext stream in a **second TLS layer** with SNI and hostname
     verification;
   - send `POST /dns-query` with `Content-Type: application/dns-message`;
   - parse `Content-Length`, chunked, or EOF-delimited bodies;
   - keep a small connection pool and retry once on a stale socket.
5. If DoH fails, log the reason and forward the original query through the
   protected local UDP relay, so DNS still resolves.
6. Record the decision in `DnsLog`.

The second TLS layer is essential. The TLS handshake to Opera terminates at
Opera; the `CONNECT` stream that comes back is a plain byte pipe. Writing
plaintext HTTP to port 443 fails. `OperaProxyTest` documents this exact pattern
for HTTPS destinations, and `DnsOverHttpsTest` guards it with a spy that asserts
the destination wrapper is called with `cloudflare-dns.com:443`.

DNS handling uses a six-thread unbounded executor, so a page that fires a burst
of lookups does not silently drop queries (an earlier two-thread bounded queue
did, and looked like a DNS outage).

---

## Ad-block manager

`DnsFilter` evaluates rules in this order:

1. **Allow rules win**, always — even if the same domain is also blocked. This
   lets you unbreak a site without editing the bundled list.
2. If the master switch is off, everything is allowed.
3. **Custom block rules**.
4. **Bundled defaults** (`doubleclick.net`, `googlesyndication.com`,
   `google-analytics.com`, `analytics.tiktok.com`, `adnxs.com`, `taboola.com`,
   `crashlytics.com`, …).
5. Everything else is allowed.

Rule syntax:

| Rule | Meaning |
| --- | --- |
| `ads.example.com` | That domain **and all subdomains**. |
| `*.example.com` | Same as above, explicit wildcard. |
| `.example.com` | Same as above, hosts-file style. |
| `||example.com^` | Adblock-style; normalised to `example.com`. |

Rules are lower-cased, trimmed and stripped of `||` / `^` / trailing dots when
stored. The filter and the UI share the same normalisation, so what you type is
what gets matched.

Settings live in `SharedPreferences` (`opera_vpn_dns_settings`) and are read by
both the UI and the VPN service. In-memory defaults mean the filter still works
before `init()` has run.

---

## Named lists and subscriptions

Beyond individual domains, `DnsLists` manages any number of **named lists**. A
list is either:

- **local** — you supply its domains by hand; or
- **remote** — you supply an `http(s)://` URL to a hosts-style file, which the
  app downloads, parses with `HostsParser`, and caches.

Both kinds behave the same for filtering: enabling a list adds all of its
domains to the blocking set, disabling it removes them without deleting the
data. A remote refresh that fails keeps the cached domains and records the
error text on the list, which the UI displays. A refresh that succeeds replaces
the domains, updates the timestamp and clears the error.

Implementation details:

- Max **32** lists, max **5 MB** per download, 15 s connect / 20 s read timeouts.
- Only `http` and `https` URLs are accepted; redirects are followed.
- Downloads run on a worker thread in the UI; the engine only reads the derived
  set, so fetching never blocks DNS resolution.
- A `domain → list name` map is rebuilt on every change, so matching a query
  costs one hash lookup per DNS label instead of scanning every rule.
- Lists are stored as JSON in `opera_vpn_dns_lists` and survive restarts, so
  subscriptions keep working offline.
- Allow rules still win over lists; the DNS log's detail field says
  `list: <name>` when a list caused a block.

The download uses the standard platform HTTP client. When the VPN is active the
request itself goes through the TUN (and therefore through Opera), so no
WebKit/curl-style fetcher or legacy code is involved.

---

## DNS log (AdAway-style sniff log)

`DnsLog` records up to **300** most-recent decisions, newest first, and persists
them to `SharedPreferences` every 16 entries and on shutdown. Each entry has:

- timestamp,
- domain,
- query type,
- decision: `BLOCKED`, `ALLOWED` or `ERROR`,
- detail: the matched rule (`block rule: …`, `default list: …`, `allow rule: …`,
  `filter disabled`, `default allow`) or, for errors, the DoH failure reason and
  the fallback used.

The main screen shows `blocked / allowed` counts from this log. The log screen
lets you clear it or turn any logged domain into a block/allow rule directly.
The cap keeps memory and preference size bounded during long sessions.

---

## Architecture and modules

The build has four Gradle modules. Each has its own README with file-by-file
details; the table explains **which piece of the system each one owns**.

| Module | What it is | What it does | Depends on |
| --- | --- | --- | --- |
| [`:app`](app/README.md) | Installable Android application | Owns the UI, the foreground `VpnService`, the TUN engine, DNS/DoH, the ad blocker, list subscriptions, the DNS log, ICMP and the in-process UDP relay. This is the only module that ships to a device. | `:opera-vpn`, `:udp-protocol` |
| [`:opera-vpn`](opera-vpn/README.md) | Android library, **legacy code preserved intact** | Implements Opera's private provisioning protocol (`OperaSeClient`) and keeps the original `OperaTransport`, `HttpFetcher` and `AppNetwork` for reference. The app uses **only** provisioning from it; none of its fetchers are on a user path. | — |
| [`:udp-protocol`](udp-protocol/README.md) | Plain Java library | Defines the length-prefixed frame format used to carry UDP datagrams over a TCP stream: `auth`, `datagram`, `close`, `error`, plus validated encoders/decoders. Shared by the relay server and the app's local relay client. | — |
| [`:udp-relay`](udp-relay/README.md) | Standalone Java application | Reference/test relay that speaks that protocol, sends real UDP datagrams and returns replies. It was the original remote relay; the app now runs an equivalent relay in-process, so this binary is **not** needed at runtime. | `:udp-protocol` |

The split matters: provisioning stays isolated in a legacy library, the wire
format is shared and testable in pure Java, and the standalone relay can be
tested independently of Android. The app combines them into one user-facing
service without exposing any player, provider or content features.

Key classes in `:app`:

| Class | Responsibility |
| --- | --- |
| `MainActivity` | Toggle, region spinner, stats and navigation. |
| `DnsFilterActivity` | Ad-block switch, custom rules, hosts import, link to lists. |
| `DnsListsActivity` | Named local lists and URL subscriptions, refresh/edit/enable/delete. |
| `DnsLogActivity` | Query log, rule shortcuts, clear/copy. |
| `VpnState` | Small observable state holder shared by service and UI. |
| `TrafficStats` | Per-session upload/download totals and one-second speed sampling. |
| `OperaVpnService` | Foreground service and connection state machine (generation guard). |
| `OperaProxy` | TLS to Opera, authenticated `CONNECT`, protected raw socket. |
| `VpnEngine` | User-space IPv4 stack, session tables, watchdog and diagnostics. |
| `DnsOverHttps` | DoH inside the Opera tunnel, TLS wrapper, pool, retry, counters. |
| `SocketTunnel` | Injectable tunnel opener (makes DoH testable without a network). |
| `IcmpEcho` | Best-effort protected ping socket and reply injection. |
| `LocalUdpRelay` / `UdpRelayClient` | In-app Rethink-style UDP relay and its framed client. |
| `dns/DnsPacket` | Minimal DNS wire reader and NXDOMAIN synthesiser. |
| `dns/DnsFilter` | Rule matching, allow priority, lists, bundled defaults. |
| `dns/DnsSettings` | Persisted region, switch and custom rule sets. |
| `dns/DnsLists` | Named lists, remote fetch/cache, `domain → list` lookup. |
| `dns/DnsLog` | Bounded persisted decision log. |
| `dns/HostsParser` | Shared hosts-file parser for imports and subscriptions. |

---

## Build and test

```bash
# Full build (debug + release APKs, lint, all unit tests)
./gradlew build --offline

# Just the app
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease

# Unit tests
./gradlew test --offline
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release-unsigned.apk`

Test coverage:

| Suite | What it checks |
| --- | --- |
| `:udp-protocol:test` | Frame encode/decode. |
| `:udp-relay:test` | End-to-end UDP echo through the standalone relay. |
| `DnsOverHttpsTest` | Real HTTP exchange against a local fake server: content-length, chunked, EOF framing, 502, tunnel failure, stale-connection retry, request format, size guard, counters, and a spy asserting TLS is applied to `cloudflare-dns.com:443`. |
| `DnsPacketTest` | Question parsing, malformed input, NXDOMAIN response bytes. |
| `DnsFilterTest` | Default list, subdomain matching, allow priority, rule normalisation, hosts import, disabled filter. |
| `DnsListsTest` | Local lists, enable/disable, domain replacement, allow priority vs lists, hosts parser, URL validation. |
| `UdpRelayClientTest` | Local relay round-trip. |
| `OperaProxyTest` | Opera `CONNECT` flow and the required second TLS layer for HTTPS destinations. |
| `IpPacketsTest` | IPv4/TCP/UDP builders and checksums. |
| `TrafficStatsTest` | Byte totals, one-second speed deltas, reset and human-readable sizes. |
| `:opera-vpn:testDebugUnitTest` | Opt-in live probes against the real Opera API (`OperaSeClientTest`). Excluded from the normal build so it does not depend on network availability; run with `./gradlew :opera-vpn:testDebugUnitTest -PliveOperaTests`. |

---

## GitHub Actions: tests, artifacts and releases

The workflows in [`.github/workflows`](.github/workflows) are deliberately split
so you can see tests, builds and publications as separate checks. They all use
the composite action [`.github/actions/setup-android-build`](.github/actions/setup-android-build)
(JDK 17 + Android platform 37.1 + build tools 37 + Gradle caching), so there is
no `local.properties` to configure in CI.

| Workflow | Trigger | What it does | Artifacts |
| --- | --- | --- | --- |
| [`test.yml`](.github/workflows/test.yml) | every push, PR, manual | Three independent jobs: `:app` unit tests, library tests (`:opera-vpn`, `:udp-protocol`, `:udp-relay`) and Android lint. Live Opera probes stay excluded. | `test-report-app`, `test-report-libraries`, `lint-report` (uploaded even on failure). |
| [`build.yml`](.github/workflows/build.yml) | every push, PR, manual | Calls the reusable APK build with version 1.0 / code 1 (or the values passed to *Run workflow*). | `apks` — both APKs plus `checksums.txt`. |
| [`build-apks.yml`](.github/workflows/build-apks.yml) | `workflow_call` only | The shared build: checks out the caller's ref (or an explicit SHA), assembles debug + release APKs and writes SHA-256 checksums. Not run directly. | `apks`. |
| [`release.yml`](.github/workflows/release.yml) | tag `v*`, manual (existing tag), or **Tests passing on `master`** | Resolves `v1.2.3` → versionName `1.2.3` / versionCode `10203`, or builds a master snapshot `v1.0.<run>-<attempt>`. Calls the shared build, verifies the checksums and publishes a GitHub Release. | Release assets: `app-debug.apk`, `app-release-unsigned.apk`, `checksums.txt`. |

```bash
git tag v1.2.3
git push origin v1.2.3
```

Every commit on `master` is also released automatically, but **only after the
`Tests` workflow for that commit completes successfully**. The snapshot tag is
`v1.0.<run-number>-<attempt>` (for example `v1.0.42-1`), the APK version values
come from the same numbers, and the tag is created at the exact commit that was
tested — not at whatever `master` points to later. Failed or cancelled test runs
publish nothing, and master snapshots are marked as prereleases.

The release body is generated from the commits since the previous tag. Tags
containing `-` (for example `v1.2.3-rc1`) are marked as prereleases. You can
also trigger `Release` manually and enter an existing tag; the release is
created/updated for that tag. No Google Play upload is configured.

**About the APKs:** `app-debug.apk` is signed with the debug key generated by
Android's build tools, so it can be installed directly. `app-release-unsigned.apk`
has **no signature** and must be signed with `apksigner` before installation.
Both are built from the same sources; the release workflow never signs anything.

Both version values can also be overridden locally:

```bash
./gradlew :app:assembleRelease -PappVersionName=1.2.3 -PappVersionCode=10203
```

---

## Diagnostics and troubleshooting

`VpnEngine` prints a counter summary every 30 seconds and `OperaVpnDns` prints
the reason for every DNS failure. On a device:

```bash
adb logcat -s VpnEngine:I OperaVpnDns:D OperaVpnIcmp:W OperaSeClient:D
```

A healthy excerpt:

```
Diagnostic: DoH queries=42 ok=41 failures=2 last=empty response; retrying · tcp=5 udp=3 icmpDropped=0 descargas=12.4 MB subidas=1.1 MB (icmp sent=4 received=4)
```

`failures` includes transient retries, so a small number is normal when a
keep-alive socket is recycled. A steadily growing count with a fixed `last`
reason identifies the failing leg.

| Symptom | What to look for |
| --- | --- |
| Pages load but ads do not disappear | Check the filter switch and the log detail. An `allow rule` on the same domain always wins. |
| A site breaks after blocking | Open **DNS Log**, tap the domain, choose **Allow domain**. |
| DNS resolves slowly | Look for repeated `last=` reasons; a growing `failures` count means DoH is failing and falling back to UDP relay. |
| `ping` fails but browsing works | `icmpDropped` growing means the device disallows ping sockets; this is non-fatal. |
| Changing region does nothing | The spinner triggers a reconnect only while the switch is on; when disconnected it just saves the choice for the next connect. |
| Reconnect keeps the same IP | Each reconnect creates a fresh anonymous subscriber; verify the `host:port` in **Status** changes. |
| VPN turns itself off | Check `OperaVpnService` in logcat: provisioning failures publish `Error · reason` with the underlying cause. |

---

## Privacy and security

- DNS queries are sent to Cloudflare over HTTPS **inside** the Opera tunnel;
  they are visible to Opera as an encrypted TCP stream but not as plaintext DNS.
- The Opera endpoint uses the platform trust store and hostname verification.
  The legacy `OperaTransport` (which disables validation) is never used.
- The DoH TLS layer uses SNI `cloudflare-dns.com` with endpoint identification,
  so a MITM inside the Opera proxy cannot impersonate the DoH server.
- Blocked queries are answered locally: no request for a blocked domain leaves
  the device.
- The DNS log, custom rules and cached subscriptions stay on the device in
  app-private `SharedPreferences`; only a list refresh downloads its URL.
- Subscription downloads use the platform HTTP client through the VPN (when
  active) and never use the legacy `HttpFetcher`/`OperaTransport` code.
- IPv6 is intentionally left unconfigured, so it cannot leak around the tunnel.

---

## Limitations

- UDP traffic exits from the device's real network, not from Opera, so its
  public IP differs from the TCP exit IP.
- Networks that block UDP can still break QUIC, games and media even though TCP
  works.
- ICMP echo depends on the device allowing unprivileged Linux ping sockets. If
  unavailable, `ping` fails while the rest of the VPN works.
- Provisioning depends on Opera's private API staying compatible; a change
  there breaks the service even if the VPN engine is fine.
- Ad blocking is DNS-level only: it cannot inspect URLs, headers or page content.
- The log is capped at 300 entries; it is a diagnostic tool, not long-term
  telemetry.
- The release APK is unsigned; sign it with your own key before distributing.

---

## Source map

```
app/src/main/java/com/github/bitstuffing/operavpn/
├── MainActivity.kt
├── DnsFilterActivity.kt
├── DnsListsActivity.kt
├── DnsLogActivity.kt
├── VpnState.kt
├── TrafficStats.kt
├── OperaVpnService.kt
├── OperaProxy.kt
├── VpnEngine.kt
├── DnsOverHttps.kt
├── SocketTunnel.kt
├── IcmpEcho.kt
├── LocalUdpRelay.kt
├── UdpRelayClient.kt
├── ip/IpPackets.kt
└── dns/
    ├── DnsPacket.kt
    ├── DnsFilter.kt
    ├── DnsSettings.kt
    ├── DnsLists.kt
    ├── DnsList.kt
    ├── DnsLog.kt
    └── HostsParser.kt

app/src/main/res/
├── layout/activity_main.xml
├── layout/activity_dns_filter.xml
├── layout/activity_dns_lists.xml
├── layout/activity_dns_log.xml
└── values/strings.xml

app/README.md
opera-vpn/README.md
udp-protocol/README.md
udp-relay/README.md
.github/actions/setup-android-build/action.yml
.github/workflows/test.yml
.github/workflows/build.yml
.github/workflows/build-apks.yml
.github/workflows/release.yml
```

The app is intentionally small: no WebKit, no network fetchers, no media stack,
no provider data and no content features. Everything above is in service of
"connect to Opera, route traffic, optionally filter DNS, explain what happened".

---

Made by @bitstuffing with love.

Licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
