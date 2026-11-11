# 参与贡献

[English](CONTRIBUTING.md) | 简体中文

欢迎一起把 Mika 做得更好。最简单的是补一份 App、网站的使用知识，不用写代码；也欢迎报告问题、贡献代码。

## 补充 App / 网站知识（不用写代码）

最简单的贡献：给某个 App 写一份 `APP.md`、给某个网站写一份 `SITE.md`，或者改进已有的。它们是带头部的 Markdown 纯文本，放在 [`core/src/main/resources/knowledge/`](../core/src/main/resources/knowledge/) 下；几类事情的通用做法（技能）在 [`core/src/main/resources/skills/`](../core/src/main/resources/skills/) 下。格式见 [`docs/dev/knowledge-format.md`](../docs/dev/knowledge-format.md)。

没有任何说明，Mika 也能看着屏幕把事办成；写了使用知识（入口在哪、常见做法、要注意什么）的 App 和网站，Mika 用到时自动查阅，少走弯路。现有清单见 [README](../docs/readme/README.zh-CN.md) 里「能做什么」下面的折叠段。

## 报告问题

开 Issue 时请写上：**手机型号、安卓版本、Mika 版本**（设置里的「关于 Mika」），做了什么、期望什么、实际发生了什么。能附上「查看过程」里的截图更好（注意先遮掉隐私）。App 里「关于 › 反馈问题」可以复制诊断信息贴进 Issue；设置 › 恢复与修复 › 兼容性自检的结果可以用[设备报告](https://github.com/wangzan101/Mika/issues/new?template=device-report.yml)提交。

## 贡献代码

欢迎 PR。先读 [`docs/dev/architecture.md`](../docs/dev/architecture.md)（按代码现状写，带类名和文件路径）和 [`docs/dev/roadmap.md`](../docs/dev/roadmap.md)（已完成、接下来、已知问题）。改动请带单元测试。

### 构建与测试

需要 JDK 17 和 Android SDK（`local.properties` 里的 `sdk.dir`，或环境变量 `ANDROID_HOME`）。CLI 测试另需 Python 3.10+，可用 `-PmikaPython=/path/to/python3` 指定解释器。

```bash
./gradlew :app:assembleDebug                  # 调试包
./gradlew :app:assemblePerf                   # 看流畅度用的包：和发布版一样预先编译、不可调试，用调试签名，可直接覆盖调试包
./gradlew :core:test :core:cliTest :app:testDebugUnitTest   # 单元测试
python3 -m unittest discover -s tools/i18n -v              # 翻译工具测试（不需要 JDK/Android SDK）
python3 tools/i18n/translations.py check                  # 轻量翻译资源检查
./gradlew updateReadme                        # 重新生成 README 里的内置知识、服务商清单
```

调试包不做预先编译，界面会慢好几倍；判断流畅度请用 perf 包。

- 仓库有两个模块：`:core` 是纯 Kotlin，主循环、工具、提示词、知识都在这里，可以在电脑上用单测和假模型跑；`:app` 是安卓宿主。
- README 里夹在 `generated` 标记之间的段落由 `./gradlew updateReadme` 生成，不要手改；单元测试会检查它们没过期。
- README 有几种语言：仓库根目录的英文 `README.md` 是原文，译文都在 `docs/readme/`。其中简体中文 `README.zh-CN.md` 和 `README.md` 一样带生成段落；`README.zh-Hant.md`、`README.ja.md`、`README.ko.md`、`README.es.md` 的生成段落换成一句指向英文或简体中文 README 的话。根目录的 `README.en.md` 只是一句指向 `README.md` 的话，留着让旧链接还能用。每份译文开头有 `<!-- translated-from: README.md @ <指纹> -->`，指纹是根目录 README.md 去掉生成段落后的 SHA-256 前 12 位。改了 README.md 以后，`ReadmeTranslationsTest` 会列出过期的译文并给出新指纹：照着改动更新译文，再把开头的指纹换成新的。译文还要和 README.md 的 `## ` 小节数、本地图片保持一致。欢迎母语使用者直接提 PR 改进译文。
- 沙箱的原生程序和自带运行环境已在仓库里；重新构建见 [`tools/sandbox/README.md`](../tools/sandbox/README.md)。
- 不需要 JDK 或 Android SDK 的翻译 key 检查与多语对照 CSV 导出见 [`tools/i18n/README.md`](../tools/i18n/README.md)。轻量检查覆盖当前全部 11 种 UI 语言（含 Arabic 和 Persian），但不能替代 JVM `StringResourcesTest` 对精确占位符、CLDR 复数规则和语言注册的权威检查。
- 真机评测见 [`eval/README.md`](../eval/README.md)。

### CI

PR 和合入 `main` 的提交会在 GitHub Actions 上跑（[`ci.yml`](workflows/ci.yml)），不需要配置任何密钥：

- `core (JVM)`：`./gradlew :core:test`
- `cli (Python)`：`./gradlew :core:cliTest`，另跑 `tools/i18n` 单元测试和翻译资源检查（Python 3.10+；CI 使用 3.12）
- `app (Android)`：`./gradlew :app:testDebugUnitTest :app:assembleDebug`
- `relay (Go)`：`./gradlew :core:relayTest`

本地按上面的命令逐项复现即可。仓库用 `.gitattributes` 把文本文件固定成 LF：金样、提示词和 README 都按字节比较，换行随平台变（例如 `core.autocrlf=true` 的旧 Windows 检出）会让测试和金样对不上。工作区不是 LF 时，先重新检出或把换行改回 LF，再运行测试或 `-PupdateSnapshots`。

### 模型配置细节

- 三个用途：**主模型**（办事，要支持工具调用）、**看图模型**（描述界面、看图定位；新装默认不用，可选「用主模型」）、**轻量模型**（判断结局、提取记忆、理解意图等小事，默认用主模型）。
- 「测试并保存」先调一次服务商公开的列模型接口，再带一个试用的工具，请会当主模型的那个模型调用它（用掉几十个 token）；都成了才保存，哪一步不成当场说原因（服务端的报错原样显示）。先试哪个：这家自己的模型在前（例如通义千问先试名字里有 qwen 的），再是列表明说能调用工具的，向量、重排、语音、画图这类不试。试的模型是它自己的问题（这个账号没开通或没权限、没有这个模型、不会调用工具）时接着试下一个，至多 3 个：通过的当主模型，并说一句先试的哪个没通过；都没通过也保存服务商（Key 是对的），不定主模型，说明原因、请你从列表里选一个开通了的，选的时候先试调用。Key、网络、地址不对照旧不保存。没列出模型时（可能这家不提供列表，也可能地址或接口类型填错了），先填一个模型名，再测试就用它试调用，通了才保存；找不到这个模型时说清是哪个、模型名大概长什么样。自定义填的是某家的官方地址时，提醒改用那家的预设（一点就换，Key 留着）。表单上随时可以「手动填写模型名」：填了就只用它试调用，列表列得出、列不出、列模型出错（包括 Key 不对）都不挡，试调用通了才保存；没手填时列模型出错照旧说原因，并展开模型名一栏。选用途时每家下面都能手填模型名，同样先试调用，通了记为手填（标「手填」，刷新列表不丢）；以前没试过的，服务商那一行提醒「没测试通过」，点进去可以「重新测试」。表单上也随时可以「不测试，直接保存」：只按接口类型、地址和 Key 存下这家，不列模型、不试调用；存下的这家标「没测试过」，在服务商页可以「重新测试」，也可以先刷新列出它的模型。手填的模型名也不会自动当主模型；在这家选主模型时仍会先试调用。
- 模型列表上的能力标签（工具调用、看图、上下文长度）：接口给了的照实显示，没给的沿用预设初值并标「未确认」，只作参考，不拦。看图这一项用户可以在模型的「用作」面板里用「能看图」开关自己定（按服务商和模型名记，比列表和初值都算数）；看图模型就是主模型那条（含「用主模型」）时主模型也算能看图，这是取配置时推出来的，不改存着的标记。
- 「看图定位已验证」在设置里的「开发者选项」（调试版可见），对当前看图模型生效。

### 文档

开发文档的目录和阅读顺序见 [`docs/dev/README.md`](../docs/dev/README.md)；面向用户的文档目录见 [`docs/README.md`](../docs/README.md)。

- [`docs/dev/architecture.md`](../docs/dev/architecture.md)：整体结构。
- [`docs/dev/roadmap.md`](../docs/dev/roadmap.md)：已完成、接下来、已知问题与暂缓。
- [`docs/dev/knowledge-format.md`](../docs/dev/knowledge-format.md)（[English](../docs/dev/knowledge-format.en.md)）：App、网站知识与技能的格式。
- [`docs/dev/skill-scripts.md`](../docs/dev/skill-scripts.md)：知识与技能自带的沙箱脚本。
- 面向用户的说明：[功能详细说明](../docs/features.md)、[模型与登录](../docs/models.md)、[询问与自主](../docs/modes.md)、[手机操作设置指南](../docs/setup-guide.md)、[常见问题](../docs/faq.md)、[从电脑使用 Mika](../docs/computer.md)、[电脑端 CLI / Skill](../docs/computer-install.md)、[电脑连接（MCP）](../docs/mcp.md)、[免责声明与数据隐私](../docs/disclaimer.md)。

### 发布与更新（维护者）

- 发布在 GitHub Releases，tag 用 `vX.Y.Z`，APK 作为附件挂在这个版本上。
- 发布包用 `perf` 构建类型打，它用项目的正式签名。每个版本挂两个 APK，版本号和签名相同：`./gradlew :app:assemblePerf` 在 `app/build/outputs/apk/perf/` 下出 `app-universal-perf.apk`（全架构）和 `app-arm64-v8a-perf.apk`（只带 arm64-v8a 原生库，更小）。**先传 `app-universal-perf.apk`**，改名为 `Mika-vX.Y.Z.apk`；再传 `app-arm64-v8a-perf.apk`，改名为 `Mika-vX.Y.Z-arm64.apk`。旧版 App 取附件里第一个 `.apk`；新版 App 在支持 arm64-v8a 的设备上取 `-arm64.apk`，其他设备取全架构包。全架构包的名字不要改，用电脑装的说明和旧版 App 都按这个名字找。
- 检查更新：每次打开 App 查一次 `https://api.github.com/repos/wangzan101/Mika/releases/latest`，不经别的地址。
- 关于页点「下载更新」后由系统下载服务在后台下载 APK，可查看进度、删除下载或失败后重试，重开应用仍可继续查看。下载完成后点「安装更新」，首次按提示允许 Mika 安装未知应用，再在系统安装页确认。安装前检查 APK 大小、发布附件提供的 SHA-256、包名、版本号和签名证书，系统安装器还会再验一次签名；自行编译、签名不同的版本不能直接覆盖成官方版，要先卸载。发布未包含 APK 时仍打开发布页。
