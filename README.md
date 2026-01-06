IPWL (IP Based Whitelist)

A simple but powerful Fabric mod that secures your Minecraft server by binding players to specific IP addresses. It keeps your server safe without the complexity.

🚀 Getting Started

Install: Drop the .jar into your server's mods folder.

Run: Start the server. The mod will create a config/ipwl.json file automatically.

Secure: You (the OP) can now start adding players.

🛠️ Main Commands

Whitelisting Players

/ipwl add <player> [ip]

Example: /ipwl add Steve 192.168.1.5 (Binds Steve to that IP).

Example: /ipwl add Alex (Allows Alex to join from any IP).

/ipwl remove <player> - Removes a player and kicks them if online.

/ipwl list - See who is on the list.

Managing IPs

/ipwl addip <player> <ip> - Add an extra allowed IP for a player (e.g., if they play from two houses).

/ipwl removeip <player> <ip> - Remove just one specific IP.

Security Tools

/lockdown on - Panic Button. Stops anyone new from joining.

/lockdown off - Resume normal operations.

/seen <player> - Check a player's last known IP and when they were last seen.

/connections - See a live list of all active connections and their uptime.

🛡️ Mod Admins (No OP Required)

You can give trusted moderators access to all IPWL commands without giving them full server OP.

/ipwl admin add <player> - Grants access to IPWL commands.

/ipwl admin remove <player> - Revokes access immediately.

/ipwl admin list - See who has mod admin access.

📝 Logs & Config

Cleaner Console
By default, the mod keeps your server console clean.

/ipwl logs verbose - Show everything (failed login attempts, retries, debug info).

/ipwl logs silent - Show only the important stuff (verified logins, kicks).

Configuration File
Located at: config/ipwl.json

You can toggle features like rate limiting, max connections per IP, and duplicate login checks in that file.
