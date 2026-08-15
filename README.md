# Adaptive Prayer Slots

Adaptive Prayer Slots keeps the positions of the three top-tier combat prayers useful when your character's effective Prayer or Defence level makes them unavailable, including LMS build overrides. Outside LMS it uses real levels, so drained or boosted stats never trigger a swap.

| Fixed slot | At the required level | Below the required level |
| --- | --- | --- |
| Melee | Piety (70) | Ultimate Strength, or Chivalry then Ultimate Strength |
| Ranged | Rigour (74) | Eagle Eye |
| Magic | Augury (77) | Mystic Might |

The replacement is the real lower-level prayer widget, not a copied icon, so clicking it activates the correct prayer. The regular Prayer tab and Quick Prayers setup are kept in sync. The plugin only operates on the standard prayer book. Each pair can be disabled independently in the plugin configuration.

Eligibility includes Prayer level, 70 Defence, and the permanent Piety/Rigour/Augury unlocks. LMS uses its temporary character build and ignores the account's permanent unlock state.

While enabled, this plugin controls the visibility of these six prayers and intentionally overrides the core Prayer plugin's hide setting for them. Other prayers and their custom positions are left untouched.

An active fallback is kept in the fixed slot during an eligibility transition and is only replaced after it is turned off. The optional **LMS only** setting prevents normal-account levels and unlocks from changing the layout. If core prayer reordering is unlocked, the plugin posts a one-time compatibility warning because dragging controlled prayers can be confusing.

## Development

Run the tests with:

```text
gradlew.bat test
```

## Plugin Hub preparation

The project includes standard Plugin Hub properties, a BSD 2-Clause license, and a GitHub Actions test build. After publishing the repository, copy `plugin-hub-manifest.properties.example` into your fork of `runelite/plugin-hub` as `plugins/adaptive-prayer-slots`, then replace its repository URL and commit placeholder. A compliant root `icon.png` still needs to be added before submission.
