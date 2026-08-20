# Approved UI design — reference artboards

These `.dc.html` files are the APPROVED design for the app, signed off by the project owner.
They are the source of truth for layout, spacing, radii, colour and type. Read the actual
HTML and lift exact values — do not eyeball, round to a 4/8dp grid, or substitute Material
defaults.

| File | Screen |
|---|---|
| `Main.dc.html` | Dashboard — shortcut tile grid |
| `Builder.dc.html` | AI builder — slot-based ("madlib") prompt |
| `Editor.dc.html` | Review steps — per-step cards, warning state, test run |
| `Describe.dc.html` | Free-text prompt entry, reached from "Describe the widget" |
| `ManualBuilder.dc.html` | Manual creation — same sentence-with-slots language |
| `WidgetPicker.dc.html` | Add to homescreen — the one widget at three sizes |

They are static HTML mockups, not runnable app code. `Builder.dc.html` is interactive: its
slots cycle through values on click, which demonstrates the intended interaction.

## Implementing them

The design tokens are already in Kotlin — use them, do not re-declare colours or type:

- `ui/theme/ShortcutsColors.kt` — `LightPalette` / `DarkPalette` (`ShortcutsPalette`), `TileColors`
- `ui/theme/ShortcutsType.kt` — `ShortcutsTypography`, Schibsted Grotesk bundled in `res/font`
- `ui/theme/ShortcutsTheme.kt` — `ShortcutsTheme(mode)`, palette via `LocalShortcutsPalette.current`
- `ui/theme/ThemeMode.kt` — `SYSTEM` / `LIGHT` / `DARK`

The mockups are light-mode only. Dark values already exist in `DarkPalette`; take every colour
from the palette rather than hardcoding the mockups' hex values, so both modes work.

The one intentional deviation from the mockups: they draw no device status bar, because a
painted one would sit under the real one. Let the system bars render normally.


## Later revisions (supersede the originals where they differ)

- The builder's background is **randomised per shortcut** from the six `TileColors`, and is the
  same colour the shortcut's tile and widget will use. It is not a fixed purple.
- The white pill on the builder reads **"Describe the widget"** and opens `Describe.dc.html`.
  "Inspire me" is gone, and so is the separate "Describe it instead" link at the bottom.
- Manual creation uses the **same sentence-with-slots language** as the AI builder. The only
  difference is that the user picks every slot rather than the model proposing them, and the
  manual screen carries the colour-swatch row that sets the shortcut's colour.
