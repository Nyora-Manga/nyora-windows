# Nyora for Windows

Official Windows downloads for Nyora Desktop.

- [Download the latest release](https://github.com/Nyora-Manga/nyora-windows/releases/latest)
- [Installation and migration instructions](https://github.com/Nyora-Manga/nyora-desktop/blob/main/docs/INSTALL.md)
- [Desktop source, development and issues](https://github.com/Nyora-Manga/nyora-desktop)
- [All six desktop builds](https://github.com/Nyora-Manga/nyora-desktop/releases/latest)

Choose `win-x64` for Intel/AMD or `win-arm64` for Windows on ARM. Java, Chromium and the helper are bundled; no separate server is needed. Initial builds are unsigned, and macOS builds are not notarized. See the installation instructions and verify `SHA256SUMS.txt` before installing.

This repository contains download pointers only. Desktop development has moved entirely to Electron in **nyora-desktop**; the previous native desktop clients are deprecated. Back up your data before migrating.

A small workflow calls the central release automation to mirror this platform’s two architectures with SHA-256 verification. It checks approximately every 15 minutes after a complete six-target release; GitHub may delay scheduled runs. No application source or release scripts are maintained here.
