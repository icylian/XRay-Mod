# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Advanced XRay — a client-side Minecraft mod that highlights configured blocks (ores, lava) through terrain with colored line-box outlines. Ships for both NeoForge and Fabric from one codebase. Targets the latest Minecraft (see `gradle.properties`, e.g. `minecraft_version=26.2`) and Java 25 (`options.release = 25`).

## Build & Test Commands

- `./gradlew build` — build all modules (runs tests)
- `./gradlew :common:test` — run unit tests (JUnit 5; `common` is the only module with tests)
- `./gradlew :common:test --tests "pro.mikey.xray.core.scanner.ScanTypeTest"` — run a single test class
- `./gradlew :fabric:runClient` — launch a dev client on Fabric
- `./gradlew :neoforge:runClient` — launch a dev client on NeoForge

On Windows use `gradlew.bat` (or `./gradlew` from Git Bash). Requires JDK 25.

Releases are published by pushing a `v/*` tag (CI in `.github/workflows/`); publishing needs `SAPS_TOKEN` / `CURSE_DEPLOY_TOKEN` / `GITHUB_TOKEN` env vars, otherwise `publishMods` runs as a dry run.

## Architecture

Three-module Gradle build (`settings.gradle`). All mod logic lives in `common`; the platform modules are thin bootstrappers that each bundle `common`'s classes into their jar:

- `common/` — everything under `pro.mikey.xray`, including the shared `LevelMixin`
- `fabric/`, `neoforge/` — entrypoints (`XRayFabric` / `XRayNeoForge`), event registration, render-hook wiring
- Platform differences go through `XPlatShim` (resolved via `ServiceLoader` in `XRay.XPLAT`): `oreTag()`, `configPath()`, `isModLoaded()`

### Scan → render pipeline (the core loop)

1. **`core/ScanController`** (enum singleton) — orchestrates everything. Called every client tick via `requestBlockFinder(false)`; rescans when the player crosses a chunk boundary (`force=true` clears everything, used on toggle/config change). It computes the chunk square around the player, diffs it against already-known chunks, and submits `ChunkScanTask`s to a 4-thread daemon pool.
   - Radius semantics: `getRadius()` multiplies the configured `radius` by 3, and `getHalfRange()` halves that — so configured radius N scans roughly (2·⌊1.5N⌋+1)² chunks, larger than the config comment claims. Radius 0 = player chunk only.
2. **`core/ChunkScanTask`** — runs off-thread, scans a full chunk column (minY→maxY), matches blocks against `ScanStore.activeScanTargets()` (plus lava when `lavaActive`, minus the static `blackList`). Results land in `ScanController.syncRenderList` (`Map<ChunkPos, Set<OutlineRenderTarget>>`, synchronized — written by scanner threads, read by the render thread).
3. **`core/OutlineRender.renderBlocks()`** — the render hook, invoked from `RenderLevelStageEvent.AfterWeather` (NeoForge) or a mixin into the weather-pass lambda in `LevelRenderer` (Fabric). Builds one line-list `GpuBuffer` (VBO) per chunk from unit-cube edges of each `OutlineRenderTarget` (block pos + ARGB color), translated by camera position each frame. Drawn with `NO_DEPTH_LINES_PIPELINE` — depth test is `ALWAYS_PASS`, so outlines render through terrain. Per-chunk VBOs are invalidated through `chunksToRefresh` and rebuilt lazily.
   - Custom shaders live in `common/src/main/resources/assets/xray/shaders/core/` (`xray_lines.vsh/fsh`, a copy of vanilla `rendertype_lines` plus distance fade). The fragment shader reads two repurposed per-draw uniforms from `DynamicUniforms.Transform`: `ColorModulator.r < 0` marks the player's own chunk (colors are inverted in the shader), and `ModelOffset` carries the fade parameters (start, end, min alpha) read from config each frame — so tint and fade change without rebuilding VBOs.
4. **Live block updates** — common mixin `mixins/LevelMixin` hooks `Level.setBlock` → `ScanController.onBlockChange`, which incrementally adds/removes one target in an already-scanned chunk and flags that chunk's VBO for rebuild.

Threading rule: scanner threads must not touch GPU/GL. All buffer creation and draw calls happen on the render thread inside `OutlineRender`.

### Data & config

- **`Configuration`** (`xray-client.json`) — hand-rolled builder-style config (`ConfigValue`/`ValueBuilder`). Holds `radius`, `showOverlay`, `lavaActive`. Every `set()` rewrites the file.
- **`core/scanner/ScanStore`** (`scan-store.json`) — the user's scan targets: a list of `Category`s, each holding `ScanType` entries. Currently only `BlockScanType` (Type.BLOCK, matches one block by registry id). Colors are stored as strings (`rgb()`, `rgba()`, `hsl()`, `#hex`) and parsed to ARGB ints by `ScanType.parseColor` (covered by `ScanTypeTest`).
- **GUI** under `screens/` — `ScanManageScreen` (opened with `G`) is the main screen; helpers in `screens/helpers/`. HUD indicator is `HudOverlay`.

## Conventions

- Versioning follows Minecraft: `mod_version` starts with the MC version (e.g. `26.2.0.1`). Only the last two MC major versions are supported — porting to a new MC version is a routine task here (see git history: "port to 26.1", "port to 26.2") and usually means adapting the render pipeline/mixin hooks to renamed mappings.
- Rendering uses the modern GpuBuffer/RenderPass API (`RenderSystem.getDevice()`, `RenderPass`, `RenderPipeline.builder`) — no legacy GL calls. Custom pipelines must be registered on NeoForge (`RegisterRenderPipelinesEvent` in `XRayNeoForge`).
- User-facing text goes through translation keys in `common/src/main/resources/assets/xray/lang/`.
