# 电脑连接（MCP）：让电脑上的 AI 工具把任务交给 Mika

简体中文 | [English](mcp.en.md)

> 从电脑使用 Mika，先看[从电脑使用 Mika](computer.md)：怎么在 CLI + Skill 和 MCP 之间选、怎么连。想在终端或通过 Skill 使用，见 [CLI 与 Skill](cli.md)。

打开「电脑连接」后，Mika 就是一个 MCP 服务器：电脑上支持 MCP 的 AI 工具（MCP 客户端）可以把一件事交给 Mika，Mika 在手机上用自己的方式去做（操作 App、用内置浏览器、处理文件），做完把结果交回去。电脑说「要办成什么」，怎么做由手机上的 Mika 决定。

有两种连法，各有各的开关和令牌，可以都开：

- **经 USB（推荐）**：手机用数据线连着电脑、开着 USB 调试，电脑上一条命令就能打开，不用碰手机。只在手机本机上监听，Wi-Fi 上连不到。
- **经 Wi-Fi 或私有网络**：在手机的设置里打开，默认关着。电脑和手机在同一个 Wi-Fi，或在同一个私有 VPN 网络（组网工具建的虚拟局域网）里就能连上，不用路由器端口转发；这也是远程使用 Mika 的办法。

## 经 USB 连接（推荐）

前提：电脑上有 adb，`adb devices` 里有这台手机；Mika 至少打开过一次（它要把脚本写到手机上）。

1. 在电脑上运行：

   ```
   adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh
   ```

   它先启动 Mika 的手机助手（和「用电脑启动」一样，已经在运行就跳过），再打开 USB 方式，最后打印连接信息，形如：

   ```json
   {
       "transport": "streamable-http",
       "url": "http://127.0.0.1:8765/mcp",
       "headers": {
           "Authorization": "Bearer <令牌>"
       },
       "token": "<令牌>",
       "port": 8765,
       "forward": "adb forward tcp:8765 tcp:8765",
       "boot": "17"
   }
   ```

2. 把电脑上的 8765 端口转到手机上：

   ```
   adb forward tcp:8765 tcp:8765
   ```

3. 在 MCP 客户端里添加一个 Streamable HTTP（也叫「HTTP」）类型的服务器：地址 `http://127.0.0.1:8765/mcp`，请求头 `Authorization: Bearer <令牌>`。配置文件的写法见下面「经 Wi-Fi 或私有网络连接」里的例子，把地址换成 `http://127.0.0.1:8765/mcp` 即可。

之后想再看连接信息：

```
adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json
```

打开以后：

- 通知栏里常驻一条「电脑连接已打开」；Mika 退到后台也一直开着。手机上的 设置 › 连接电脑 顶部先显示「等待电脑连接」，收到请求后才显示「已收到电脑请求」及时间。
- **拔插数据线**（或电脑上的 adb 重启过）之后，要再运行一次 `adb forward tcp:8765 tcp:8765`。令牌不变。
- 已经开着时再运行第 1 步的命令，令牌不变，已经配好的客户端照常能用。
- **重启手机后自动关掉**，要再运行一次第 1 步的命令；这时令牌会换新的，客户端配置里的令牌也要换上。

关掉（任一种都行，关掉后旧令牌立即作废，连接信息文件也删掉；下次打开换一个新令牌）：

```
adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh off
```

或者在手机的 设置 › 连接电脑 › 高级设置 里关掉「经 USB 连接」，或者点通知上的「关闭」（Wi-Fi 那种开着的话也一起关）。

脚本做的事也可以直接用命令做（打开后等一两秒连接信息文件才写好）：

```
adb shell am start-foreground-service -n com.wangzan101.mika/.mcp.McpService -a com.wangzan101.mika.mcp.USB_ON
adb shell cat /sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json
adb shell am start-foreground-service -n com.wangzan101.mika/.mcp.McpService -a com.wangzan101.mika.mcp.USB_OFF
```

## 经 Wi-Fi 或私有网络连接

