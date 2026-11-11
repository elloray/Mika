# 免责声明与数据隐私

简体中文 | [English](disclaimer.en.md)

## Beta 说明

- 这是早期公开测试版，功能和界面还在快速变化，升级时可能有不兼容的改动。
- 目前主要在一台安卓 14 手机上测试过。别的安卓版本、别的手机上可能遇到没见过的问题，欢迎[反馈](../.github/CONTRIBUTING.zh-CN.md#报告问题)。
- Mika 会替你操作第三方 App 和网站，请先读下面的免责声明。

## 免责声明

- 用自动化方式操作第三方 App 和网站，可能违反它们的用户协议，有被限制功能、封号的风险。请自行判断、自担风险。
- 你同意的动作（以及自主模式下 Mika 替你做的动作）由你负责。重要的事请用询问模式，并检查结果（见[询问与自主](modes.md)）。
- 这是 Beta 软件，按「原样」提供，不做任何担保（详见 [LICENSE](../LICENSE)）。

## 数据与隐私

- **没有 Mika 的服务器。** 你的话、屏幕内容、截图只发给你自己配置的模型服务商。
- API Key、记忆和用户画像用安卓密钥库加密，保存在手机上。对话、运行记录、Token 用量都只存在本机。
- 检查更新只访问 GitHub。
- 内置浏览器访问的是你让它去的网站；沙箱默认联网，不需要可在设置里关闭。

## 许可证与第三方组件

Mika 以 [Apache License 2.0](../LICENSE) 发布，版权声明见 [NOTICE](../NOTICE)。

第三方组件保留各自的许可证，见 [THIRD_PARTY_NOTICES.md](../licenses/THIRD_PARTY_NOTICES.md)。其中：APK 里带有 PRoot（GPL-2.0-or-later，静态链接 talloc，LGPL-3.0-or-later）编译成的独立可执行程序 `libproot.so`、`libproot_loader.so`，Mika 只以子进程方式运行它们；源码地址与校验值在 [`tools/sandbox/sources.lock`](../tools/sandbox/sources.lock)，补丁在 `tools/sandbox/patches/`，可以用 `tools/sandbox/build-native.sh` 重新编译。沙箱的运行环境（Alpine Linux 3.22 的最小根文件系统和软件包）随 APK 发布，发布时附带对应源码包，做法见 [`tools/sandbox/README.md`](../tools/sandbox/README.md)。
