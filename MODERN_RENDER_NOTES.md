# Modern GUI + Path Render

This project includes a visual refresh for the WanderBot ClickGUI and world-space path renderer.

## ClickGUI
- Modern dark panel with soft shadow and subtle borders.
- Compact sidebar navigation with active-page accent.
- Toggle switches instead of vanilla-style ON/OFF buttons.
- Value pills for pathfinding controls.
- Two-column megastreak selector.
- Compact live debug cards.
- Uses only vanilla Minecraft 1.8.9 rendering APIs; no font/image dependency was added.

## Path renderer
- Navigation route: blue glow + thin core line.
- Combat route: coral glow + thin core line.
- Removed noisy wireframe boxes on every path node.
- Current, next, and goal nodes use lightweight rings/markers.
- Target box uses a subtle translucent fill plus clean outline.
- Target guide uses a glow pass plus crisp core line.
- Draw calls are reduced for node markers compared with the previous all-boxes style.

## Validation
- Based on the previously fixed project ZIP containing Humanizer.range(int, int).
- ZIP integrity checked after packaging.
- javac parser-error scan found no parser-level errors in the modified sources.
- Full ForgeGradle compile could not be run in this environment because Forge/Minecraft dependencies are unavailable here.
