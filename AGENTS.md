# Adaptive Prayer Slots developer guide

This is the living technical guide for the Adaptive Prayer Slots RuneLite plugin. It is intended for both human developers and AI agents. It documents the repository as it works today; it is not a wishlist or a replacement for reading the code involved in a change.

## Mandatory documentation rule

**Before editing this plugin, read this entire `AGENTS.md`. Every future code change must update `AGENTS.md` in the same patch whenever it changes behavior, configuration, files, assets, architecture, dependencies, build/run steps, limitations, performance characteristics, cleanup, or testing requirements. When behavior is removed, remove or rewrite its outdated documentation here in that same patch. Do not leave historical behavior described as current behavior.**

Documentation-only changes do not need to manufacture a code change. For a code change with no effect on this guide, explicitly verify that every section is still accurate before deciding that `AGENTS.md` needs no textual edit.

## Purpose and scope

Adaptive Prayer Slots keeps the locations of three combat prayer slots stable, principally for Last Man Standing (LMS) builds with different Prayer and Defence levels. It replaces an unavailable high-level prayer in that prayer's normal slot with a usable lower-level prayer:

| Slot | Preferred prayer | Default fallback | Optional fallback chain |
| --- | --- | --- | --- |
| Melee | Piety | Ultimate Strength | Piety -> Chivalry -> Ultimate Strength |
| Ranged | Rigour | Eagle Eye | None |
| Magic | Augury | Mystic Might | None |

The same selection is applied to the normal Prayer tab and the Quick Prayer setup interface. The plugin changes live widget position and visibility; it does not click prayers, activate prayers, rewrite RuneLite's saved prayer order, or save its own layout.

Despite the LMS-focused name and Plugin Hub description, `LMS only` defaults to off. With that default, normal-account levels and permanent unlocks also affect the selected prayers. Users who want exclusively LMS behavior must enable `LMS only`.

## Repository map

### Root, metadata, and build files

| File | Responsibility |
| --- | --- |
| `AGENTS.md` | This mandatory living architecture, operations, and testing guide. |
| `README.md` | Deliberately short, user-facing explanation of LMS behavior. Keep technical implementation details here rather than expanding the README. |
| `LICENSE` | BSD 2-Clause license, copyright 2026 Endrit. |
| `icon.png` | Root-level Plugin Hub icon. It is a 48x72 PNG, the maximum dimensions accepted by Plugin Hub. It is presentation metadata and is not loaded by the plugin at runtime. |
| `runelite-plugin.properties` | Plugin Hub metadata: visual name, author, short description, search tags, plugin entry class, version, and standard-build declaration. |
| `plugin-hub-manifest.properties.example` | Template for a RuneLite Plugin Hub manifest entry. Its `commit` placeholder must be replaced with the full 40-character commit hash submitted to Plugin Hub. |
| `build.gradle` | Java build, RuneLite and JUnit dependencies, Java 11 target, version, and the local RuneLite `run` task. |
| `settings.gradle` | Gradle root project name, `adaptive-prayer-slots`. |
| `.gitignore` | Excludes Gradle output, IDE metadata, and IntelliJ module files. |
| `.github/workflows/build.yml` | GitHub Actions build on pushes, pull requests, and manual dispatch using Temurin Java 11 and `./gradlew build`. |
| `gradlew`, `gradlew.bat` | Generated Gradle wrapper launchers for POSIX systems and Windows. |
| `gradle/wrapper/gradle-wrapper.properties` | Pins Gradle 8.10 `-all`, its SHA-256, download timeout, and wrapper storage paths. |
| `gradle/wrapper/gradle-wrapper.jar` | Generated binary bootstrap for the Gradle wrapper. Do not hand-edit it. |

### Production Java sources

All production code is under `src/main/java/com/adaptiveprayerslots/`.

