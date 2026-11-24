# Stash: Guidelines and FAQ (Verdicts)

Read this first. Everything here is already settled, so don't ask about it.

**Also read:** `RULES.md` (code rules) · `TASKS.md` (what's next) · `CHANGELOG.md` (what changed) · `API.md` (the server contract) · `TESTING.md`

---

## 1. What is this project, and what stage is it in?

A self-hosted server + website + Android app, built for a lightweight machine.

**Stage:** alpha. It reaches full release when the fundamentals and the headline features are done. Anything beyond that is optional QoL.

- **Fundamentals:** a working UI (website + server) that passes data to the backend, a password or API key to connect, and a view of what's stored.
- **Headline feature:** back up chats and display them in a WhatsApp/Telegram-style UI.
- **Future capabilities:** optional, treated as highlights.

## 2. Where does it run?

- **Development:** Debian VM, code on GitHub, APK built by GitHub Actions.
- **Production:** an Armbian machine.
- **Data:** on the internal eMMC (boot drive) today. Later, a second device shares its drive over the network and the server uses it, so never assume `STASH_DATA` is local or fast.

## 3. Hardware limits

| Resource | Spec |
|----------|------|
| RAM | 2 GB |
| CPU | S905X, 4 cores @ 1.5 GHz |
| Storage | 8 GB eMMC, including the OS |

- Stream, don't load whole files into memory.
- No heavy dependencies.
- Stdlib only unless I say otherwise.

## 4. Do I optimize?

Only when I say "optimize". Otherwise, just write lightweight code.

## 5. What's frozen / off-limits?

- API version 2 (extend it, never break it)
- The passing tests

Anything else gets added here when something breaks.

## 6. Code rules

See `RULES.md`. Don't repeat them here.

## 7. Limits

Max upload size is configurable via `STASH_MAX_MB`. Default: **10 MB**.

## 8. How to deliver work

- Send only the files you edited, each one complete. No snippets for me to paste in. I add and push the files by hand.
- If the session is near its token limit, send the unfinished files plus a short report: what's done, what's half-done, what you planned next. End that message with the tag `(forced sent, token limit is almost out)`.

## 9. Status and next steps

`TASKS.md` for what's next, `CHANGELOG.md` for what's done.

## 10. Access

LAN only for now. Remote access later through a VPN tunnel (Tailscale or NetBird). Still tell me about a serious security problem if you find one.

## 11. If something is unclear

Ask first, but make reasonable assumptions. Try the new or better idea, and say what you assumed.

## 12. Verdicts

| Date | Verdict | Why |
|------|---------|-----|
|      |         |     |
