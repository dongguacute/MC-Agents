# MC-Agents

[简体中文说明](README_zh.md)

**MC-Agents** is a [Fabric](https://fabricmc.net/) server-side mod that connects your Minecraft server to large language models (LLMs). It provides in-game commands for AI chat, automatic [Minecraft Wiki](https://minecraft.wiki/) retrieval, per-player conversation memory, and optional control of Carpet-style fake players via structured AI directives.

---

## Requirements

| Component | Version / notes |
|-----------|-----------------|
| **Minecraft** | **Supported range**: `>=1.19.4 <1.23` (see `depends.minecraft` in `fabric.mod.json`). Targets **Java Edition** releases including **1.19.4**, **1.20.x** (e.g. 1.20.1–1.20.6), and **1.21.x** (from 1.21.1 onward on the 1.21 line); **not** 1.19.3 or older. |
| **Java** | 17 or newer (Gradle build targets Java 17) |
| **Fabric Loader** | ≥ 0.18.6 |
| **Fabric API** | Required; must match your game version (pick the correct `fabric-api` artifact from the [Fabric develop page](https://fabricmc.net/develop/) or Maven). Local dev defaults are in `gradle.properties` (e.g. `0.119.4+1.21.4`). |

Release builds (`.github/workflows/build.yml`) produce one `mc-agents-<minecraft-version>.jar` per supported **release** that has a published Fabric API mapping, and attach them to the GitHub Release.

### Optional but recommended for bot control

- **[Carpet Mod](https://github.com/gnembon/fabric-carpet)** (or another mod that exposes the same `player <name> rejoin` / `player <name> kill` behaviour).  
  MC-Agents does **not** declare Carpet as a Gradle dependency; bot join/leave is implemented by dispatching those commands on the server. Without Carpet (or equivalent), control directives will fail when executed.

---

## Supported languages (in-game UI)

Client-facing strings use Minecraft’s language files:

- **English** — `en_us`
- **Simplified Chinese** — `zh_cn`

Set the game language in the client to switch between them. AI replies follow the behaviour described in `ControlBot.md` (default: same language as the user’s latest message).

---

## LLM API compatibility

The mod speaks **OpenAI-compatible Chat Completions** over HTTP:

- **Default endpoint** — `https://api.openai.com/v1/chat/completions` (configurable).
- **Protocol** — JSON `POST` with `Authorization: Bearer <api_key>`, `Content-Type: application/json; charset=utf-8`, body includes `model`, `stream`, and `messages` (roles: `system`, `user`, `assistant`).
- **Streaming** — Server-Sent Events (`data: …` chunks) are used first; on HTTP **400**, the client falls back to a non-streaming request.
- **Models** — Any provider that implements the same schema (OpenAI, many proxies, compatible gateways) should work. DeepSeek-style model names are normalized internally (`deepseek-chat` / `deepseek-reasoner` aliases).

### Context window size

Total context length is **resolved via [OpenRouter](https://openrouter.ai/)** (`GET https://openrouter.ai/api/v1/models`) using `openrouter_api_key` if set, otherwise **`api_key`**, to match the configured `model` and drive token budgeting and progress display. Ensure at least one of these keys can access OpenRouter’s model list for your chosen model id.

---

## Configuration

### Where the file lives

The mod resolves the **server root** by walking upward from the world folder until it finds `server.properties` or `eula.txt`. The config file is always:

**`<server root>/config/macagent.txt`**

(Aligned with the usual Fabric `config/` layout; in dev, if the game directory is `run/`, that is `run/config/macagent.txt`.)

### Format

Plain text, one **`key=value`** per line:

- Lines starting with **`#`** and blank lines are ignored.
- On **first start**, if `macagent.txt` is missing, the mod writes a **default template** (with commented hints) and logs the path.

### Keys

| Key | Description |
|-----|-------------|
| `api_url` | Chat Completions URL (default: OpenAI). |
| `api_key` | Bearer token for `api_url`. |
| `openrouter_api_key` | Optional separate key for OpenRouter `/models`; if empty, `api_key` is used. |
| `model` | Model id (default in code: `gpt-4o-mini`). |
| `max_context_tokens` | **Ignored** — total context is **auto-detected** from OpenRouter’s model metadata (see [Context window size](#context-window-size)); if present, a warning is logged. |

### Legacy paths (migration)

If `config/macagent.txt` does not exist yet, the mod may **copy** an existing file from a legacy location — for example the **old** root-level `macagent.txt`, under `agentsdata/`, next to the world folder, or inside the world directory. Check the server log for `Migrated config file from …` when this happens.

### Applying changes

Reload without restart: **`/agent reload`** (permission level **2**).

> **Security:** Keep `macagent.txt` out of public backups and version control; it contains secrets.

---

## System prompt and bot records

- **Control bot behaviour** is defined by **`src/main/java/com/mcagents/prompt/ControlBot.md`** (loaded from the server working directory: the mod searches upward from the world folder for that path).
- **Bot name ↔ tag** mappings are stored in **`config/macagent/agent_records.json`** (JSON array of `{ "bot_name", "tag" }`). The AI receives a truncated copy of this library in the system prompt for join/leave decisions.

Legacy paths may be migrated automatically (old root `macagent/`, `agentsdata`, world `data/`, etc.) — see `AgentConfigLoader` / `Recordbot`.

---

## Commands (players)

| Command | Description |
|---------|-------------|
| `/agent ask <prompt>` | Send a prompt to the LLM with per-player conversation history. Supports **compound prompts** (multiple lines, or separators like `然后`, `；`, `+`, etc.) executed **sequentially**. |
| `/agent <prompt>` | Same as `/agent ask <prompt>`. Literal subcommands (`search`, `new`, `record`, …) are matched first, so this only applies when the first token is not a reserved keyword. |
| `/agent search <query>` | Fetch Minecraft Wiki excerpts (EN/zh) and ask the model to summarize. |
| `/agent new` | Clear the current player’s conversation state. |
| `/agent record <bot_name> <tag>` | Append a bot record. |
| `/agent record remove …` | Remove records (by bot+tag, by bot name, or all). |
| `/agent botlist` | List recorded bots. |
| `/agent reload` | Reload config (ops / permission level 2). |

`ask`, `search`, and `new` require a **player** executor (not console-only in the current implementation).

---

## Features (summary)

- **OpenAI-compatible chat** with **SSE streaming**, optional non-streaming fallback, and handling of `reasoning` / `thinking`-style fields where present.
- **Per-player chat history** with rough token estimation and a **context usage bar** when max context is known.
- **Minecraft Wiki integration** — MediaWiki API against **minecraft.wiki** and **zh.minecraft.wiki** (auto pick by Latin vs CJK in the query; fallback to the other wiki if needed).
- **Knowledge-style `/agent ask`** — For wiki-like questions, the server runs a **keyword-extraction** call (`WIKI_QUERY: …`), **searches** Minecraft Wiki, injects excerpts, then the model answers **once** from that context (no extra “answer first, wiki supplement” round-trip from the server).
- **`[MC_WIKI_SEARCH]` lines** — If the model outputs them, the **first** assistant message is **not** shown to the player and is **not** committed; the server runs **Minecraft Wiki** search (up to three queries), then requests **one** final answer that is shown and stored. The final reply does **not** trigger further wiki rounds (no chaining).
- **Compound prompts** — “Conversation complete” and **context usage** messages are shown **once**, after the **last** sub-prompt finishes.
- **Carpet bot control** — If the model outputs lines such as `[CONTROL_BOT] join <tag_or_name>` or `[CONTROL_BOT] leave …`, the server parses them and runs Carpet `player` subcommands (`rejoin` / `kill`), with **rate limits** and **cross-player conflict avoidance** for the same bot name. Targets resolve by **tag** from `agent_records.json` or direct **bot name** (3–16 chars, `[A-Za-z0-9_]`).
- **Sanitization** — Control directives and wiki directive lines are hidden from normal displayed text; prompt-leak patterns are reduced for “thinking” streams.

---

## Data layout (typical)

```
<server root>/
  config/
    macagent.txt            # API config
    macagent/
      agent_records.json    # bot_name / tag records
```

---

## Building

```bash
./gradlew build
```

The remapped mod JAR is under `build/libs/`. CI (`.github/workflows/build.yml`) runs `./gradlew build` on release events.

---

## Project metadata

- **Mod id:** `mc-agents` in `fabric.mod.json` (some docs may still reference the older internal name `modid`).
- **Maven group / version:** see `gradle.properties` (`maven_group`, `mod_version`).
- **Author:** [Cherry Fu](mailto:me@dgct.cc) (`fabric.mod.json`).

---

## License

This project is released under the **MIT License**. See [`LICENSE`](LICENSE) in the repository root (the Gradle `jar` task bundles it into the built JAR).
