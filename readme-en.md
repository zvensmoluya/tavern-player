<p align="center">
  <img src="assets/branding/exports/avatar-circle-512.png" width="180" height="180" alt="Tavern Player mascot with white hair and red eyes">
</p>

<h1 align="center">Tavern Player</h1>

<p align="center">
  <strong>Just characters. Just chat.</strong><br>
  Step into your character's story on Android.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-782C40?style=flat-square" alt="Android 8.0 and above">
  <img src="https://img.shields.io/badge/Status-In_Development-54434B?style=flat-square" alt="In development">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0--only-782C40?style=flat-square" alt="AGPL-3.0-only"></a>
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <strong>English</strong><br>
  <a href="https://github.com/zvensmoluya/tavern-player/releases">Download APK</a> · <a href="#getting-started">Get started</a> · <a href="docs/README.md">Documentation</a> · <a href="https://github.com/zvensmoluya/tavern-player/issues">Report an issue</a>
</p>

---

Tavern Player is an Android-native roleplay client built for the ecosystem that grew around SillyTavern.

Character cards carry lore, regex, presentation tricks, and years of community experimentation. We want to keep those capabilities without asking players to manage them all.

## Character, preset, model. Then chat.

| Character | Preset | Model |
| --- | --- | --- |
| What you want to experience | How you want it written | Who generates it |
| Bring a character card and its story | Use the built-in default or import a favorite | Connect your model service |

The complicated parts can stay underneath. They don't all need to become settings you manage.

> **Complex characters. Simple product.**

## What you can do today

| | Available capabilities |
| --- | --- |
| **Bring your characters** | Import V1 / V2 / V3 JSON or PNG / APNG character cards. Scan Tavern Shelf to receive cards, world books, or supported presets over the same local network. |
| **Explore their worlds** | Read character definitions, creator notes, and world books. Prepare character images for offline viewing. Import, edit, enable, and export global world books independently. |
| **Step in as yourself** | Set your name, description, and avatar. New conversations capture that identity; each character can have multiple independent conversations. |
| **Choose how replies read** | Use the built-in default or import an ST OpenAI / Chat Completion Preset. Adjust, restore, save copies, and export without losing imported fields. Connect services using supported OpenAI, Anthropic, and Gemini protocols. |
| **Experience authored interactions** | A native input bar accompanies a web message surface. Author HTML/JS runs within declared host capabilities, with compilation-free MVU/EJS preparation. |
| **Keep the conversation going** | Choose greetings, regenerate replies, switch variants, and edit history. Character snapshots and runtime state persist across process restarts. |

The project is in development. The single-character conversation loop is available; see the [product scope](docs/product-direction.md) and [web runtime contract](docs/web-runtime.md) for compatibility boundaries.

## Getting started

