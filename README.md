# BootPatcher 🔧

> A GitHub Actions-powered Telegram bot that automatically patches your stock `boot.img` with the latest **BruhKernel** (android14-6.1) kernel.

## How it works

```
You → Send boot.img to @umbromomento_bot
        ↓
Bot → Uploads to transfer.sh (temp hosting)
        ↓
Bot → Triggers GitHub Actions workflow
        ↓
GH Actions → Downloads BruhKernel artifact (android14-6.1 SukiSU / KernelSU-Next / etc.)
           → Unpacks your boot.img with magiskboot
           → Swaps old kernel → new kernel
           → Repacks boot.img
        ↓
GH Actions → Sends patched boot.img back to you on Telegram 🎉
```

## Setup (one-time)

### 1. Fork / create this repo on GitHub

Push this codebase to a new public or private GitHub repo.

### 2. Add GitHub Secrets

Go to your repo → **Settings → Secrets and variables → Actions → New secret**:

| Secret name           | Value |
|-----------------------|-------|
| `TELEGRAM_BOT_TOKEN`  | Your bot token from BotFather: `REDACTED_BOT_TOKEN` |
| `BOT_GH_TOKEN`        | A GitHub Personal Access Token with `repo` + `workflow` scopes (used by the bot to trigger workflows) |
| `BRUHKERNEL_GH_TOKEN` | A GitHub PAT with `actions:read` + `repo` scopes to download BruhKernel artifacts |

> **Note:** `BOT_GH_TOKEN` and `BRUHKERNEL_GH_TOKEN` can be the same token if you use one PAT with all permissions.

### 3. Start the bot workflow

Go to **Actions → Run Telegram Bot → Run workflow**.

The bot will run for up to ~5h 50m on GitHub's free runners. Restart it manually when it stops, or add a scheduled workflow to keep it alive.

### 4. Use the bot on Telegram

Open **@umbromomento_bot** and:
1. Send `/start`
2. Upload your `boot.img` as a **file** (not photo/video)
3. Choose your kernel variant (SukiSU recommended)
4. Wait ~5 minutes
5. Get your patched `boot.img` sent back!

## Repository structure

```
BootPatcher/
├── .github/
│   └── workflows/
│       ├── patch-boot.yml   ← The main patcher (triggered by the bot)
│       └── run-bot.yml      ← Keeps the Telegram bot running on GH Actions
├── bot/
│   ├── bot.py               ← Telegram bot source code
│   └── requirements.txt
└── README.md
```

## Kernel variants

All variants come from [nothingnesscore/BruhKernel](https://github.com/nothingnesscore/BruhKernel), android14-6.1 branch:

| Variant | Description |
|---------|-------------|
| **SukiSU** *(default)* | SukiSU Ultra with SUSFS |
| **KernelSU-Next** | KernelSU Next with SUSFS |
| **WKSU** | Wild KernelSU |
| **ReSukiSU** | Re-compiled SukiSU |

## Troubleshooting

- **Bot not responding?** → Restart the `Run Telegram Bot` workflow
- **Patch failed?** → The bot will send you a link to the failed Actions run log
- **transfer.sh upload fails?** → The file is hosted for 3 days; workflow must complete within that window
