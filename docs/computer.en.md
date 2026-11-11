# Use Mika from a computer

[简体中文](computer.md) | English

AI tools on your computer (or you, in a terminal) can hand a job to Mika on your phone, check progress, answer its follow-up questions and get the result back. The computer says what to get done; Mika on the phone decides how. This page helps you pick an entry point and a connection, then points you to the step-by-step guides.

AI agents: go straight to the [agent quick path](agent-quickstart.en.md).

On the phone: install the APK ([Releases](https://github.com/wangzan101/Mika/releases/latest)), open Mika once and add a model. If Mika only needs to chat, browse the web and work with files, Android 11 is enough; to let it operate phone apps, also finish the phone-control setup in the [setup guide](setup-guide.en.md) (Android 12+).

## Step 1: pick an entry point (CLI + Skill or MCP)

Both entry points reach the same task service on the phone. **Normally one is enough**; you can use both if you need to connect different clients.

| Your situation | Pick | Read |
| --- | --- | --- |
| Your computer agent can run commands and supports Skills | **Skill**: includes its own CLI script, so no separate CLI install and no MCP configuration | [Install the Skill](computer-install.en.md#skill-install) |
| You want to submit tasks from a terminal or script | **CLI**: the `mika` command | [Install the CLI](computer-install.en.md#cli-install), [connection and task commands](cli.en.md) |
| You use a client that speaks MCP | **MCP**: Mika acts as an MCP server | [Computer access (MCP)](mcp.en.md) |

Not sure? If your agent supports Skills, use the Skill; otherwise use MCP.

## Step 2: pick a connection (USB or Wi-Fi / VPN)

Both entry points work over either connection. In Settings › Connect a computer on the phone, "CLI / Skill" and "MCP" share the same connection selector.

| | USB (recommended) | Wi-Fi / private VPN |
| --- | --- | --- |
| When | The phone is on a cable to the computer | No cable, or you're away from the phone (remote use) |
| How to turn it on | One command on the computer, without touching the phone; the CLI's `connect` does it for you | Turn on "network access" in Settings › Connect a computer on the phone; off by default |
| The computer needs | adb (Android platform-tools), with the phone listed as `device` in `adb devices` | To be on the same Wi-Fi as the phone, or in the same private VPN network |
| Where it listens | On the phone itself only; not reachable over Wi-Fi | Accepts only LAN and private VPN addresses; public addresses and mobile data are refused |

Each connection has its own switch and token, and both can be on at once. The connection is plain HTTP, so use it only on networks you trust, and don't share the token. Payments and anything that needs your consent can only be handled on the phone; tasks from the computer never approve them for you.

## Step 3: follow the guide

- **CLI or Skill**: install it with [install, update and remove](computer-install.en.md), then read [connection and task commands](cli.en.md).
- **MCP**: follow [Computer access (MCP)](mcp.en.md), which covers both USB and Wi-Fi / VPN step by step.
- **Let an AI agent on your computer set it up**: with the phone connected over USB, give it [Install with an AI agent on your computer](install-with-agent.en.md). It installs Mika on the phone first; if you also want computer access, it then sets up the entry point you chose.

## Task desk (`mika ui`)

The optional [Feishu connector](feishu.en.md) can also create/link a bot and exchange tasks and results in private chats; its computer process must stay online.

If you would rather not type commands, connect the phone and run `mika ui`. Your browser opens a task desk where you can start tasks, follow their steps, answer questions, add instructions and cancel tasks; recent tasks started from other entry points show up too. Reads pause while the tab is hidden by default and resume when you come back; steps that need you still have to be handled on the phone. The desk follows the browser's language: Simplified Chinese or English.

To get alerts while working in another window, open the phone connection details at the bottom left, turn on **Desktop notifications** and allow notifications in your browser. The desk alerts you when a task needs an answer or a phone takeover, finishes or fails. Click a notification to open the exact turn. Existing states and ordinary progress updates stay quiet.

Notifications are off by default. With notifications on, keep at least one desk tab open in the same browser. Background tabs can deliver alerts, and opening extra tabs does not duplicate them. Your computer must allow the browser to show notifications. If the computer sleeps, the browser suspends the page or the phone disconnects, the desk reads the latest state when the connection resumes. Closing all desk tabs or quitting the browser pauses alerts. Reopening the desk starts from the current state.

The sidebar groups the latest 50 remote tasks by conversation. Read each turn in the center and use the composer to continue after a turn ends. Select **View process** to expand the phone’s report and the updates observed in this tab above that turn’s reply. Select **Hide process** to collapse it. The composer keeps your current draft. Select **New chat** to start a separate conversation.

On the phone, open **Settings → Connect a computer → Advanced** and enable **Allow computers to view history** to include saved phone conversations in the sidebar. **Load more conversations** and **Load earlier turns** page through the archive. Tasks started on the phone can be viewed here and continued or answered on the phone. This permission is off by default and covers all conversations, run records and recorded Trace bodies over both USB and private-network connections.

Each turn’s **View process** can show saved run events and detailed Trace entries, including model attempts, tool calls, timing, input and cached tokens, and the first changed request block. **Read request and reply** loads redacted text one page at a time. Recordings update while the page is visible, and switching the inspected turn keeps the composer’s draft. Detailed recording must have been enabled beforehand in Developer options in a debug or perf build. Missing, rotated and incomplete records are identified in the view.

On a phone with task recovery, the desk matches a missing submission receipt by ID when the connection returns. Choose **Find task** if the task is outside the recent list. If the phone has no record yet, choose **Retry with the same ID**. The tab keeps the pending request across a reload until you recover it, retry successfully or dismiss it.

- With several ADB devices, choose one in the page or start with `mika ui --device SERIAL`; Wi-Fi / private VPN connections use the usual `--url` and `--token-file`.
- With only the Skill installed, run `python3 <skills-directory>/mika/scripts/mika_cli.py ui`.
- Attach files to a new task or a follow-up with **Attach files**, or paste a clipboard image into the composer. Select up to 9 files totalling 200 MiB. You can remove them before sending or send files without a message. The phone imports them into that conversation’s workspace and numbers duplicate names.
- When a task exports a result file on the phone, its turn shows a download button with the file name and size. Select it to save a copy. A missing or changed result reports an error. Attachments and downloads require a compatible phone and desk.
- If no browser opens, add `--no-open` and open the printed link in a browser on this computer.
- The desk listens only on `127.0.0.1` on a random port and prints a new link on every start. The secret in the link is what grants access, so keep it to yourself. Ctrl+C in the terminal closes the desk; tasks already on the phone keep running.

## Export a run record

When a task is stuck, the result looks wrong, or another tool on the computer needs to debug it, pull that turn's redacted record over the same connection. It is the same event stream as the phone's process view, redacted again, and it does not include screenshot files.

```bash
mika task export TASK_OR_RUN_ID --json
```

The JSON has `exportFormat` 1 and states `task_id`, `conversation_id` and run_id (`conversation_id` is the same value as `conversation` on `task status`). `partial: true` means the turn is still in progress and the events are what has been written so far. An unknown id fails with an explicit error. For one event per line: `mika task export ID --events`.

**View process** on the desk is still the progress observed in that page, not this full event stream. Downloading the same JSON from the desk, or attaching an OTLP Trace or screenshots, is left for later.