| File | Responsibility |
| --- | --- |
| `AdaptivePrayerSlotsPlugin.java` | RuneLite plugin entry point and all runtime orchestration: lifecycle, event subscriptions, level/unlock resolution, fallback selection, active-prayer preservation, normal and Quick Prayer widget discovery, rendering, caching, reorder compatibility, warning delivery, restoration, and redraws. |
| `AdaptivePrayerSlotsConfig.java` | RuneLite configuration group and its five user-visible settings. |
| `PrayerChoice.java` | Package-private metadata for every controlled prayer: display name, RuneLite `Prayer`, Prayer requirement, Defence requirement, and unlock category. It also implements the level eligibility predicate. The display-name accessor currently has no runtime caller. |
| `PrayerSwap.java` | Package-private definition of the three independently configurable slots, pairing each high prayer with its default fallback. |
| `PrayerUnlock.java` | Package-private categories used to choose the appropriate permanent-unlock varbits outside LMS. `PIETY` is shared by Piety and Chivalry. |
| `MeleeFallbackChain.java` | Public config enum for Ultimate Strength only versus Chivalry then Ultimate Strength. Its `toString()` values are the labels shown in RuneLite configuration. |

### Test sources

All test/development sources are under `src/test/java/com/adaptiveprayerslots/`.

| File | Responsibility |
| --- | --- |
| `AdaptivePrayerSlotsPluginTest.java` | Development launcher used by the Gradle `run` task. It loads the production class as a built-in external plugin, then starts RuneLite. It is a launcher despite its `Test` suffix; it contains no JUnit tests. |
| `PrayerSwapTest.java` | JUnit 4 unit tests for Prayer and Defence thresholds and the LMS-versus-normal effective-level rule. It does not exercise widgets, events, unlock varbits, fallback priority, or rendering. |

## Runtime architecture

`AdaptivePrayerSlotsPlugin` is a single RuneLite `Plugin` with injected `Client`, `ClientThread`, `ChatMessageManager`, config, and a provided config instance from `ConfigManager`. There are no overlays, panels, services, files, databases, or network calls.

The main runtime path is:

1. An event or lifecycle callback calls or schedules `applyCurrentSwaps()`.
2. The method requires the plugin to be running and the client to be logged in.
3. It determines LMS state from `VarbitID.BR_INGAME`.
4. If `LMS only` is enabled outside LMS, it restores ownership to the normal interface and stops.
5. It operates only when `VarbitID.PRAYERBOOK == 0`, the standard prayer book.
6. It resolves effective Prayer and Defence levels.
7. `applySwaps()` resolves the appropriate normal-prayer enum variant, detects unlocked core prayer reordering, chooses one prayer per enabled slot, and updates the normal and Quick Prayer widgets.
8. The chosen prayer is remembered in `displayedChoices` so an active fallback can survive a transition until it is turned off.

Each enabled slot is processed independently in enum order: melee, ranged, then magic. Disabled slots are removed from `displayedChoices` and otherwise skipped.

## Prayer metadata, eligibility, and priority

### Exact requirements

| Prayer choice | RuneLite prayer | Prayer level | Defence level | Permanent unlock category |
| --- | --- | ---: | ---: | --- |
| Ultimate Strength | `Prayer.ULTIMATE_STRENGTH` | 31 | 1 | None |
| Chivalry | `Prayer.CHIVALRY` | 60 | 65 | Piety |
| Piety | `Prayer.PIETY` | 70 | 70 | Piety |
| Eagle Eye | `Prayer.EAGLE_EYE` | 44 | 1 | None |
| Rigour | `Prayer.RIGOUR` | 74 | 70 | Rigour |
| Mystic Might | `Prayer.MYSTIC_MIGHT` | 45 | 1 | None |
| Augury | `Prayer.AUGURY` | 77 | 70 | Augury |

The level formula in `PrayerChoice.meetsLevels()` is:

```text
eligible_by_level = effective_prayer >= required_prayer
                 && effective_defence >= required_defence
```

The effective-level rule is intentionally different in and out of LMS:

```text
effective_level = in_LMS ? boosted_skill_level : real_skill_level
```

In LMS, the current boosted skill level represents the assigned character build, and permanent unlock varbits are ignored. Outside LMS, real skill levels are used, so temporary boosts and drains do not change the layout, and permanent unlocks are required for high-level/unlock-gated choices.

