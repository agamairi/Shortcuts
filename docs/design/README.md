# Approved UI design — reference artboards

These `.dc.html` files are the APPROVED design for the app, signed off by the project owner.
They are the source of truth for layout, spacing, radii, colour and type. Read the actual
HTML and lift exact values — do not eyeball, round to a 4/8dp grid, or substitute Material
defaults.

| File | Screen |
|---|---|
| `Main.dc.html` | Dashboard — shortcut tile grid |
| `Builder.dc.html` | AI builder — hero step counter, a tap-to-cycle example sentence, "Add this step" |
| `Editor.dc.html` | Review steps — per-step cards, warning state, test run |
| `Describe.dc.html` | Reference only, not the current design (see "Later revisions") |
| `ManualBuilder.dc.html` | Manual creation — same sentence-with-slots language |
| `WidgetPicker.dc.html` | Add to homescreen — the one widget at three sizes |

They are static HTML mockups, not runnable app code. `Builder.dc.html` is interactive: tapping the
big example sentence cycles it, and "Add this step" commits whatever it currently shows — the hero
card's step count and the dot row both update live, which demonstrates the intended interaction.

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
- Manual creation uses the **same sentence-with-slots language** as the AI builder. The only
  difference is that the user picks every slot rather than the model proposing them, and the
  manual screen carries the colour-swatch row that sets the shortcut's colour.
- **`Builder.dc.html` was redesigned**, twice, to fix a real complaint: the slot-based ("madlib")
  template landing page had no value of its own — its only real function was the white "Describe
  the widget" pill, which led to a *separate* screen (`Describe.dc.html`) for free-text entry. The
  first pass replaced the whole page with a free-text-first layout (input box, quick-add chips, an
  itemized steps list) — a bigger departure than wanted; the owner asked to keep the original's
  look and feel instead of trading it away. The current version is a deliberate **mix of the two**:
  it keeps almost the entire original layout and rhythm — the header, the big centred hero card
  with the bolt icon, the dot row, the large centred sentence with its dotted-underline accent, the
  white pill, the AI-experimental notice — but rewires what's underneath each piece so it is no
  longer a dead end:
  - The template-cycling header pill and the two independent word-slots are **gone**. The header
    centre is now a plain label ("New shortcut"), matching `Editor.dc.html`'s header convention.
  - The hero card's step count and the dot row are **live**, not the original's static demo value —
    they reflect how many steps have actually been added so far (dots fill in one at a time, up to
    six).
  - The big sentence is now a single **tap-to-cycle example** ("Turn on Wi-Fi" → "Text Mum that
    I'm running late" → "Send a POST request to ifttt.com" → "Open Chrome" → …), keeping the exact
    tap-a-big-thing-to-change-it interaction the original slots had, but cycling through complete,
    realistic descriptions instead of isolated words.
  - The white pill is relabelled **"Add this step"**: it commits whatever the sentence currently
    shows as a step, instead of navigating to `Describe.dc.html`. A small "Remove last step" link
    appears once there is at least one step, so a mis-tap during a walkthrough isn't unrecoverable.
  - The header checkmark (save) dims and is inert until at least one step exists.
  - There is deliberately **no itemized steps list** on this screen — that stays the job of
    `Editor.dc.html` (Review steps), which the app already has. Keeping this screen's information
    density as low as the original was is the point, not an oversight.
  `Describe.dc.html` is kept in the canvas for reference but is superseded — nothing routes to it
  as a separate screen anymore.
