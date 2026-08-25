# IPWL - Comprehensive Documentation

**IPWL (IP Whitelist)** is an advanced, high-performance, server-side firewall and whitelist system for Fabric Minecraft servers. It introduces zero-trust network verification that intercepts connection packets before Mojang authentication completes, blocking unauthorized logins, credential stuffing, bot swarms, and malicious IPs before they consume server resources.

---

## Table of Contents

1. [Architecture & How It Works](#1-architecture--how-it-works)
2. [Installation & Requirements](#2-installation--requirements)
3. [Admin & Permission Management](#3-admin--permission-management)
4. [Whitelist Management & Syntax](#4-whitelist-management--syntax)
5. [Temporary Access System](#5-temporary-access-system)
6. [Firewall & Permanent IP Bans](#6-firewall--permanent-ip-bans)
7. [Automated Protection & Anti-Bot Shield](#7-automated-protection--anti-bot-shield)
8. [Interactive Alerts & Notifications](#8-interactive-alerts--notifications)
9. [Inspection & Monitoring Tools](#9-inspection--monitoring-tools)
10. [Configuration Reference (`config/ipwl.json`)](#10-configuration-reference-configipwljson)
11. [Data Storage & Localization Files](#11-data-storage--localization-files)
12. [Command Cheat Sheet](#12-command-cheat-sheet)

---

## 1. Architecture & How It Works

Traditional whitelists authenticate players via Mojang/Microsoft auth servers before checking whitelist status. This exposes servers to auth denial-of-service, excessive thread consumption, and resource exhaustion during bot attacks.

IPWL hooks into the network pipeline at the **very first handshake packet** (`ServerboundHelloPacket` / Netty channel pipeline) using Mixin (`ServerLoginNetworkHandlerMixin`). 

### Connection Verification Pipeline:
Every inbound connection must pass through a strict 9-step evaluation pipeline:

```
[ Inbound Connection ]
         │
         ▼
 1. Emergency Lockdown Check ─── (Active? If non-admin -> Block immediately)
         │
         ▼
 2. Permanent IP Ban Check ───── (Banned IP? -> Block immediately)
         │
         ▼
 3. Temporary Ban Check ──────── (Rate limit / Bruteforce ban active? -> Block)
         │
         ▼
 4. Bruteforce Heuristic ─────── (3+ usernames from 1 IP in 60s? -> Auto 1h ban & block)
         │
         ▼
 5. Rate Limit Verification ──── (Connections too rapid? -> Block & count failures)
         │
         ▼
 6. Duplicate Session Check ──── (Same username already connected? -> Block)
         │
         ▼
 7. Whitelist & IP Match ─────── (Player on whitelist & matching allowed IP? -> Block + Trigger Admin Alert if unknown)
         │
         ▼
 8. Concurrent IP Limit ──────── (Exceeds max connections for this IP? -> Block)
         │
         ▼
 9. [ Connection Accepted ] ──── (Secondary async re-verification runs post-join)
```

---

## 2. Installation & Requirements

- **Platform**: Fabric Server (Server-Side Only; clients do not need the mod installed)
- **Supported Minecraft Versions**: `1.21.1` through `1.21.11`, `26.1`, `26.2`
- **Fabric Loader**: `>= 0.19.0`
- **Java**: Java 21+ (Java 25 recommended for latest versions)

### Steps:
1. Place the compiled `ipwhitelist-<version>-mc<mc_version>.jar` into your server's `mods/` directory.
2. Start the server. IPWL will automatically generate default configuration and data files in the `config/` folder.
3. Access the server console and grant initial admin rights:
   ```bash
   ipwl admin add <YourUsername>
   ```

---

## 3. Admin & Permission Management

IPWL uses an independent permission layer completely decoupled from vanilla Minecraft OP levels. This prevents privilege escalation and ensures security controls remain strictly governed.

### Managing Admins:
- `/ipwl admin add <player>`: Grant IPWL admin status. Saved immediately to `config/ipwl.json`.
- `/ipwl admin remove <player>`: Revoke IPWL admin status. Instantly removes their permissions and live-updates their client-side command suggestion tree without requiring a reconnect.
- `/ipwl admin list`: Lists all configured IPWL administrators.

> **Console Access**: The server console always maintains root access to all IPWL commands.

---

## 4. Whitelist Management & Syntax

### Commands:
- `/ipwl add <player> [ip]`: Whitelists a player. If no IP is specified, defaults to wildcard `*`.
- `/ipwl remove <player>`: Removes a player from the whitelist and disconnects them immediately if online.
- `/ipwl addip <player> <ip>`: Adds a secondary or additional allowed IP address to an existing player.
- `/ipwl removeip <player> <ip>`: Removes one specific allowed IP address from a player.
- `/ipwl list`: Displays all whitelisted players and their assigned IP patterns.
- `/ipwl reload`: Hot-reloads the whitelist and configuration from disk without restarting the server.

### Supported IP Patterns:
1. **Exact IP**: Matches a single specific IP address.
   - Example: `192.168.1.5` or `2001:db8::1`
2. **Wildcard Suffix**: Matches any address sharing the specified prefix.
   - Example: `192.168.1.*` (matches `192.168.1.0` through `192.168.1.255`)
3. **CIDR Subnet Notation**: Matches all IPs within a CIDR subnet range (IPv4 and IPv6).
   - Example: `10.0.0.0/8`, `192.168.1.0/24`, `2001:db8::/32`
4. **Global Wildcard (`*`)**: Allows the player to connect from any IP address while still being on the whitelist.

---

## 5. Temporary Access System

Temporary access grants a player permission to connect from a specified IP for a defined duration without altering the permanent whitelist.

### Syntax:
```
/ipwl tempadd <player> <ip> <duration>
```

### Duration Units:
- Seconds: `3600s`
- Minutes: `30m`
- Hours: `2h`
- Days: `1d` / `7d`

### Lifecycle:
- Temporary entries are tracked in-memory.
- When the timer expires, the access is automatically revoked.
- All online admins receive an immediate alert notification when a temporary pass expires.

---

## 6. Firewall & Permanent IP Bans

IPWL provides a dedicated network firewall layer to block hostile IPs from attempting connection with any username.

### Commands:
- `/ipwl banip <ip>`: Adds an IP to the permanent ban list and terminates any active connections from that address.
- `/ipwl unbanip <ip>`: Removes an IP from the ban list.
- `/ipwl banip list`: Displays all permanently banned IPs.

All banned IPs are persisted in `config/ipwl.json` across server restarts.

---

## 7. Automated Protection & Anti-Bot Shield

IPWL includes built-in automated heuristics to detect and neutralize connection attacks:

| Threat / Pattern | Detection Criteria | Automated Mitigation |
| :--- | :--- | :--- |
| **Credential Stuffing / Bot Attack** | 3 or more distinct usernames attempted from the same IP within a 60-second window | **1-Hour Automatic Temporary Ban** applied immediately |
| **Connection Flooding / Hammering** | Inbound connection rate exceeds `rateLimitWindowMs` threshold repeatedly | **5-Minute Temporary Ban** (escalated after `maxFailuresBeforeTempBan` attempts) |
| **Duplicate Login Attack** | Client attempts connecting with an existing online player's name | Second session rejected immediately |
| **Simultaneous Session Flood** | Connections from a single IP exceed `maxConnectionsPerIp` | Subsequent connections blocked |

---

## 8. Interactive Alerts & Notifications

When an unwhitelisted player attempts to connect, IPWL alerts staff through two channels:

### 1. In-Game Interactive Admin Chat:
Online administrators receive a notification with clickable chat components:
- **`[Accept]`**: Pre-fills and executes `/ipwl add <player> <ip>` instantly.
- **`[Ban IP]`**: Pre-fills and executes `/ipwl banip <ip>` instantly.

### 2. Server Console Visual Box:
```
[IPWL] ====================================================
[IPWL]   !! UNKNOWN CONNECTION ATTEMPT !!
[IPWL] ====================================================
[IPWL]   Name   : Steve
[IPWL]   IP     : 198.51.100.45
[IPWL]   To add : /ipwl add Steve 198.51.100.45
[IPWL]   To ban : /ipwl banip 198.51.100.45
[IPWL] ====================================================
```

Admin actions (accepting, banning, or removing players) are broadcast across all online admins to avoid duplicated efforts.

---

## 9. Inspection & Monitoring Tools

### Security Commands:
- `/seen <player>`: Displays a player's last recorded IP address, connection timestamp, and current online status.
- `/connections`: Lists all currently connected players along with their active IP addresses.
- `/security status`: Prints real-time statistics including total blocked attempts, active temporary bans, lockdown status, and rate limit counters.
- `/lockdown on`: Enables emergency lockdown. Blocks all non-admin players from connecting.
- `/lockdown off`: Disables emergency lockdown and resumes normal filtering.
- `/ipwl logs verbose`: Enables verbose diagnostic output in chat and console.
- `/ipwl logs silent`: Sets logging to minimal/critical events only.

---

## 10. Configuration Reference (`config/ipwl.json`)

The main configuration file is stored at `config/ipwl.json`:

```json
{
  "admins": [
    "Dany0443"
  ],
  "whitelist": {
    "Steve": [
      "192.168.1.5",
      "10.0.0.0/24"
    ],
    "Alex": [
      "*"
    ]
  },
  "bannedIps": [
    "203.0.113.10"
  ],
  "maxConnectionsPerIp": 2,
  "rateLimitWindowMs": 1000,
  "maxFailuresBeforeTempBan": 5,
  "tempBanDurationMs": 300000,
  "enableRateLimit": true,
  "enableDuplicateCheck": true,
  "enableJoinAlerts": true,
  "alertCooldownMs": 30000,
  "verboseLogging": false,
  "allowWildcardIps": true,
  "allowSubnetPatterns": true,
  "lockdown": false
}
```

### Configuration Options Breakdown:

| Key | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `admins` | Array | `[]` | List of player usernames with full IPWL administrative access. |
| `whitelist` | Object | `{}` | Map of usernames to arrays of allowed IP/CIDR/wildcard strings. |
| `bannedIps` | Array | `[]` | List of permanently banned IP addresses. |
| `maxConnectionsPerIp` | Integer | `2` | Maximum concurrent player sessions allowed from the same IP address. |
| `rateLimitWindowMs` | Long | `1000` | Minimum milliseconds required between connection handshakes from the same IP. |
| `maxFailuresBeforeTempBan` | Integer | `5` | Number of rate limit violations before triggering an automated temporary ban. |
| `tempBanDurationMs` | Long | `300000` | Duration (in milliseconds) for automated rate-limit temporary bans (5 minutes). |
| `enableRateLimit` | Boolean | `true` | Enables or disables the handshake rate limiter. |
| `enableDuplicateCheck` | Boolean | `true` | Prevents multiple logins under the same username. |
| `enableJoinAlerts` | Boolean | `true` | Sends interactive alerts to admins when unwhitelisted players connect. |
| `alertCooldownMs` | Long | `30000` | Cooldown period between join alerts for the same IP to prevent chat spam. |
| `verboseLogging` | Boolean | `false` | When true, logs detailed handshake diagnostics to console. |
| `allowWildcardIps` | Boolean | `true` | Allows global wildcard `*` IP entries in whitelist. |
| `allowSubnetPatterns` | Boolean | `true` | Enables subnet CIDR and wildcard prefix evaluation. |
| `lockdown` | Boolean | `false` | Emergency lockdown toggle (blocks all non-admin connections). |

---

## 11. Data Storage & Localization Files

| File | Purpose |
| :--- | :--- |
| `config/ipwl.json` | Main configuration, admin roster, and permanent IP bans. |
| `config/ipwl_whitelist.json` | Persistent player-to-IP whitelist database. |
| `config/ipwl-stats.json` | Persistent security metrics and firewall counters. |
| `config/ipwl-seen.json` | Last-seen connection timestamps and historical IP records. |
| `config/ipwl-lang.json` | Optional language and message override file. |

### Message Customization (`config/ipwl-lang.json`):
To override default messages without recompiling the mod, create `config/ipwl-lang.json` and specify custom strings:

```json
{
  "ipwl.alert.unknown": "§c[Firewall] §eUnknown login attempt from %s (IP: %s)",
  "ipwl.disconnect.not_whitelisted": "§cAccess Denied: Your IP is not registered on this server."
}
```

---

## 12. Command Cheat Sheet

```text
/ipwl add <player> [ip]               - Whitelist player with optional IP / CIDR / *
/ipwl remove <player>                 - Remove player from whitelist & kick
/ipwl addip <player> <ip>             - Add extra IP to player
/ipwl removeip <player> <ip>          - Remove specific IP from player
/ipwl tempadd <player> <ip> <time>    - Add temporary access (e.g. 30m, 2h, 1d)
/ipwl list                            - List all whitelisted players & IPs
/ipwl reload                          - Reload configuration from disk
/ipwl banip <ip>                      - Permanently ban an IP address
/ipwl unbanip <ip>                    - Remove an IP ban
/ipwl banip list                      - List all banned IPs
/ipwl admin add <player>              - Grant IPWL admin access
/ipwl admin remove <player>           - Revoke IPWL admin access
/ipwl admin list                      - List all IPWL admins
/ipwl logs verbose|silent             - Toggle logging level
/lockdown on|off                      - Toggle emergency server lockdown
/seen <player>                        - Show last known IP and timestamp
/connections                          - Show active player connections & IPs
/security status                      - Show live protection metrics
```