Outside LMS, unlock checks are:

- Piety and Chivalry: `KR_KNIGHTWAVES_STATE >= 8` or `HUMBLE_PIETY != 0`.
- Rigour: `PRAYER_RIGOUR_UNLOCKED != 0`.
- Augury: `PRAYER_AUGURY_UNLOCKED != 0`.
- Ultimate Strength, Eagle Eye, and Mystic Might: no unlock varbit.

### Selection priority

For every enabled slot:

1. Select the high prayer if it meets both level requirements and, outside LMS, its unlock requirement.
2. For melee only, if the configured chain is `CHIVALRY_THEN_ULTIMATE`, select Chivalry when Piety is unavailable but Chivalry is eligible.
3. Otherwise select the slot's default fallback. The fallback is returned without another eligibility check; the configured use case assumes the build can use Ultimate Strength, Eagle Eye, and Mystic Might.
4. Active-fallback preservation can temporarily override the newly desired selection as described below.

The default melee chain is therefore Piety -> Ultimate Strength, not Chivalry. The alternate chain is Piety -> Chivalry -> Ultimate Strength.

### Active-fallback preservation

When levels or unlock eligibility increase, the plugin does not hide a currently active fallback immediately. It retains the currently displayed prayer when all of these conditions are true:

- There is a remembered current selection for that slot.
- The desired selection has changed.
- The current selection is not the slot's high prayer.
- The current selection still belongs to the slot's currently controlled set.
- `client.isPrayerActive(current.getPrayer())` is true.

This means active Ultimate Strength or Chivalry can remain visible after Piety becomes eligible, and active Eagle Eye or Mystic Might can similarly survive their upgrade transition. Once the fallback is inactive, the next enforcement pass selects the newly eligible prayer. A high prayer is never preserved as a fallback when eligibility decreases.

Changing the melee chain can remove Chivalry from the controlled set, so Chivalry is not preserved after switching to the Ultimate-only chain.

## Configuration

RuneLite stores settings under config group `adaptivePrayerSlots`.

| Key | UI name | Default | Position | Runtime effect |
| --- | --- | --- | ---: | --- |
| `lmsOnly` | LMS only | `false` | 0 | If true, relinquishes the layout outside an active LMS game. |
| `swapMelee` | Adaptive melee slot | `true` | 1 | Controls the Piety/fallback slot. |
| `meleeFallbackChain` | Melee fallback chain | `ULTIMATE_ONLY` | 2 | Chooses Piety -> Ultimate Strength or Piety -> Chivalry -> Ultimate Strength. |
| `swapRanged` | Adaptive ranged slot | `true` | 3 | Controls the Rigour/Eagle Eye slot. |
| `swapMagic` | Adaptive magic slot | `true` | 4 | Controls the Augury/Mystic Might slot. |

Any change in this config group is deferred onto `ClientThread`. The callback restores the captured Quick Prayer baseline, invalidates all widget/enum caches, asks the normal prayer book to redraw, and reapplies the current selection. This redraw is what restores a just-disabled slot before the enabled slots are enforced again.

## Normal Prayer tab rendering

### Prayer-book enum selection

The standard prayer enum changes when Deadeye or Mystic Vigour is unlocked. The plugin creates a two-bit variant key:

```text
variant = (Deadeye unlocked ? 1 : 0)
        | (Mystic Vigour unlocked ? 2 : 0)
```

It maps variants `0`, `1`, `2`, and `3` to `PRAYERS_NORMAL`, `PRAYERS_NORMAL_DEADEYE`, `PRAYERS_NORMAL_MYSTIC_VIGOUR`, and `PRAYERS_NORMAL_DEADEYE_MYSTIC_VIGOUR`, respectively. The enum and resolved normal widgets are cached until the variant or interface state is invalidated.

### Widget discovery

For a `PrayerChoice`, `findPrayerWidget()` scans the selected prayer enum's item/object IDs. It reads `ParamID.OC_PRAYER_LEVEL` from each item definition and identifies the choice by its unique configured Prayer level. It then reads `ParamID.OC_PRAYER_COMPONENT` to resolve the live widget. Successful results are cached by `PrayerChoice`; missing results are not cached.

