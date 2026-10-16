# DiscordChatExporter for Android (MVP)

A minimal Android port of DiscordChatExporter focused on one job: exporting a **direct message
conversation in full**, as **HTML, JSON or XML**, optionally with every attachment included.

## What it does

- Sign in through the Discord website (in an in-app WebView) or by pasting a token.
- Lists your open DMs and group DMs; closed DMs can be exported by channel ID or link
  (enable Developer Mode in Discord, long-press the DM, copy its ID).
- Exports the entire history, oldest to newest, using the same HTML layout and styling as the
  desktop app (dark or light theme): replies, edits, embeds, forwarded messages, polls,
  stickers, reactions, custom emoji, mentions, markdown and system messages.
- Formats:
  - **HTML** – same layout and styling as the desktop app, dark or light.
  - **JSON** – same schema as the desktop app's JSON export, so existing tools can read it.
  - **XML** – the same data as the JSON, with arrays as wrapper elements (`<attachments><attachment>…`).
- **Download attachments & media** is optional:
  - Off: one file; attachments stay as links to Discord's CDN (those links expire after a while).
  - On, HTML: either a **single HTML file** with every asset embedded (opens anywhere, offline;
    assets are stored once and decoded lazily as you scroll) or a ZIP.
  - On, JSON/XML: a **ZIP** with the data file plus a `<name>.<ext>_Files/` folder it links to,
    matching the desktop app's layout.
- Runs as a foreground service with a progress notification, so long chats keep exporting in
  the background. Exports can be cancelled; partial files are removed.
- Includes a viewer for single-file exports, plus Share / Open with.

## What's left out (compared to the desktop app)

Server channels, threads, TXT/CSV formats, date ranges, filters, partitioning,
syntax highlighting, Lottie sticker animation, Twemoji images for standard emoji (they render
with the device's emoji font) and invite previews.

## Code map

| Android | Ported from (DiscordChatExporter.Core) |
| --- | --- |
| `discord/Models.kt` | `Discord/Data/*` |
| `discord/DiscordClient.kt` | `Discord/DiscordClient.cs`, `Utils/Http.cs` (retries, rate limits) |
| `export/Markdown.kt` | `Markdown/Parsing/MarkdownParser.cs`, `Exporting/HtmlMarkdownVisitor.cs` |
| `export/DataWriter.kt` | `Exporting/JsonMessageWriter.cs` (also serialized as XML) |
| `export/HtmlRenderer.kt` | `Exporting/HtmlMessageWriter.cs`, `*Template.cshtml`, `ExportContext.cs` |
| `export/Assets.kt` | `Exporting/ExportAssetDownloader.cs` |
| `export/ChannelExporter.kt` | `Exporting/ChannelExporter.cs` |
| `src/main/assets/theme-*.css`, `head.html` | Extracted from `PreambleTemplate.cshtml` |

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```sh
cd DiscordChatExporter.Android
./gradlew testDebugUnitTest   # markdown + end-to-end export tests against a fake Discord API
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

CI builds the APK only when Android changes are merged into `prime` (see
`.github/workflows/android.yml`); the desktop and Docker workflows are manual-only in this fork.

The release build is signed with the debug key so it can be sideloaded directly; use your own
signing config before distributing it.

> Automating a user account is against Discord's Terms of Service. Use at your own risk.
