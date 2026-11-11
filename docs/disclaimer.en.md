# Disclaimer, data and privacy

[简体中文](disclaimer.md) | English

## Beta notes

- This is an early public beta. Features and UI are still changing quickly, and upgrades may include breaking changes.
- So far it has mainly been tested on one Android 14 phone. Other Android versions and other phones may hit problems we have not seen yet; [reports are welcome](../.github/CONTRIBUTING.md#reporting-issues).
- Mika operates third-party apps and websites on your behalf. Please read the disclaimer below first.

## Disclaimer

- Operating third-party apps and websites through automation may violate their terms of service and carries a risk of restricted features or account bans. Use your own judgment, at your own risk.
- You are responsible for actions you approve (and for actions Mika takes on your behalf in Autonomous mode). For anything important, use Ask mode and check the results (see [Ask and Autonomous](modes.en.md)).
- This is beta software, provided "as is", without warranty of any kind (see [LICENSE](../LICENSE)).

## Data and privacy

- **There is no Mika server.** What you say, screen contents and screenshots go only to the model provider you configure.
- API keys, memories and your user profile are encrypted with the Android Keystore and stored on the phone. Conversations, run records and token usage stay on the device.
- Update checks only contact GitHub.
- The built-in browser visits only the sites you send it to; the sandbox is online by default, and you can turn it off in Settings.

## License and third-party components

Mika is released under the [Apache License 2.0](../LICENSE); see [NOTICE](../NOTICE) for the copyright notice.

Third-party components keep their own licenses; see [THIRD_PARTY_NOTICES.md](../licenses/THIRD_PARTY_NOTICES.md). In particular, the APK contains standalone executables `libproot.so` and `libproot_loader.so` built from PRoot (GPL-2.0-or-later, statically linked with talloc, LGPL-3.0-or-later); Mika only runs them as child processes. Source locations and checksums are in [`tools/sandbox/sources.lock`](../tools/sandbox/sources.lock), patches are in `tools/sandbox/patches/`, and they can be rebuilt with `tools/sandbox/build-native.sh`. The sandbox runtime (a minimal Alpine Linux 3.22 root filesystem and packages) ships with the APK, and the matching source packages are attached to each release; see [`tools/sandbox/README.md`](../tools/sandbox/README.md) for how.
