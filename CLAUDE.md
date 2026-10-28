# ToDoApp (Android)

Native Android port of the web app in `../ToDoApp-js` (plain HTML/CSS/JS, Firebase-synced
todo + event app). Java + XML Views (no Compose, no Kotlin). The web app is the behavior
spec: when in doubt, read it.

## Source of truth

`../ToDoApp-js/` — port behavior from these, keeping field names identical for JSON compat:
- `script.mjs` (~2000 lines) — task logic, tabs, menus, projects, repeat, Firebase sync.
- `view-logic.mjs` — pure display helpers → ported to `logic/ViewLogic.java`.
- `project-logic.mjs` — pure project-tree helpers → ported to `logic/ProjectLogic.java`.
- `styles.css` — the "Graphite" design tokens (ported to `res/values[-night]/colors.xml`).

## Layout

- `model/` — `Item`, `RepeatRule`, `Project`. Field names mirror the web JSON exactly.
- `logic/` — pure, UI-free, unit-testable ports of the `.mjs` modules (`Dates`, `ViewLogic`,
  `Categories`, `ProjectLogic`). Keep new pure logic here.
- `data/` — `TaskRepository` interface + `LocalTaskRepository` (JSON file via Gson). This is
  the seam where Firebase sync slots in later; UI talks only to the interface.
- `ui/` — `TaskAdapter`, `AddItemSheet`, and other view code.
- `MainActivity` — wires tabs, rendering/sorting, row actions, theme, search, the add sheet.
- `res/` — Graphite drawables (`bg_*`, `chk_*`, `ic_*`), `colors.xml` (+ `values-night`),
  `themes.xml`, `item_row.xml`, `item_tab.xml`, `sheet_add_item.xml`, `activity_main.xml`.

## Conventions

- Dates are ISO strings `"yyyy-MM-dd"`, times `"HH:mm"` — same as the web app, so JSON
  import/export stays compatible. Use `java.time` (minSdk 29).
- Graphite colors are semantic tokens (`@color/bg`, `fg`, `muted`, `line`, `accent`, `warn`…)
  with day/night variants; never hardcode hex in layouts.
- Keep the data layer behind `TaskRepository`; don't reach past it from the UI.

## Build / run

- Build: `.\gradlew.bat :app:assembleDebug` (Java 17; SDK at `%LOCALAPPDATA%\Android\Sdk`).
- Emulator AVD on this machine: `Pixel_10a`.
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Namespace / applicationId: `com.karson.todoapp`.

## Port status

Full feature parity with the web app reached (verified on the `Pixel_10a` emulator):
- Phase 1 (core: date tabs, add sheet, complete/dismiss, local persistence, Graphite) — done.
- Phase 2 (projects: nested tree, collapse, counts, menus, date-tab opt-in) — done.
- Phase 3 (repeat engine: virtual occurrences, reveal time, stop date, complete-and-advance + undo) — done.
- Phase 4 (item menus: postpone; account menu with JSON import/export) — done.

Not yet built (the "couple other features" to come): **Firebase sync + Google sign-in**
(intentionally deferred — slots in behind `TaskRepository` as a `FirebaseTaskRepository`;
needs an Android app registered in Firebase console project `todoapp-js-5cbd2` for
`google-services.json` + SHA-1). Plus any other features the user names.
See the project memory note for the full phase plan and decisions.
