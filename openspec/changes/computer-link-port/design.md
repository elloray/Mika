# 设计：电脑连接端口可自定义（issue #392）

## Context

`McpSettings`（mcp.json）读取时已支持 `port`（1024–65535，默认 8765），但没有写入口；运行时全链路动态：
- `McpRuntime.startServer` 用 `settings.port` 建 `RemoteHost`（绑定失败抛异常 → `McpServerState.Failed`）
- `sync()` 在 USB 开着时用实际绑定端口发布 `mcp-usb.json`（`McpUsb.info(port,…)`，`forward` 命令同源）
- `writeUsbScript()` 用 `settings.port` 生成 `connect-computer.sh`（内容含端口，仅在变化时重写）
- CLI 每次调用从 `mcp-usb.json` 读端口，无需改动
- `McpService` 通知文案带端口，`onStartCommand` 每次重建

## Goals / Non-Goals

**Goals:** 高级区可改端口，保存即热生效（重绑/重写脚本/重发布/换通知）；存量手工配置完全兼容。

**Non-Goals:** CLI/Skill 侧、中继网关（relay）、自动换端口重试（占用时报错并显示即可，与现状一致）。

## Decisions

1. **`McpSettings.setPort(port: Int)`，`port` 从 `val` 改为 `@Volatile private var` + getter。**
   读取校验规则（1024–65535）原样用于 setter：`require` 之外不静默纠正——范围约束在 UI 对话框先拦，存储层兜底。`save()` 本来就写 `port` 字段，无需改格式。

2. **端口变化的重绑放在 `sync()`：`host != null && (hostListen != want || host!!.boundPort != settings.port)` 就 `stopListener()`。**
   `RemoteHost` 绑定精确端口（占用即抛），`boundPort` 即实际监听端口，可当变化检测依据。不引入额外的 hostPort 字段。

3. **入口 `McpRuntime.setPort(port)` = `settings.setPort` + `writeUsbScript()` + `apply()`。**
   `apply()` 沿用开关的既有路径：有一种开着 → `ensureRunning()` 重起服务 → `onStartCommand` 重建通知 + `sync()`（重绑 + 重新发布 info）；都关着 → 只存设置、重写脚本。`writeUsbScript` 幂等（内容没变不写盘）。

4. **UI 放在「高级」区（现在只有 USB/网络两个开关和端口脚注）。**
   把端口脚注升级成可点的「监听端口」行 + 对话框：预设「默认 8765」+ 数字输入（5 位以内、只收数字），应用按钮仅在 1024–65535 时可用。`ComputerAccessUi` 增加 `onPortChange: (Int) -> Unit` 传到底层，与其它回调同构。

## Risks / Trade-offs

- [运行中改端口，已连电脑的会话断] → 这正是改端口的目的（换到可用端口）；`mcp-usb.json` 重新发布 + 脚本提示新 `forward` 命令，电脑侧重连即可；与换令牌的破坏性一致且更小。
- [热重绑瞬间有短暂不可用] → 开关切换本就经停/起服务，路径相同；绑定失败时 `McpServerState.Failed` 照常显示原因，用户可再换。
- [范围校验两处（UI + require）] → UI 负责体验、存储层兜底，规则同源常量（1024..65535 已在读取侧）。

## Migration Plan

纯增量：默认行为不变；手工写过 `port` 的用户照旧。回滚即还原代码，mcp.json 多写的合法 port 字段旧代码也能读。
