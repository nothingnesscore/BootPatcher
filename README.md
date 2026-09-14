# BootPatcher 🔧

> A GitHub Actions-powered Telegram bot that automatically patches your stock `boot.img` with the latest **BruhKernel** (android14-6.1) kernel.

## Usage

Just send your `boot.img` to the bot on Telegram — it handles everything automatically.

1. Send `/start` to get started
2. Upload your `boot.img` as a **file** (not photo/video)
3. Choose the kernel variant you want
4. Wait ~5 minutes
5. Receive your patched `boot.img` directly in chat 🎉

## How it works

```
You → Upload boot.img to Telegram bot
        ↓
Bot → Triggers GitHub Actions workflow
        ↓
GH Actions → Downloads latest BruhKernel (android14-6.1)
           → Unpacks your boot.img with magiskboot
           → Swaps old kernel → new kernel
           → Repacks boot.img
        ↓
Bot → Sends patched boot.img back to you
```

## Kernel variants

All kernels come from [nothingnesscore/BruhKernel](https://github.com/nothingnesscore/BruhKernel), android14-6.1:

| Variant | Description |
|---------|-------------|
| **SukiSU** *(default)* | SukiSU Ultra with SUSFS |
| **KernelSU-Next** | KernelSU Next with SUSFS |
| **WKSU** | Wild KernelSU |
| **ReSukiSU** | Re-compiled SukiSU |

## Troubleshooting

- **No response?** → The bot may be restarting, try again in a minute
- **Patch failed?** → You'll get a link to the Actions log with details