This relies on the controlled prayers having distinct Prayer-level values in the enum and on RuneLite/Jagex continuing to expose the two item params with these meanings.

### Visibility and position

For a slot, `applySwap()` first resolves both the high prayer and selected prayer. If either cannot be found, it makes no changes for that normal slot. Otherwise it:

1. Hides every nonselected prayer in that slot's controlled set.
2. Moves the selected prayer to the high prayer widget's `originalX` and `originalY`.
3. Shows the selected prayer.

The hide-first, show-last order is deliberate: during a normal completed pass, two controlled prayers should not be visible in the target slot at once. `setHidden()` and `setPosition()` compare existing state before mutating and call `revalidate()` only after an actual change.

The plugin does not retain a separate normal-widget baseline. It relies on RuneLite's prayer redraw script to restore the normal layout when configuration changes, control stops, or the plugin shuts down. Hidden fallback widgets may retain the target coordinates until that redraw, which is harmless while hidden.

## Quick Prayer setup rendering

Quick Prayer rendering controls three dynamic widgets per prayer:

- `target`: the click/interaction widget.
- `sprite`: the prayer image.
- `toggle`: the selection indicator.

`findQuickPrayerWidgets()` requires `InterfaceID.Quickprayer.BUTTONS` and exactly `prayerCount * 3` dynamic children. It finds:

```text
target_index = prayer enum key for the choice
sorted_index = count of prayers whose Prayer level is lower than the choice
sprite_index = prayerCount + 2 * sorted_index
toggle_index = sprite_index + 1
```

It rejects missing/out-of-range indices and caches successful triples by `PrayerChoice`.

Before Quick Prayer widgets are changed, each widget's original X, original Y, and self-hidden state are captured once in an identity-keyed `quickPrayerBaseline`. The selected triple is moved to the three baseline positions belonging to the high prayer. Nonselected controlled triples are hidden first; the selected triple is shown last. This mirrors the normal tab's no-double-display ordering.

`restoreQuickPrayerBaseline()` restores the captured position and hidden state of every widget, clears the baseline, and clears the Quick Prayer lookup cache. The baseline is restored on shutdown, config changes, leaving the plugin's control scope, and entering unlocked reorder mode. A Quick Prayer interface rebuild discards the old baseline and cache before new widgets are captured.

## Core Prayer reordering compatibility

The core RuneLite Prayer plugin exposes `Hide` or `Unhide` actions on prayer widgets while reordering is unlocked. Adaptive Prayer Slots checks the actions of all currently controlled normal-prayer widgets. If either exact, case-sensitive action string is found, it assumes reordering is unlocked and temporarily yields control.

On the first unlocked detection it queues one console message:

```text
Adaptive Prayer Slots: temporarily paused while prayer reordering is enabled.
```

Suspension clears remembered displayed choices, restores the Quick Prayer baseline, and invalidates caches. It does not force a prayer redraw from inside `ScriptPostFired`. This is critical: RuneLite scripts are not safely reentrant, and synchronously calling the prayer redraw script while handling a prayer script callback previously crashed the client. **Never add a synchronous `redrawPrayerBook()` call to `suspendForReordering()` or another path that can execute directly inside `onScriptPostFired()`.** Defer such work through `ClientThread.invokeLater` if a future change truly requires a script invocation.

While reordering stays unlocked, repeated passes return early and do not repeat the warning. When the `Hide`/`Unhide` actions disappear, the plugin clears suspension, resets the warning latch, and resumes adaptive rendering. Yielding control keeps all core-managed positions available for selection and dragging, including positions that would otherwise contain hidden replacements.

The action-string heuristic is an external coupling. If the core Prayer plugin changes its action labels or reorder implementation, detection may fail and must be updated.

## Lifecycle, events, and threading

RuneLite client/widget APIs must be used on the client thread. Event-bus callbacks used here are client-thread events; lifecycle/config work that may arrive elsewhere or needs a safe later turn is scheduled with `ClientThread.invokeLater`.

### Startup and shutdown

