# Contributing

English | [简体中文](CONTRIBUTING.zh-CN.md)

Help make Mika better. The easiest way is to add usage knowledge for an app or website, which needs no code; bug reports and code are welcome too.

## Add app / website knowledge (no code needed)

The easiest contribution: write an `APP.md` for an app or a `SITE.md` for a website, or improve an existing one. They are plain-text Markdown files with a header, under [`core/src/main/resources/knowledge/`](../core/src/main/resources/knowledge/); general how-to skills for a few kinds of tasks live under [`core/src/main/resources/skills/`](../core/src/main/resources/skills/). See [`docs/dev/knowledge-format.en.md`](../docs/dev/knowledge-format.en.md) for the format. Existing knowledge is in Chinese, but new files can be written in English.

Mika can get things done just by looking at the screen; for apps and websites with usage knowledge (where things are, common ways to do things, what to watch out for), it looks the knowledge up when needed and takes fewer wrong turns. The current list is in the collapsed section under "What it can do" in the [README](../README.md).

## Reporting issues

When opening an issue, please include your **phone model, Android version and Mika version** ("About Mika" (关于 Mika) in Settings), what you did, what you expected and what actually happened. Screenshots from "View steps" (查看过程) help a lot (hide anything private first). In the app, About › Send feedback can copy diagnostics for you to paste into the issue; results from Settings › Recovery & repair › Compatibility self-check can be filed as a [device report](https://github.com/wangzan101/Mika/issues/new?template=device-report.yml).

## Contributing code

PRs welcome. Start with [`docs/dev/architecture.md`](../docs/dev/architecture.md) (written against the current code, with class names and file paths) and [`docs/dev/roadmap.md`](../docs/dev/roadmap.md) (done, next, known issues). Please include unit tests with your changes. The developer docs are in Chinese for now.

### Build and test

Requires JDK 17 and the Android SDK (`sdk.dir` in `local.properties`, or the `ANDROID_HOME` environment variable). CLI tests also require Python 3.10+; select an interpreter with `-PmikaPython=/path/to/python3`.

```bash
./gradlew :app:assembleDebug                  # debug build
./gradlew :app:assemblePerf                   # build for judging smoothness: precompiled and non-debuggable like release, debug-signed, installs over the debug build
./gradlew :core:test :core:cliTest :app:testDebugUnitTest   # unit tests
python3 -m unittest discover -s tools/i18n -v              # translation tool tests (no JDK/Android SDK)
python3 tools/i18n/translations.py check                  # lightweight translation resource checks
./gradlew updateReadme                        # regenerate the built-in knowledge and provider lists in the READMEs
```

The debug build is not precompiled and its UI is several times slower; use the perf build to judge smoothness.

- The repository has two modules: `:core` is pure Kotlin and holds the main loop, tools, prompts and knowledge, and can be run on a computer with unit tests and a fake model; `:app` is the Android host.
- README sections between the `generated` markers are produced by `./gradlew updateReadme`; don't edit them by hand. Unit tests check that they are up to date.
- The README comes in several languages. The English `README.md` at the repository root is the original; the translations are in `docs/readme/`. `README.zh-CN.md` (Simplified Chinese) has the same generated sections as `README.md`; in `README.zh-Hant.md`, `README.ja.md`, `README.ko.md` and `README.es.md` the generated sections are replaced by one sentence pointing to the English or Simplified Chinese README. `README.en.md` at the root only points to `README.md`, kept so old links still work. Each translation starts with `<!-- translated-from: README.md @ <fingerprint> -->`, where the fingerprint is the first 12 hex digits of the SHA-256 of the root README.md with the generated sections removed. After you change README.md, `ReadmeTranslationsTest` lists the stale translations and prints the new fingerprint: update the translations to match, then replace the fingerprint at the top. Translations must also keep the same number of `## ` sections and the same local images as README.md. Native speakers are welcome to improve a translation with a PR.
- The sandbox's native binaries and bundled runtime are checked into the repository; to rebuild them see [`tools/sandbox/README.md`](../tools/sandbox/README.md).
- For translation key checks and a multi-language reference CSV without a JDK or Android SDK, see [`tools/i18n/README.md`](../tools/i18n/README.md). These lightweight checks cover all 10 current UI languages, including Arabic, but do not replace the JVM `StringResourcesTest` checks for exact placeholders, CLDR plural rules and language registration.
- For on-device evaluation see [`eval/README.md`](../eval/README.md).

### CI

Pull requests and pushes to `main` run on GitHub Actions ([`ci.yml`](workflows/ci.yml)); no secrets are required:

- `core (JVM)`: `./gradlew :core:test`
- `cli (Python)`: `./gradlew :core:cliTest`, plus `tools/i18n` unit tests and translation resource checks (Python 3.10+; CI uses 3.12)
- `app (Android)`: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
- `relay (Go)`: `./gradlew :core:relayTest`

Reproduce locally with the commands above. The repository pins text files to LF in `.gitattributes`: goldens, prompts and the README are compared byte-for-byte, so line endings that vary by platform (for example an old Windows checkout with `core.autocrlf=true`) make tests and goldens disagree. If your working tree is not LF, re-checkout or convert it back to LF before running the tests or `-PupdateSnapshots`.

### Model configuration details

- Three roles: the **main model** (does the work; must support tool calling), the **vision model** (describes the UI and locates things in screenshots; off by default on a fresh install, or choose "use main model"), and the **light model** (small jobs like judging outcomes, extracting memories and understanding intent; defaults to the main model).
- "Test and save" calls the provider’s public list-models endpoint once, then gives the model that would become your main model a test tool and asks it to call it (a few dozen tokens). Only if both work is the provider saved; otherwise you see why right away, including the server’s own error message. Which model is tried first: the provider’s own models (for Qwen, ones with “qwen” in the name), then ones the list says can call tools; embedding, rerank, speech and image models are skipped. If the tried model fails for a reason of its own (your account hasn’t activated it or has no access, there is no such model, or it doesn’t call tools), the next one is tried, up to 3: the one that passes becomes your main model and you’re told which ones didn’t; if none passes, the provider is still saved (the key works) without a main model, and you’re asked to pick an activated one from the list, which is tested before it’s used. Key, network and address problems still stop the save. If no models are listed (the provider may not offer a list, or the address or interface type may be wrong), you enter a model name and the test call uses it; it’s saved only if that works, and a missing model is reported by name with an example of what model names look like. If a custom address is a known provider’s official one, you’re offered that provider’s preset (one tap, your key is kept). The form always offers "Enter a model name yourself": with a name filled in, only that model gets the test call, whether or not the list works, lacks it, or fails (a wrong key included), and it’s saved only if the call works; without one, a listing error is shown as before and the model-name field opens. When picking a role, any provider takes a typed model name, tested the same way first and remembered as typed (tagged “Typed in”, kept across list refreshes); ones that were never tested show “Not tested” on the provider row, with “Test again” on its page. The form also always offers “Don’t test, save anyway”: it saves the provider from the interface type, address and key alone, without listing models or making a test call. Such a provider is marked “not tested”, and its page offers “Test again” or a refresh to list its models; a model name typed there does not become the main model on its own, and picking a main model on that provider still tests it first.
- Capability tags on the model list (tool calling, vision, context length) show what the API reports; anything it does not report falls back to the preset's initial values and is marked "unconfirmed". They are for reference only and never block anything. Vision can be set by the user with the "Can see images" switch in a model's "Use as" sheet (stored per provider and model name, and it beats both the list and the initial values); when the vision role uses the main model's own config (including "use main model"), the main model counts as seeing too. That is derived when the config is resolved, not written into the stored flags.
- "Vision grounding verified" lives in "Developer options" in Settings (visible in debug builds) and applies to the current vision model.

### Docs

The developer docs index and reading order are in [`docs/dev/README.en.md`](../docs/dev/README.en.md); the user docs index is [`docs/README.en.md`](../docs/README.en.md).

- [`docs/dev/architecture.md`](../docs/dev/architecture.md): overall structure.
- [`docs/dev/roadmap.md`](../docs/dev/roadmap.md): done, next, known issues and deferred items.
- [`docs/dev/knowledge-format.en.md`](../docs/dev/knowledge-format.en.md): format of app and website knowledge and skills.
- [`docs/dev/skill-scripts.md`](../docs/dev/skill-scripts.md): sandbox scripts that come with knowledge and skills.
- User-facing guides: [features in detail](../docs/features.en.md), [models and sign-in](../docs/models.en.md), [Ask and Autonomous](../docs/modes.en.md), [phone control setup](../docs/setup-guide.en.md), [FAQ](../docs/faq.en.md), [use Mika from a computer](../docs/computer.en.md), [computer CLI / Skill](../docs/computer-install.en.md), [computer access (MCP)](../docs/mcp.en.md), [disclaimer, data and privacy](../docs/disclaimer.en.md).

### Releases and updates (maintainers)

- Releases are published on GitHub Releases with tags `vX.Y.Z`, with the APK attached to the release.
- Releases are built from the `perf` build type, which is signed with the project's release key. Each release carries two APKs with the same version and signature: `./gradlew :app:assemblePerf` writes `app-universal-perf.apk` (all architectures) and `app-arm64-v8a-perf.apk` (arm64-v8a native libraries only, smaller) to `app/build/outputs/apk/perf/`. Upload **`app-universal-perf.apk` first**, renamed to `Mika-vX.Y.Z.apk`, then `app-arm64-v8a-perf.apk` renamed to `Mika-vX.Y.Z-arm64.apk`: older app versions take the first `.apk` asset. Current versions take `-arm64.apk` on devices that support arm64-v8a and the all-architecture APK elsewhere. Keep the all-architecture name unchanged; the install-by-computer guide and older app versions look for it.
- Update checks: each time you open the app it queries `https://api.github.com/repos/wangzan101/Mika/releases/latest` and no other address.
- After you tap "Download update" on the About page, the system download service fetches the APK in the background; you can watch progress, delete the download or retry after a failure, and it is still there after reopening the app. When it finishes, tap "Install update", allow Mika to install unknown apps the first time, then confirm on the system installer. Before installing, Mika checks the APK size, the SHA-256 published with the release asset, the package name, the version and the signing certificate, and the system installer verifies the signature again; a build you compiled and signed yourself cannot be installed over the official one without uninstalling first. If a release has no APK, the release page is opened instead.
