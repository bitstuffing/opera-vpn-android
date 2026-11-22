# `:app` — the user-facing VPN

This Gradle module is the only installable part of the project. It turns the
legacy Opera provisioning code into a real Android VPN: a foreground
`VpnService`, a small user-space IPv4 stack on the TUN fd, private DNS with an
ad blocker and a query log.

- **Namespace / application id:** `com.github.bitstuffing.operavpn`
- **minSdk:** 31 · **compileSdk:** 37.1 · **JVM target:** 11
- **UI toolkit:** plain `android.widget` views (no AppCompat/Material, no WebKit)

See the [root README](../README.md) for the big picture and the other modules.

## What lives here

```
src/main/java/com/github/bitstuffing/operavpn/
├── MainActivity.kt            toggle, region spinner, stats, navigation
├── DnsFilterActivity.kt       ad-block switch, custom rules, hosts import
├── DnsListsActivity.kt        named local lists + URL subscriptions
├── DnsLogActivity.kt          bounded query log, block/allow shortcuts
├── VpnState.kt                observable connection state shared with the UI
├── TrafficStats.kt            per-session byte totals and speed sampling
├── OperaVpnService.kt         foreground service / connection state machine
├── OperaProxy.kt              TLS to Opera + authenticated HTTP CONNECT
├── VpnEngine.kt               IPv4/TCP/UDP/ICMP relay over the TUN fd
├── DnsOverHttps.kt            DoH inside the Opera tunnel (double TLS)
├── SocketTunnel.kt            injectable socket opener (test seam)
├── IcmpEcho.kt                best-effort protected ping socket
├── LocalUdpRelay.kt           in-app Rethink-style UDP relay
├── UdpRelayClient.kt          framed client for LocalUdpRelay
├── ip/IpPackets.kt            IPv4/TCP/UDP packet builders + checksums
└── dns/
    ├── DnsPacket.kt           DNS question parser + NXDOMAIN synthesiser
    ├── DnsFilter.kt           rule evaluation (allow > custom > lists > defaults)
    ├── DnsSettings.kt         persisted region, switch, block/allow rules
    ├── DnsLists.kt            named local/remote lists, fetch + cache
    ├── DnsLog.kt              bounded persisted decision log
    └── HostsParser.kt         shared hosts-file text parser
```

Resources: `layout/activity_main.xml`, `activity_dns_filter.xml`,
`activity_dns_lists.xml`, `activity_dns_log.xml`, `values/strings.xml`,
`values/themes.xml`, notification icon and launcher icons.

## Screens

| Screen | Role |
| --- | --- |
| `MainActivity` | Enable switch, region spinner (`AM`/`EU`/`AS`), status, traffic totals/speed, `DNS: blocked / allowed`, reconnect/new-IP, two DNS shortcuts. |
| `DnsFilterActivity` | Master ad-block switch, custom block/allow rules with add/remove, hosts paste import, link to lists and log, bundled defaults preview. |
| `DnsListsActivity` | Create named local lists, subscribe to hosts URLs, enable/disable, edit domains, refresh one or all, delete. Downloads run on a worker thread; results are cached offline. |
| `DnsLogActivity` | Newest-first query list with decision + reason; refresh, clear, and tap-to-block/allow/copy. |

All activities call `DnsSettings.init`, `DnsLists.init` and/or `DnsLog.init`
before reading state. Those initialisers are idempotent and use
`applicationContext`.

## Traffic counters

`TrafficStats` owns two atomic totals and a one-second sampling baseline:

- `VpnEngine.readLoop()` calls `onUpload(length)` for every packet read from the
  TUN (traffic leaving the device).
- `VpnEngine.writePacket()` calls `onDownload(packet.size)` for every packet
  written back, including locally generated DNS replies.
- `OperaVpnService.connect()` resets the counters, so totals belong to the
  current session. They stay visible after a disconnect.
- `MainActivity` posts `TrafficStats.snapshot()` once per second while resumed.
  Speed is `(total - previous total) / elapsed`, with a 250 ms floor to avoid
  unrealistic peaks from a single packet.

`formatBytes` / `formatRate` render B, KB, MB and GB. Totals are also included
in the 30-second `Diagnóstico:` log line.

## Service lifecycle

