# Terminal font

Add a legally redistributable Nerd Font Mono regular face here as
`NerdFontMono-Regular.ttf`. `TerminalScreen` loads it through Compose's
`Font(path, assetManager, ...)`; the Android input field uses the same asset.
When the asset is absent, both components fall back to the platform monospace
font and the terminal remains usable.
