# FAQ

[简体中文](faq.md) | English

The README keeps only the most common questions; this page has all of them. For specific errors at each step of phone-control setup, see [Troubleshooting](setup-guide.en.md#troubleshooting) in the setup guide.

## Installing and getting started

**Do I need to pair wireless debugging if I only want to chat, browse and work with files?**
No. Pairing wireless debugging is only needed to operate phone apps. Chat, the built-in browser and working with files in the sandbox work as soon as the app is installed and a model is added, on Android 11 as well.

**Why recommend Android 14? What about older systems?**
Mika operates apps on a separate background screen. Phone control is available from Android 12, but background work can affect main-screen focus on Android 12–13. From Android 14 the background screen has its own focus and does not interfere with your typing on the main screen, so it is recommended. Web, sandbox and chat also work on Android 11.

**Not sure whether your phone works?**
Run Settings › Recovery & repair › Compatibility self-check. It takes about a minute, and you can send the result to us with one tap as a [device report](https://github.com/wangzan101/Mika/issues/new?template=device-report.yml).

**Where do APK updates come from?**
Only from this repository's [GitHub Releases](https://github.com/wangzan101/Mika/releases). Each release's notes list what changed. In the app, About › Check for updates can also download and install it directly.

## Phone control

**Wireless debugging turned itself off?**
Stock Android turns off wireless debugging when Wi-Fi disconnects or the phone reboots. "Turn on Wireless debugging automatically" (自动打开无线调试) under "Recovery & repair" (恢复与修复) in Settings is on by default: when needed, Mika turns it on and reconnects (this requires allowing Mika to modify system settings). The first time on a given Wi-Fi network, the system asks for permission; turn it on manually once and check "Always allow on this network". If it still cannot connect, go back to the "Set up phone control" (设置手机操作) wizard in Settings and pair again.

**Can't find wireless debugging on my phone?**
Some phone systems leave it out. You can start the phone helper from a computer with adb over a USB cable instead; see [Start it from a computer](setup-guide.en.md#another-way-start-it-from-a-computer-usb-cable) in the setup guide.

**The accessibility service got turned off?**
The system sometimes stops accessibility services (e.g. after the process is cleaned up). "Turn on accessibility for me" (替我打开无障碍) is on by default, so Mika turns it back on itself; if that fails, the Settings page prompts you to turn it on manually.

**Some app screens cannot be read or captured?**
Some screens (e.g. payment or password entry) block screenshots, so Mika cannot see them and will not try to read text on them. You need to take over on those screens and hand back to Mika when done.

## Models

**Can I use a model running on my own computer or LAN?**
Yes: pick "Custom" as the provider and enter its address on your local network; see [Models and sign-in](models.en.md#models-on-your-own-computer-or-lan).

**Can I use Mika without an API key?**
Yes, sign in with a ChatGPT (Plus / Pro), xAI / Grok or OpenRouter account; see [Models and sign-in](models.en.md#sign-in-with-an-account-no-key-needed).

## Using it from a computer

**Can I hand tasks to Mika from my computer?**
Yes. AI tools on your computer can use either CLI + Skill or MCP; normally you pick one. See [Use Mika from a computer](computer.en.md).

## Language

**Is the app in English?**
Yes. The app UI comes in 10 languages: English, Arabic, Simplified Chinese, Traditional Chinese, Japanese, Korean, Spanish, Portuguese, Russian and Indonesian. It follows your system language (English when your system language isn't one of them), and can be switched in Settings › Language & appearance › Language. The built-in app and website knowledge is written in Chinese, but that doesn't limit you: the model reads it fine, and you can talk to Mika in your own language.

## Privacy

**Where does my data go?**
There is no Mika server: what you say and what's on your screen go only to the model provider you set up. API keys and memories are stored encrypted on the phone; conversations and run records stay on the device. See the [disclaimer and data privacy](disclaimer.en.md).