### 打开

1. 打开 Mika 的 设置 › 连接电脑，选择「MCP」›「Wi-Fi / VPN」，点「开启网络访问」。网络访问默认关闭，切换引导不会自动开启。
2. 打开后通知栏里会常驻一条「电脑连接已打开」，表示 Mika 在后台监听；通知上的「关闭」可以直接关掉。
3. 选择电脑能访问的地址，然后点「复制配置」添加到 MCP 客户端。配置包含地址和令牌；「查看配置」默认收起，展开也只显示令牌占位符。
4. 客户端连接或刷新工具后，确认页面顶部出现「已收到电脑请求」及请求时间。

访问开关、令牌的复制与更换、远程亮屏与解锁都在「高级设置」中，默认收起。「CLI / Skill」与「MCP」共用 USB / Wi-Fi / VPN 连接方式选择。「CLI / Skill」只有安装和连接两步，connect 会验证连接；网络方式使用 HTTP 根地址和令牌文件，使用 CLI 不需要 MCP 配置。

令牌和服务商的 API Key 一样加密存在手机上。关掉开关，端口就关了（USB 那种还开着的话，只留本机上的监听）。

### 从电脑连接

- **同一个 Wi-Fi**：用列表里那个 Wi-Fi 地址（通常是 `192.168.*.*` 或 `10.*.*.*`）。
- **私有 VPN 网络**：手机和电脑都加入同一个网络后，用列表里那个网络的地址（这类网络常用 `100.64.*.*` 到 `100.127.*.*` 一段）。
- 手机走移动数据、或者对方是公网地址时，Mika 不接受连接。

在 MCP 客户端里添加一个「HTTP」（也叫 Streamable HTTP）类型的服务器，填地址，并加上请求头 `Authorization: Bearer <令牌>`。不同客户端的配置文件写法略有不同，大多是这个样子：

```json
{
  "mcpServers": {
    "mika": {
      "type": "http",
      "url": "http://192.168.1.23:8765/mcp",
      "headers": { "Authorization": "Bearer <令牌>" }
    }
  }
}
```

也可以先用命令行试一下（把地址和令牌换成自己的）：

