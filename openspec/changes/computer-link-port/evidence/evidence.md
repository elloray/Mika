# computer-link-port 的验证取证（issue #392）

分支 `feat/392-computer-link-port`（基于 main `8d61573e`）。

## 单元测试（全绿）

- `:core:test :core:cliTest :app:testDebugUnitTest` 通过；i18n 校验（2187 资源 ×10 语言）、
  `updateReadme`（无差异）通过。
- 新增用例（`McpSettingsTest`）：
  - `portChangePersistsAndReloads`：改 9123 持久化，重读生效，不影响开关
  - `portOutsideRangeIsRejected`：1023/65536/0/-1 抛 IllegalArgumentException，不落盘
  - `handEditedPortInFileStillRead`：改动前手工写进 mcp.json 的 port 继续按它读（存量兼容）

## 真机验证（华为 Mate X2，TET-AN50，2026-11-08）

- 包：`:app:assemblePerf` arm64 perf 包，`adb install -r` 覆盖安装（0.1.38 → 本分支）。
- 验证全程 USB 电脑连接开着（经 `connect-computer.sh` 引导），即热路径。

| # | 步骤 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 基线：默认端口引导连接 | `mcp-usb.json` port 8765，`adb forward tcp:8765` 后 `/api/v1/tasks` 返回正常 | — |
| 2 | 设置 › 连接电脑 › 高级设置 | 新增「监听端口」行显示 8765，脚注「端口 8765。手机重启后 USB 访问会关闭。」 | 01-port-row-default.png |
| 3 | 点行开对话框 | 「默认（8765）」快捷项 + 自定义输入框（只收数字、5 位上限），说明文案齐全 | 02-port-dialog.png |
| 4 | 自定义 9123 应用 | 行值变 9123；`connect-computer.sh` 内容变为 `tcp:9123`；`mcp-usb.json` 的 `port`/`api_url`/`forward` 全为 9123；旧口 8765 连接拒绝（已热重绑）；`adb forward tcp:9123` 后 API 正常；前台服务通知「经 USB 连接,端口 9123(只在本机监听)」 | 03-port-changed.png |
| 5 | 恢复「默认（8765）」 | 行值回 8765，脚本/info/通知全部回到 8765，API 恢复可用 | 04-port-restored.png |

- 中途 8765 一度 curl 不通，排查为电脑侧 `adb forward` 在手机锁屏往返后丢失（`adb forward --list`
  只剩 9123），手机侧监听一直在（`/proc/net/tcp6` 回环 8765）——非应用问题，重建转发即恢复，
  记录于此避免误判。
- 截图均为 Mika 自身设置页，无个人信息；已逐张目检。
- 验证后已关闭 USB 连接、清掉全部 adb 转发，手机不留状态变更。