`OperaVpnService` receives `ACTION_CONNECT`, `ACTION_RECONNECT` and
`ACTION_STOP` (plus a `null` intent from a system restart). Every command is
handled on the main thread. Each attempt has a generation number, incremented
by teardown, so a slow provisioning call from an old attempt can never publish
`CONNECTED` or close a descriptor owned by a newer one.

Order of operations for a connect/reconnect:

1. **Teardown** the old engine/TUN (reconnect only).
2. **Provision** via `OperaProxy.initialize(country)` — before the TUN exists,
   so API calls do not enter the tunnel.
3. **Start the local UDP relay** with protected sockets.
4. **`Builder.establish()`** the TUN (`192.0.2.2/24`, default route, MTU 1500).
5. **Start `VpnEngine`** and publish `CONNECTED` with `host:port · region`.

`ACTION_RECONNECT` creates a fresh anonymous Opera subscriber/device, which is
what actually changes the exit IP.

## Engine data flow

```
TUN packet → parseIpv4
  ├─ TCP  → TcpSession (SYN/ACK, retransmit, ack, FIN) → Opera CONNECT stream
  ├─ UDP/53 → DnsPacket → DnsFilter → DoH or NXDOMAIN → TUN reply
  │             └─ DoH failure → protected local UDP relay
  ├─ UDP other → UdpRelayClient → LocalUdpRelay → protected DatagramSocket
  └─ ICMP echo → IcmpEcho → protected ping socket → reply injected into TUN
```

A six-thread unbounded executor handles DNS so bursts are never dropped. The
watchdog runs every 250 ms for retransmissions/timeouts and prints a diagnostic
summary every 30 s.

## DNS subsystem

| File | Function |
| --- | --- |
| `DnsPacket` | Reads the question name/type from a DNS message; builds an NXDOMAIN response by echoing the question and setting QR + RCODE 3. |
| `DnsFilter` | Decision order: allow rules → master switch → custom block rules → named lists → bundled defaults → allow. |
| `DnsSettings` | Persists region, switch and custom rule sets; in-memory defaults make it safe before initialisation. |
| `DnsLists` | Named lists (`DnsList`) with local domains or a URL source; bounded 5 MB fetch, hosts parsing, JSON persistence, and a `domain → list name` lookup map for O(labels) matching. |
| `DnsLog` | Last 300 decisions with reason, flushed every 16 entries and on shutdown. |
| `HostsParser` | One parser shared by custom imports and list downloads. |

Rule syntax: exact domain (also matches subdomains), `*.domain`, `.domain` and
`||domain^`. Allow rules always win, so a false positive is fixed with one tap
in the log screen.

## Persistence

| Preferences file | Contents |
| --- | --- |
| `opera_vpn_dns_settings` | region, ad-block switch, custom block/allow sets |
| `opera_vpn_dns_lists` | JSON array of named lists (id, name, URL, enabled, domains, timestamp, last error) |
| `opera_vpn_dns_log` | JSON array of the newest 300 log entries |

All are app-private and never leave the device.

## Tests

`src/test/java/...` runs on the JVM with JUnit 5:

- `DnsOverHttpsTest` — fake local DoH server; framing, errors, retries and the TLS spy.
- `DnsPacketTest` — question parsing and NXDOMAIN bytes.
- `DnsFilterTest` — defaults, subdomains, allow priority, hosts import.
- `DnsListsTest` — local lists, enable/disable, replacement, URL validation, parser.
- `UdpRelayClientTest`, `OperaProxyTest`, `ip/IpPacketsTest` — relay round-trip,
  Opera CONNECT + destination TLS, and packet builders.

```bash
./gradlew :app:testDebugUnitTest --offline
./gradlew :app:assembleDebug --offline
```

## Diagnostics

```bash
adb logcat -s VpnEngine:I OperaVpnDns:D OperaVpnIcmp:W OperaVpnService:E
```

Look for the diagnostic summary (`Diagnostic:` / `Diagnóstico:`) every 30 s (DoH counters, TCP/UDP sessions, ICMP
stats) and `OperaVpnDns` lines for individual failures. The in-app DNS log
shows the user-facing equivalent, including which list matched.

---

Made by @bitstuffing with love.

Licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
