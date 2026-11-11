## 1. 设置与运行时

- [x] 1.1 `McpSettings`：`port` 可变 + `setPort()`（1024–65535 校验、落盘），app 单测（保存/越界/默认读取、手工旧值兼容）
- [x] 1.2 `McpRuntime.setPort()`：写设置 + `writeUsbScript()` + `apply()`；`sync()` 在 `boundPort != settings.port` 时重绑
- [x] 1.3 `McpScreen` 高级区「监听端口」行 + 对话框（默认 8765 快捷项 + 数字输入校验），`ComputerAccessUi.onPortChange` 接线

## 2. 文案与文档

- [x] 2.1 字符串 ×10（端口行、对话框标题/说明/范围提示/恢复默认/应用）
- [x] 2.2 docs/mcp.md（或对应段落）补一句端口可改

## 3. 验证

- [x] 3.1 `./gradlew :core:test :core:cliTest :app:testDebugUnitTest` 全绿；i18n 校验、updateReadme 确认
- [x] 3.2 真机：改端口 9123 → 脚本/`mcp-usb.json`/通知/`curl :9123` 全链路验证 → 恢复 8765 再验一次；取证 `evidence/`
- [ ] 3.3 提交、推 fork、建 PR 关联 #392