Download the experimental `.apk` from [GitHub Releases](https://github.com/zvensmoluya/tavern-player/releases), open it on Android 8.0 or later, and allow installation from that source when prompted. Choose the APK attachment, not the automatically generated Source code archive.

The experimental app uses a separate application ID and a debug signing key. It can coexist with a future release app, but data is not migrated automatically and a different signature prevents an in-place update. The first build passed compilation, lint, and signature checks; installation and chat on a physical device have not yet been verified. See the [experimental build guide](docs/experimental-build.md).

1. **Import a character.** Pick a PNG / JSON card in the character library, or scan Tavern Shelf. You can browse character content before connecting a model.
2. **Connect a model.** Configure your service connection and choose a model in the model settings. You can start with the built-in default preset.
3. **Start a conversation.** Open the character details and start a new conversation with one of the card's greetings.

Your default identity, global world books, and imported presets are optional. To build the app from source, see [Development and verification](#development-and-verification).

## Capability and compatibility details

<details>
<summary><strong>Content, presets, and conversation state</strong></summary>

- Character world books include disabled entries for reading. In a conversation, select original conditions, always injecting, or disabling per entry, and edit or restore text for that conversation alone.
- Global world books are independent of presets, support multiple enabled books, and start disabled on import. They can be read, edited, copied, deleted, and exported.
- Character images are saved in private app storage with pause and retry support. Preparation requires neither a model connection nor Native compilation.
- The built-in default preset can be edited and restored but not deleted. Imported presets and saved copies retain immutable restore points. The active preset is global: each generation captures a snapshot, and switching affects subsequent generations. History display uses the current preset's display regex and `show_thoughts`.
- Preset switches follow the actual `prompt_order`. Individual request parameters can be disabled while retaining local values; disabled parameters are omitted from compatible requests and exports.
- Sending applies world books, character regex, macros, prompt assembly, and context budgeting, mapping parameters to five supported OpenAI, Anthropic, or Gemini protocols. When the model catalog lacks capacity information, preset-declared budgets are used as unverified limits; token limits can be overridden per model ID.
- The single default identity supplies user attribution and persona macros. The preset determines whether and where its description enters the request; existing conversations retain their identity snapshot.
- History edits can preserve later messages or explicitly truncate them and restore runtime state from the edit point. Message variants, character snapshots, and runtime state are persisted.

</details>

<details>
<summary><strong>Web execution, Shelf, and experimental Native adaptation</strong></summary>

Importing and Shelf reception do not execute programs or call a compilation model. In a web conversation, enabled helper scripts and eligible author pages run under `player-web-1`. Ordinary HTML does not execute scripts; missing capabilities are reported. Web dependencies are saved as needed and character images are reused.

New conversations use the web message surface; existing ones retain their execution mode. Every character retains a native-mode entry without requiring Native adaptation first. Provider, endpoint, custom headers/body, and credential-shaped fields in presets remain inert imported content and do not change the player's connection.

Shelf reception is available from the character library. Android 17 requests local network permission before the first reception.

Experimental Native adaptation uses a model to interpret source, reuse MVU/EJS libraries, and produce JS projections and controlled native surfaces. Custom actions use declared host interfaces; persistent changes are saved before updating the UI. Action checkpoints are separate from message-end state and recover with their associated variants. Simple state bindings, existing draft forms, and manual adaptation import remain available.

An installable adaptation does not imply behavioral equivalence for an entire card. See the [architecture](docs/architecture.md) for interfaces and cancellation semantics, the [web runtime contract](docs/web-runtime.md) for execution and recovery limits, and the [documentation index](docs/README.md) for verification records.

</details>

## Documentation

Most technical documentation is currently in Chinese.

| To learn about | Start here |
| --- | --- |
| Current progress and document organization | [Documentation index](docs/README.md) |
| Product direction and supported behavior | [Product and compatibility scope](docs/product-direction.md) |
| Modules, data, and execution | [Implemented architecture](docs/architecture.md) |
| Author pages, scripts, and recovery limits | [Web runtime contract](docs/web-runtime.md) |
| Mascot artwork, app icons, and exports | [Brand assets](assets/branding/README.md) |

## Development and verification

For an installable trial APK, run `./gradlew.bat :app:assembleExperimental`. It uses a separate application ID and the local debug signing key, so it can coexist with the release app. Data is separate, and a different signing key prevents an in-place update. See the [experimental build guide](docs/experimental-build.md) (Chinese).

Build with the repository's Gradle Wrapper. Configure the Android SDK and make Node.js and npm available on the command line; the build prepares the web and MVU runtime assets.

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

<details>
<summary><strong>Regression checks and local compatibility fixtures</strong></summary>

```powershell
.\gradlew.bat :content-core:test :conversation-core:test :model-gateway:test :app:testDebugUnitTest
.\gradlew.bat lint assembleDebug
```

Optional local character-card compatibility checks accept `-DcommunityCard=<path>`. ST default preset import, export, and reimport checks accept `-DstDefaultPreset=<Default.json path>`. The `source/` directory contains local verification material and is excluded from version control.

</details>

## Open source

Report bugs and suggestions through [Issues](https://github.com/zvensmoluya/tavern-player/issues). See [CONTRIBUTING](CONTRIBUTING.md) for contribution guidance.

SillyTavern built the ecosystem. We're building the player.

The ecosystem is already open. **The player should be too.**

Copyright (C) 2026 Zven · [AGPL-3.0-only](LICENSE)