- `startUp()` sets `running = true` and schedules an initial `applyCurrentSwaps()`.
- `shutDown()` immediately sets `running = false`, then schedules cleanup: clear layout-control/suspension flags and displayed choices, restore the Quick Prayer baseline, invalidate all caches, and redraw the normal prayer book.
- The `running` guard prevents a queued or late event from applying new mutations after shutdown begins.

Shutdown cleanup is asynchronous because it must run on the client thread. There is no executor, thread, timer, or closeable resource owned by the plugin.

### Event subscriptions

| Event | Behavior |
| --- | --- |
| `GameTick` | While running and logged in, reapplies all enabled swaps. This is the final enforcement path against competing prayer redraws. |
| `StatChanged` | Reapplies when Prayer or Defence changes. Other skill changes are ignored. |
| `GameStateChanged` | Invalidates all caches and resets the warning latch. On login it schedules an apply; in other states it drops the Quick Prayer baseline because interface widget identities may no longer be valid. |
| `ScriptPostFired` at priority `-100` | Reacts after Quick Prayer initialization, prayer redraw, and prayer button update scripts. See the script-specific behavior below. |
| `ConfigChanged` | For this plugin's group only, schedules restoration, invalidation, redraw, and reapplication. |

Script handling:

- `QUICKPRAYER_INIT`: clears Quick Prayer baseline/cache because all dynamic children may have been rebuilt, then applies.
- `PRAYER_REDRAW`: clears normal widget cache because widget state/components may have been rebuilt, then applies.
- `PRAYER_UPDATEBUTTON`: applies without pre-clearing caches.

Priority `-100` lets the plugin enforce its state after ordinary subscribers to the same script event. The once-per-tick apply is intentional redundancy because both Jagex scripts and the core Prayer plugin can rewrite the same widgets.

### Leaving control scope

With `LMS only` enabled, leaving LMS calls `stopControllingLayout()`. If the plugin was controlling or suspended, it clears state, restores Quick Prayer widgets, invalidates caches, and redraws the normal prayer book. If it already owns nothing, it returns without a redraw. Entering LMS is detected by the next normal event/tick apply.

For a nonstandard prayer book (`PRAYERBOOK != 0`), `applyCurrentSwaps()` simply returns. The plugin does not modify alternate prayer books. It does not explicitly restore normal widgets at that exact branch because the normal interface is no longer the active prayer book; normal enforcement resumes on return.

## State, persistence, and ownership

The only persistent values are the five RuneLite config keys managed by `ConfigManager`. The plugin writes no files and stores no account data.

All runtime state is process-local:

| State | Meaning |
| --- | --- |
| `running` | Lifecycle guard. |
| `controllingLayout` | Whether adaptive rendering has claimed the current normal layout. |
| `suspendedForReordering` | Whether core prayer reordering currently owns the layout. |
| `reorderWarningShown` | Suppresses duplicate console warnings during one continuous unlocked interval. |
| `cachedPrayerBookVariant`, `cachedNormalPrayerBook` | Cached standard prayer enum selection. |
| `prayerWidgetCache` | Choice-to-normal-widget cache. |
| `quickPrayerWidgetCache` | Choice-to-Quick-Prayer-triple cache. |
| `displayedChoices` | Last selected choice per slot, used only for active-fallback preservation. |
| `quickPrayerBaseline` | Identity-keyed original Quick Prayer position/visibility snapshots used for restoration. |

RuneLite's core Prayer plugin owns its own saved order and hide/unhide preferences. Adaptive Prayer Slots neither reads nor rewrites that persistent configuration. It can show a selected fallback even if the user had manually hidden that prayer, because the adaptive render explicitly unhides the selected live widget. When this plugin relinquishes control, the core prayer redraw reasserts the user's core layout.

## Cache invalidation and performance-sensitive paths

`applyCurrentSwaps()` runs at least once per logged-in game tick and can also run several times around scripts or stat changes. Keep this path allocation-light and avoid new network, disk, reflection, or full-interface scans.

Current performance measures:

