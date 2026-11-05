# Contributing

Thank you for considering a contribution to a4miniprint.

## Project Principles

Keep the app small, local, and predictable:

- The final print paper remains A4.
- Use the Android system photo picker.
- Use the Android system print service.
- Do not implement a custom `PrintService`.
- Do not connect directly to printers or use IPP.
- Do not modify printer configuration or firmware.
- Do not add network, account, advertising, analytics, or tracking features.
- Do not add third-party image-loading or printing libraries.
- Do not save app-owned images, PDFs, or caches.

## Code Style

- Use Kotlin.
- Keep user-facing text in `res/values/strings.xml`.
- Use ViewBinding instead of `findViewById`.
- Run image decoding and PDF generation off the main thread.
- Recycle bitmaps after use.
- Use Chinese comments for key flow explanations and boundaries.
- Avoid speculative abstractions and unused dependencies.

## Before Submitting

Please check:

```bash
./gradlew assembleDebug --no-daemon
./gradlew assembleRelease --no-daemon
```

Also verify:

- A4 layout is unchanged unless the issue requires a geometry fix.
- Existing print options continue to work.
- The app has no unnecessary permissions.
- New UI text is localized in `strings.xml`.
- Documentation is updated if user-visible behavior changes.

## Issues

When reporting a bug, include:

- App version.
- Android or HarmonyOS version.
- Printer model.
- Whether you used Save as PDF or a physical printer.
- Layout mode, fit mode, and output precision.
- A non-private description of the unexpected result.

Do not upload private photos.
