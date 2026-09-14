# ⚡ Serverless On-Demand Webhook (Cloudflare Worker)

This serverless webhook ensures that **GitHub Actions NEVER runs continuously**.  
Instead, runners **only fire up on-demand** when a user uploads a `boot.img` for patching, ensuring zero runner minute waste and fair queueing for all users.

---

## 🏗 How It Works

```
User on Telegram sends boot.img
       │
       ▼
Cloudflare Worker (<20ms runtime, 100,000 free reqs/day)
       │
       ├─► Prompts user with 4 flavour buttons: SukiSU, KernelSU-Next, WKSU, ReSukiSU
       │
       ▼ User clicks flavour
       │
Cloudflare Worker dispatches GitHub Actions (patch-boot.yml)
       │
       ▼
GitHub Actions Runner fires up ON-DEMAND:
  1. Unpacks boot.img with magiskboot
  2. Injects requested BruhKernel GKI build
  3. Repacks boot.img
  4. Sends patched_boot.img directly back to Telegram
  5. Runner terminates immediately (~3 minutes total)
```

---

## 🚀 2-Minute Setup Guide (100% Free)

### Step 1: Create a Cloudflare Worker
1. Go to [dash.cloudflare.com](https://dash.cloudflare.com) (free account, no credit card required).
2. Click **Workers & Pages** ➔ **Create Worker**.
3. Name it `bootpatcher-bot` and click **Deploy**.
4. Click **Edit code**, replace everything in `worker.js` with the contents of [`worker.js`](worker.js), and click **Deploy**.

### Step 2: Set Worker Secrets
In your worker's settings (Settings ➔ Variables and Secrets ➔ Add):

| Variable Name | Value |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Your Telegram Bot token from BotFather |
| `GITHUB_TOKEN` | GitHub Personal Access Token (`repo` + `workflow` scopes) |
| `GITHUB_REPO` | `nothingnesscore/BootPatcher` |
| `KERNEL_REPO` | `nothingnesscore/BruhKernel` |

### Step 3: Set Telegram Webhook
Open your browser and navigate to:
```
https://api.telegram.org/bot<YOUR_TELEGRAM_BOT_TOKEN>/setWebhook?url=https://<YOUR_WORKER_NAME>.<YOUR_SUBDOMAIN>.workers.dev
```

You will see:
```json
{"ok": true, "result": true, "description": "Webhook was set"}
```

🎉 **Done!** Your bot is now permanently online 24/7 without consuming any GitHub Actions runner minutes until someone actually requests a patch!