- The selected prayer enum is cached by the two-bit Deadeye/Mystic Vigour variant.
- Normal widgets and Quick Prayer triples are cached per `PrayerChoice`.
- Widget writes and `revalidate()` calls occur only when hidden state or position differs.
- The Quick Prayer baseline uses widget identity, which is necessary because rebuilt widget objects must not compare as a logical replacement for old ones.

Full cache invalidation occurs on game-state changes, config changes, stopping control, entering reorder suspension, shutdown, and prayer-enum variant changes. Script-specific invalidation is narrower where possible. Quick Prayer initialization must clear both its widget cache and old baseline; prayer redraw clears normal widget lookups.

The remaining repeated work per pass includes assembling controlled `EnumSet`s, checking the controlled widgets' actions, testing eligibility/unlocks, and applying up to three normal/Quick slots. Metadata scans happen only on cache misses. Any future caching optimization must still invalidate on interface rebuilds and enum-variant changes; retaining a widget across rebuilds risks mutating a stale object.

`redrawPrayerBook()` executes the prayer-book widget's `onVarTransmitListener` via `client.runScript`. Treat it as a relatively expensive and reentrancy-sensitive operation. It is used for lifecycle/config restoration, not every tick.

## Rendering guarantees and failure behavior

- For a successfully resolved slot, nonselected controlled prayers are hidden before the selected prayer is shown, minimizing any chance of both prayers appearing at once.
- Melee's controlled set contains two choices by default and three only with the Chivalry chain. Ranged and magic always contain two.
- Normal and Quick Prayer changes are independent. If one interface cannot be resolved, the other can still be updated.
- If the normal high or selected widget is missing, that normal slot is left unchanged for the pass.
- If the Quick Prayer container/shape/indices are invalid, that Quick Prayer slot is left unchanged for the pass.
- Widget caches store only successful resolutions, so a temporarily missing widget can be found on a later pass.
- The plugin does not create placeholder widgets or change grid dimensions; it reuses existing prayers and coordinates.
- There is no custom overlay or custom painting. All visual behavior comes from position/visibility mutations of RuneLite/Jagex widgets.

## Assets and user-visible text

The repository contains one asset: root-level `icon.png`, a 48x72, 32-bit PNG used by Plugin Hub as the plugin's listing icon. It is not referenced or loaded by the Java runtime. Plugin Hub accepts icons no larger than 48x72 pixels, so do not increase either dimension. There are no sound, font, overlay, or other runtime assets.

User-visible strings live in:

- `@PluginDescriptor` for the in-client name, description, and tags.
- `runelite-plugin.properties` for Plugin Hub metadata and version.
- `AdaptivePrayerSlotsConfig` for setting names/descriptions.
- `MeleeFallbackChain` for enum option labels.
- `REORDER_WARNING` for the console compatibility warning.
- `README.md` for the short user explanation.

When changing product naming, description, or tags, check both the annotation and `runelite-plugin.properties`; they are separate metadata sources and can drift.

## External dependencies and compatibility surface

Production has one declared dependency:

- `net.runelite:client:latest.release`, `compileOnly`. RuneLite supplies it at runtime.

Tests add:

- JUnit `4.13.2`.
- `net.runelite:client:latest.release` on the test runtime classpath so the development launcher can start RuneLite.

Repositories are local Maven, RuneLite's repository (restricted to `net.runelite` groups), and Maven Central. The build targets Java 11. There are no bundled runtime libraries beyond the plugin's classes.

`latest.release` keeps local builds aligned with current RuneLite, but it also means upstream API, gameval, enum, script, and widget changes can break a future build or runtime assumption without this repository changing. Important external contracts include:

- RuneLite event ordering and client-thread rules.
- `ScriptID` values/events for prayer redraw/update and Quick Prayer initialization.
- `InterfaceID` widget layouts.
- prayer enum keys/values and item params.
- relevant `VarbitID` meanings.
- core Prayer plugin `Hide`/`Unhide` action labels.
- Quick Prayer's three-sections dynamic-child layout.

## Build, test, and run

Prerequisites are a Java 11-compatible JDK and network access for the first Gradle/RuneLite dependency download. Use the checked-in wrapper rather than a system Gradle.

