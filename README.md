# BootPatcher 🔧

> A **GitHub Actions-powered Telegram bot** that patches your stock `boot.img` with the latest [BruhKernel](https://github.com/nothingnesscore/BruhKernel) (android14-6.1). No PC needed — just send your file.

---

## 🚀 How to get your patched boot.img (newbie guide)

> ⏱ Takes about **5–7 minutes** total

**Step 1** — Open the bot on Telegram  
→ Search for **@umbromomento_bot** or click the link your device came with

**Step 2** — Send your `boot.img` as a **File**  
→ In Telegram, tap the 📎 paperclip → pick **File** (NOT photo/video)  
→ Navigate to your `boot.img` and send it

**Step 3** — Read the analysis the bot shows you  
→ The bot will tell you your stock kernel version, arch, compression type

**Step 4** — Pick a kernel flavour  
→ Tap one of the 4 buttons: SukiSU (recommended), KernelSU-Next, WKSU, or ReSukiSU

**Step 5** — Wait for GitHub Actions to do the work  
→ The bot will message you as it progresses  
→ When done, it sends back your **patched_boot.img** directly

**Step 6** — Flash the patched boot.img  
→ Boot into recovery (TWRP / OrangeFox)  
→ Flash `patched_boot.img` via **Install → Install Image → Boot Partition**  
→ Reboot and enjoy your patched kernel! 🎉

---

## How it works (technical)

```
You → Send boot.img to bot
        ↓
Bot → Analyses kernel version, arch, compression locally
Bot → Shows you the analysis + flavour picker buttons
        ↓
You → Pick a kernel flavour
        ↓
Bot → Uploads boot.img to temporary hosting
Bot → Triggers GitHub Actions patch workflow
        ↓
GH Actions → Downloads latest BruhKernel (android14-6.1) artifact
           → magiskboot unpack stock boot.img
           → Swaps old kernel → new kernel
           → magiskboot repack
        ↓
Bot → Sends patched_boot.img back to you on Telegram
```

---

## Kernel flavours

All kernels from [nothingnesscore/BruhKernel](https://github.com/nothingnesscore/BruhKernel), android14-6.1:

| Flavour | Description |
|---------|-------------|
| 🟣 **SukiSU** *(recommended)* | SukiSU Ultra + SUSFS + NoMount |
| 🔵 **KernelSU-Next** | KernelSU Next + SUSFS |
| 🟤 **WKSU** | Wild KernelSU |
| 🟢 **ReSukiSU** | Re-compiled SukiSU |

---

## Use your own kernel fork (for developers)

If you maintain your own BruhKernel fork:

1. Fork [nothingnesscore/BruhKernel](https://github.com/nothingnesscore/BruhKernel)
2. Build your kernel — it should publish AnyKernel3 artifacts with the same naming pattern
3. In the bot, after sending your boot.img, tap **⚙️ Custom kernel repo**
4. Type your repo as `owner/repo` (e.g. `myuser/MyKernelFork`)
5. Pick your flavour and patch!

---

## Coming soon 🚧

| Feature | Status |
|---------|--------|
| Android app (root required) | 🔜 Soon |
| Auto-detect matching kernel version from boot.img | 🔜 Soon |
| Support for more android versions / branches | 🔜 Soon |

---

## Credits

- 🔧 **Original patching concept** — [Dayto0/BootKernelChanger](https://github.com/Dayto0/BootKernelChanger) by **@dayt0**  
- 🐧 **Kernel builds** — [nothingnesscore/BruhKernel](https://github.com/nothingnesscore/BruhKernel)  
- ⚙️ **Repacking engine** — [magiskboot](https://github.com/topjohnwu/Magisk) by topjohnwu

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Bot not responding | It may be restarting (~6h cycle), try again in a minute |
| "No artifact found" | The kernel repo hasn't built recently, or wrong repo name |
| Patch fails | Tap the Actions link the bot sends and check logs |
| Wrong boot partition after flash | Make sure you're flashing to the correct slot (A/B) |
