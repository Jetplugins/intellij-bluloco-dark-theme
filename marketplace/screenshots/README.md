# Marketplace screenshots

Run `./gradlew createMarketplaceMedia` on macOS or Linux to regenerate the complete media set from a real IntelliJ instance. Use `./gradlew createScreenshots` when only the still images are needed.

The task discovers every registered `*.theme.json` descriptor and produces:

- A labeled sample-code screenshot for each theme under `themes/`
- Raw editor, tool-window, completion, Appearance settings, and diff captures under `raw/<theme>/`
- Annotated comparisons containing all registered themes

Every PNG is exactly 1200 × 760 pixels with a consistent aspect ratio. The Settings capture opens **Settings → Appearance**, where users actually select the active Bluloco theme.

The complete task also writes a silent, 13-second H.264 demo to `../media/bluloco-demo.mp4`. It presents the editor, theme selection, completion, tool windows, and diff experience using the real Robot captures. `ffmpeg` is required for video generation.

The plugin's paid-product descriptor is omitted only from the test sandbox. Remote Robot verifies each active theme and its rendered colors before any image is written.

Raw per-theme captures are kept in the generated `raw/` subdirectory so visual regressions can be inspected independently of the side-by-side Marketplace images.