```sh
curl -s http://192.168.1.23:8765/mcp \
  -H "Authorization: Bearer <令牌>" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

## 自托管远程连接

公网远程连接可使用自托管网关：在「MCP → 远程」配对后显式开启访问，复制 HTTPS 配置到支持 Authorization 请求头的客户端。手机主动连接，不需要同一 Wi-Fi 或 VPN。CLI / Skill 也可选择远程，共用手机的局域网访问令牌，由手机鉴权；网关不管理访问令牌，不提供 OAuth 流程。首版为单用户、单设备，部署见[自托管远程 CLI / MCP](dev/remote-mcp.md)。网关终止 HTTPS，可读取转发内容，请使用可信网关。

## 工具

| 工具 | 参数 | 做什么 |
| --- | --- | --- |
| `mika_run_task` | `goal`（必填）、`conversation`、`"request_id"`（可选） | 把一件事交给 Mika，马上返回任务编号 `task_id`。任务记在一个对话里，手机上的对话列表看得到。想接着上一件事说，就把上一个任务返回的 `conversation` 带上。 |
| `mika_task_status` | `task_id` | 任务此刻的状态、最近的进度、在等什么、做完时的回答。 |
| `mika_wait_task` | `task_id`、`timeout_s`（可选，默认 30，最多 50） | 等到状态变了（比如开始等你回答、做完了）或者到时间，再返回状态。进度说明变了不算，不会一步一返回。 |
| `mika_answer` | `task_id`、`text` | 回答 Mika 在这个任务里问的问题。 |
| `mika_steer_task` | `task_id`、`text` | 向 running 任务追加要求（最多 4000 字符），保留任务 ID 和上下文。返回 accepted 与 message_id，不代表已经执行。 |
| `mika_cancel_task` | `task_id` | 停下这个任务。 |
| `mika_list_tasks` | `limit`（可选，默认 10） | 电脑交来的最近几个任务，新的在前。 |

状态有这几种：

- `starting`：刚交给手机，还没开始。
- `running`：正在做，带着最近的进度说明。
- `queued`：还没开始，排在前面的任务后面（同时进行的任务已满，或这个对话里已有任务在做），轮到时自动开始；只能等或者停掉。
- `paused`：在等一个条件，比如手机锁屏了，解锁后自动接着做。
- `waiting_for_answer`：Mika 问了一个问题，用 `mika_answer` 回答（在手机上回答也行）。等太久没人回答，Mika 会当作没有回答接着做。
- `waiting_on_phone`：这一步只能由人在手机上处理，比如交给你亲手操作的一步。电脑这边没法代答，只能等或者停掉任务；手机上会照常弹出通知。
- `done`：做完了，带着回答；`verified` 表示回答核对过。
- `failed`：没做成，带着原因。
- `cancelled`：停掉了。

支持任务恢复的手机会在 `mika_run_task` 的输入结构里列出 `"request_id"`。调用方可先生成并保存一个小写 UUID，丢回执后用它调用 `mika_task_status`。同一编号、目标和对话参数只启动一次，换目标或换对话需要新编号。进程重启后仍能读终态，未完成的任务会报告 `failed` 和 `failure_kind=interrupted`。已收尾的记录保留 30 天、至多最近 1000 条。

建议的用法：`mika_run_task` → 反复 `mika_wait_task` → 遇到 `waiting_for_answer` 用 `mika_answer` → 直到 `done` / `failed` / `cancelled`。

追加要求后，用 `mika_task_status` 查看 `steers` 中对应 `message_id` 的去向：`received` 已收到、`joined` 已加入上下文、`not_applied` 结束或取消前未用上。正在执行的动作会先做完，追加不会撤销已做的事。排队/暂停、等待回答/审批/接管和终态均拒绝追加；收尾时未消费的远程插话不另开任务。响应丢失不要自动重发。旧 APK 的工具列表没有 `mika_steer_task`，需要升级手机 App。

## 远程亮屏与解锁

在手机的 设置 › 连接电脑 › 高级设置 中打开「允许远程亮屏与解锁」，默认关闭。经自托管网关连进来的客户端还要在远程页另开「允许经网关唤醒和解锁」（默认关），否则看不到这三个工具。没有密码的锁屏不需要填写凭据；PIN 或密码在这页的「锁屏凭据」中输入两次保存，电脑不能设置或读取它。

| 工具 | 参数 | 做什么 |
| --- | --- | --- |
| `mika_device_state` | 无 | 读取亮屏、锁屏、手机助手状态，以及是否允许远程解锁。 |
| `mika_wake_device` | 无 | 只唤醒主屏，不自动认证。 |
| `mika_unlock_device` | 无 | 唤醒并解除滑动锁屏，或使用手机上保存的正确 PIN/密码认证。 |

先调用 `mika_unlock_device`，返回 `status: unlocked` 且 `device.ready: true` 后再派任务；因锁屏而 `paused` 的任务在系统确认解锁后自动继续。`mika_wake_device` 的 `awake` 仅表示已经亮屏，不代表已经解锁。

- PIN 支持 4–16 位数字；密码支持 4–64 位英文字母、数字、空格和常见 ASCII 符号。图案锁屏、SIM PIN、无法识别的厂商凭据页不输入，返回 `unsupported_keyguard`。
- 输入 PIN/密码需要安卓 13 及以上：按键只投给系统锁屏界面，锁屏上方另有窗口（锁屏时显示的 App、来电等）时系统拒绝，凭据不会输进别的 App。安卓 12 只能解除滑动或无密码锁屏。
- 需先完成手机操作设置，内置手机助手和无障碍服务正常运行。有的手机还要在开发者选项里另外打开允许模拟输入的开关。
- 凭据加密保存在本机，不发给模型、电脑或日志。接口拒绝任何参数，包括 `pin`、`password` 等。
- 凭据提交前先持久化占位。失败、超时、取消或进程中断后，后续尝试返回 `attempt_blocked`，请在手机上检查并重新保存凭据；关开开关或重启 App 不会清掉这个保护。
- `disabled` 表示手机上未允许；`helper_unavailable` 表示手机助手未连接；`credential_required` 表示需在手机上保存凭据；`unlock_failed` 表示系统没有确认解锁，不会把按键注入成功当作解锁成功。
- 不支持重启后的首次解锁之前远程启动 Mika。重启后请先亲手解锁一次。这里不改变系统锁屏配置，不放行任务中的付款或确认。

## 安全

- **默认关闭**，打开后才监听；关掉立即停止。
- **USB 方式只在本机（127.0.0.1）上监听**，Wi-Fi 和别的网络上连不到，电脑经 adb 的端口转发进来。手机上别的 App 也能连本机端口，所以照样要令牌，而且 USB 的令牌只认本机来的连接。
- **只有电脑能打开 USB 方式、拿到它的令牌**：Mika 的这个服务只接受持有 `INTERACT_ACROSS_USERS_FULL` 权限的调用方，adb shell 有，普通 App 拿不到（这个权限也没法用 adb 授给别的 App）。令牌写在 Mika 的外部文件目录里（`/sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json`），安卓 11 起别的 App 读不到这个目录，只有 Mika 和 adb shell 读得到。
- **每个请求都要带令牌**。令牌是 32 字节的随机数，按常量时间比较；同一个地址一分钟内错 5 次，就先封一分钟。
- **不接受浏览器的请求**：带 `Origin` 请求头的一律拒绝，网页没法借你的浏览器碰到这个端口。
- **只接受私有网络**：两端都必须是局域网、私有 VPN 网络或本机的地址，公网地址来的连接当场断开。
- **花钱和确认只能在手机上**：电脑交来的任务里，付款、需要你同意的操作永远不会执行，不管「自主模式」开没开；交给你亲手处理的那一步也只能在手机上处理。`mika_answer` 只能回答问题，回答不了这些。
- **只碰电脑自己交来的任务**：电脑看不到、也停不了你在手机上发起的任务；电脑开的对话编号带固定前缀，接不上你手机上的对话。
- 每次运行的记录里写明是电脑（MCP）发起的。
- 传输是明文 HTTP，只适合在自己信任的网络里用；令牌别发给别人，怀疑泄露就「换一个」。

## 用不了时

- **列表里没有地址**：手机没连 Wi-Fi，也没连私有 VPN 网络。
- **状态写「没能开启」**：端口可能被别的 App 占了；关掉再打开试试。
- **USB 方式的脚本提示文件不存在**：先打开一次 Mika，等几秒再试；还是没有的话，手机上的 Mika 版本还不带这个功能。
- **经 USB 连不上**（`curl` 报连接被拒或断开）：再运行一次 `adb forward tcp:8765 tcp:8765`；手机重启过的话，先再运行一次打开的命令。
- **电脑上 8765 端口被别的程序占了**（转发报 `cannot bind`，或者连上后返回的不是 Mika 的内容）：电脑这边换一个空闲端口，比如 `adb forward tcp:18765 tcp:8765`，再把配置里的地址改成 `http://127.0.0.1:18765/mcp`。手机那边不用改。
- **返回 429**：同一个地址错的令牌试了太多次，等一分钟再试。经 USB 时手机上所有连本机端口的都算同一个地址。
- **电脑连不上**：确认两边在同一个网络；有的 Wi-Fi（比如访客网络）不让设备之间互相访问。
- **任务没开始**（交任务时就报错，或者过两分钟变成 `failed`）：打开一下手机上的 Mika 再试。安卓有时不让后台的 App 自己开始干活，打开过 Mika 之后就好了。
- **任务一直是 `paused`**：多半是手机锁屏了。先在手机上解锁，或在手机已允许远程解锁时调用 `mika_unlock_device`；仅调用亮屏不一定能解除锁屏。