Windows PowerShell or Command Prompt:

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat run
```

POSIX shell:

```sh
./gradlew build
./gradlew test
./gradlew run
```

`build` compiles production/test sources and runs JUnit tests. `run` first builds test classes, then launches `AdaptivePrayerSlotsPluginTest` with `--developer-mode --debug`; this loads `AdaptivePrayerSlotsPlugin` into a development RuneLite client. JVM assertions are enabled for all `JavaExec` tasks.

Do not terminate an already running user client merely to launch another test client. If relaunching is necessary, preserve the user's existing client unless they explicitly authorize closing it.

GitHub Actions performs the Linux build with Java 11 on every push and pull request. The separate RuneLite Plugin Hub checks run in the Plugin Hub repository/submission and are not defined by this repository's workflow.

For a Plugin Hub submission, update the example manifest's placeholder in the submitted manifest copy to the exact full commit containing the code to build. The `runelite-plugin.properties` entry class must remain loadable and its version/metadata should match the intended release.

## Known limitations and maintenance hazards

1. Only the standard prayer book is supported.
2. The fallback returned at the bottom of `choosePrayer()` is not itself eligibility-checked. Very low/custom builds below the fallback requirements could receive a visible prayer they cannot activate.
3. Normal widget lookup identifies prayers by Prayer-level item params, so equal/reassigned upstream levels would make lookup ambiguous.
4. Quick Prayer lookup assumes exactly three dynamic children per prayer and a specific target/sprite/toggle ordering. Upstream layout changes cause a safe no-op, not an adaptive Quick Prayer layout.
5. Reorder detection depends on exact English `Hide` and `Unhide` widget actions.
6. This plugin and the core Prayer plugin mutate the same widgets. Tick/script re-enforcement and reorder suspension reduce conflicts but cannot guarantee compatibility with every future core change or another third-party widget-mutating plugin.
7. There are no widget-level automated tests. Event timing, widget rebuilds, restoration, core-plugin interaction, visibility, and crash regressions require manual client testing.
8. The unit tests cover level boundaries only; permanent unlock checks, fallback priority, active preservation, config transitions, and cleanup are not directly unit-tested.
9. Shutdown restoration is scheduled asynchronously on `ClientThread`; forced process termination cannot guarantee that cleanup callback runs. The next normal RuneLite interface construction should rebuild widgets regardless.
10. Normal layout restoration depends on the prayer-book `onVarTransmitListener` existing. If it is absent, `redrawPrayerBook()` is a no-op.
11. Do not invoke the redraw script synchronously from `ScriptPostFired`; this can crash the client through script reentrancy.

## Manual testing checklist

Run `build` first, then use the development `run` task and watch the client log for exceptions. Test with the core Prayer plugin enabled because coexistence is a core requirement.

### Basic selection and placement

- [ ] On a standard prayer book, verify Piety, Rigour, and Augury occupy their normal familiar slots when all level/unlock requirements are met.
- [ ] In LMS with insufficient levels, verify Ultimate Strength appears in Piety's slot, Eagle Eye in Rigour's slot, and Mystic Might in Augury's slot.
- [ ] Verify the original high prayer and fallback are never both visible for the same controlled slot after the interface settles.
- [ ] Verify the replacements are clickable and activate the intended prayers, not merely display the right sprite.
- [ ] Verify manually hidden fallbacks are shown while selected by this plugin.
- [ ] Verify each of the melee, ranged, and magic toggles independently disables only its own adaptive slot and restores the core layout.

### Thresholds and unlocks

- [ ] Test Prayer values immediately below, at, and above 70 for Piety, 74 for Rigour, and 77 for Augury, always including the Defence 70 boundary.
- [ ] Test Defence 69 versus 70 while Prayer is high enough.
- [ ] Outside LMS, verify unavailable permanent unlocks force fallbacks even with sufficient levels.
- [ ] Outside LMS, verify Knight Waves/Humble Piety, Rigour, and Augury unlock varbits allow their respective choices.
- [ ] In LMS, verify assigned boosted Prayer/Defence levels drive the layout and normal-account real levels/unlocks do not.
- [ ] Outside LMS, verify temporary boosts/drains do not change the layout because real levels are used.

### Melee chains and active transitions

- [ ] With `ULTIMATE_ONLY`, verify Piety falls directly to Ultimate Strength.
- [ ] With `CHIVALRY_THEN_ULTIMATE`, verify Piety is preferred at 70/70, Chivalry at 60 Prayer/65 Defence when its conditions apply, and Ultimate Strength below Chivalry's thresholds.
- [ ] Activate Ultimate Strength, transition to a Piety-eligible build, and verify Ultimate Strength remains visible and active until deactivated; then verify Piety replaces it on the next pass.
- [ ] Repeat active preservation for Chivalry with the alternate chain.
- [ ] Repeat active preservation for Eagle Eye -> Rigour and Mystic Might -> Augury.
- [ ] While Chivalry is active, switch the chain to Ultimate-only and verify the config redraw leaves a coherent Ultimate/Piety layout rather than preserving out-of-chain Chivalry.

### Quick Prayer interface

- [ ] Open Quick Prayer setup for both high-level and fallback LMS builds and verify target, sprite, and toggle all move together to the high prayer's slot.
- [ ] Select/deselect every controlled Quick Prayer and verify the visible toggle corresponds to the prayer actually being configured.
- [ ] Open, close, and reopen Quick Prayer setup repeatedly to exercise `QUICKPRAYER_INIT` rebuilds and cache invalidation.
- [ ] Change plugin configuration while Quick Prayer setup is open and verify old positions/visibility are restored before the new layout applies.
- [ ] Disable the plugin while Quick Prayer setup is open and verify all captured baseline positions and hidden states return.

### Core Prayer reorder compatibility

- [ ] Unlock prayer reordering and verify the console warning appears once for that unlocked interval.
- [ ] Verify all controlled positions, including otherwise empty/hidden replacement locations, can be selected and dragged.
- [ ] Drag all six default controlled prayers and Chivalry when applicable; verify no client crash or logged script exception.
- [ ] Leave reordering unlocked for several ticks and verify the plugin does not fight the drag layout or spam warnings.
- [ ] Lock reordering and verify adaptive placement resumes automatically.
- [ ] Unlock, lock, and unlock again; verify one warning appears for each distinct unlocked interval.
- [ ] Confirm that disabling the plugin or leaving its scope restores the core Prayer plugin's saved hide/order layout.

### Scope, lifecycle, and rebuilds

- [ ] With `LMS only` enabled, verify the plugin makes no layout changes outside LMS, applies after entering a game, and restores the core layout after leaving.
- [ ] With `LMS only` disabled, verify normal-account levels and unlocks select prayers as documented.
- [ ] Log out and back in, hop worlds, and enter/leave LMS; verify no stale widgets, duplicate prayers, or missing prayers.
- [ ] Enable and disable the plugin repeatedly with both the normal Prayer tab and Quick Prayer setup open.
- [ ] Test accounts with neither, one, and both Deadeye/Mystic Vigour unlocks to exercise all four prayer-enum variants.
- [ ] Switch to a nonstandard prayer book and back; verify the alternate book is untouched and normal adaptive rendering resumes afterward.
- [ ] Confirm no `runScript` reentrancy crash occurs during prayer redraws or reorder enablement.
- [ ] Review the RuneLite client log after the full pass for exceptions, invalid widget access, or script errors.

### Release checks

- [ ] Run `.\gradlew.bat build` on Windows or `./gradlew build` on POSIX and confirm all tests pass.
- [ ] Verify `runelite-plugin.properties`, `@PluginDescriptor`, config text, README, and this guide describe the same current behavior.
- [ ] Verify the release version and Plugin Hub manifest commit are intentional.
- [ ] Verify `icon.png` is a valid PNG no larger than 48x72 pixels and remains legible at Plugin Hub display size.
- [ ] Verify any added/removed source, test, workflow, metadata, or asset file is reflected in the repository map and relevant sections of this guide.
- [ ] Verify removed behavior has also been removed from this guide and the manual checklist.
