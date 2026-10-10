# BootPatcher 🔧

> An automated, **GitHub Actions-powered Telegram bot** & toolkit that inspects and patches your stock Android `boot.img` with custom GKI kernels from [BruhKernel](https://github.com/nothingnesscore/BruhKernel) (or your own custom kernel fork).  
> Zero setup required on your device — just send your boot image to the bot!

---

## 📱 Android App: BootPatcher Mobile

Patch a stock `boot.img` with a BruhKernel GKI kernel **on your phone**, with no PC and no Telegram bot.

* **Built automatically** by [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) on every push that touches the app.
* **Download** the `BootPatcher-release-apk` artifact from the [Actions page](https://github.com/nothingnesscore/BootPatcher/actions).
* **Requires Android 11+.** Root is *not* required to patch an image you have already dumped.
* The patching engine (`magiskboot`, ARM64) is bundled in the app, so patching happens locally and offline.

### How to use it

1. **Get your stock `boot.img`** — the same ways described in the newbie guide below.
2. **Install the APK** and open BootPatcher.
3. **Choose your stock `boot.img`.** The app reads the real boot header and shows the header version, Android version, security patch level, kernel version and architecture.
4. **Search for a matching kernel** on the Flavor tab, or pick a kernel image yourself.
5. **Tap "Patch boot image".** The patched image is saved to the app's files directory.
6. **Flash it** with fastboot or a custom recovery, exactly as in the newbie guide.

### 🛡️ Why it refuses to patch

A GKI kernel only boots if it matches your phone's Android version, kernel version **and security patch level**. Flashing a kernel built for a different month is a bootloop.

BootPatcher treats these as hard failures, not warnings. It will not patch when:

* the Android version differs
* the kernel version differs (`6.1.75` and `6.1.138` are not interchangeable)
* the **security patch level differs** — this is read from the boot header, not guessed
* the boot header version differs
* the architecture differs, or cannot be determined

If nothing matches, the app tells you exactly which values it needs, and how to get them: fork [nothingnesscore/BruhKernel](https://github.com/nothingnesscore/BruhKernel), run the build for your exact target, then point BootPatcher at your fork.

### 📖 Newbie Guide: Step-by-Step to a Patched Boot Image

If you have never patched or flashed a boot image before, follow these simple steps:

### 1️⃣ Extract your stock `boot.img`
* **From Fastboot / Recovery ROM:** Download the official stock ROM for your phone model and extract `boot.img` (or use `payload-dumper-go` if it is inside `payload.bin`).
* **From Rooted Device / Termux:** You can dump your current boot partition directly:
  ```bash
  su -c "dd if=/dev/block/by-name/boot$(getprop ro.boot.slot_suffix) of=/sdcard/boot.img"
  ```

### 2️⃣ Send your `boot.img` to the Telegram Bot
1. Open the bot on Telegram: **[@umbromomento_bot](https://t.me/umbromomento_bot)**
2. Tap the 📎 **Attachment (Paperclip)** icon.
3. Select **File** ➔ browse and send your `boot.img` *(important: do NOT send as compressed photo/media)*.

### 3️⃣ Review Boot Image Inspection
The bot will immediately inspect your boot image and display:
* 🐧 **Stock Linux Kernel Version & Banner**
* 🏗 **Architecture** (`arm64`, `arm32`, `x86_64`)
* 🗜 **Compression Format** (`gzip`, `lz4`, `zstd`, `raw`)
* 📅 **OS Patch Level** & Header Format (`Android Boot v2/v3/v4`)

### 4️⃣ Select your Kernel Flavour
Tap one of the interactive buttons displaying the available kernel builds:
* 🟣 **SukiSU Ultra** — SukiSU with latest SUSFS, NoMount & performance patches *(recommended)*.
* 🔵 **KernelSU-Next** — KernelSU-Next branch with SUSFS integration.
* 🟤 **WKSU** — Wild KernelSU implementation.
* 🟢 **ReSukiSU** — Alternative SukiSU rebuild.

*(Each button shows the exact version matched, e.g. `🟣 SukiSU Ultra (6.1.138)`)*

### 5️⃣ Cloud Patching via GitHub Actions
* The bot stages your image and triggers a dedicated GitHub Actions cloud runner.
* Live runner updates are sent to your chat while `magiskboot` unpacks, replaces the kernel binary, and repacks.
* Within **3 to 5 minutes**, the bot delivers your ready-to-flash `patched_boot.img`!

### 6️⃣ Flash to Device
* **Via Fastboot (PC):**
  ```bash
  adb reboot bootloader
  fastboot flash boot patched_boot.img
  fastboot reboot
  ```
* **Via Custom Recovery (TWRP / OrangeFox):**
  1. Copy `patched_boot.img` to internal storage.
  2. In TWRP: **Install** ➔ **Install Image** ➔ select `patched_boot.img` ➔ check **Boot** partition ➔ Swipe to flash.
  3. Reboot system!

---

## 🌐 Universal Fork Support (For Developers)

Anyone can fork `nothingnesscore/BruhKernel` or build custom kernels and patch stock boot images using this tool:

1. **Fork BruhKernel:** Fork [nothingnesscore/BruhKernel](https://github.com/nothingnesscore/BruhKernel) to your own GitHub account.
2. **Build Your Kernel:** Run your custom workflow in your fork to build your kernel variants.
3. **Point the Bot to Your Fork:**
   * In Telegram, send `/repo` or click **⚙️ Kernel Repo** after uploading your image.
   * Send your GitHub repository in `username/repo` format (e.g. `myuser/BruhKernel`).
   * The bot will automatically query your fork's artifacts and patch images using your builds!
4. **Zero Server Load:** Everything runs on GitHub Actions runners, keeping costs and hosting load at absolute zero.

---

## 📱 Rooted Android APK Plan: `BootPatcher Mobile`

The on-device app described above is now implemented. Remaining work:

* Direct partition dumping via root shell (`libsu`) so the app can read the active slot itself.
* One-tap flashing to the inactive slot with automatic backup.
* Fork selection and a token field in Settings (the resolver already supports both).

---

## 💻 Desktop Tool: BootKernelChanger (English + Auto Dark Mode)

For users who prefer offline patching on Windows PC:
* 📥 **[Download Latest Release (v1.0.0)](https://github.com/nothingnesscore/BootPatcher/releases/tag/v1.0.0)** — Standalone Windows 64-bit `.exe`.
* Source code located in [`tools/BootKernelChanger/`](tools/BootKernelChanger/).
* **Features:**
  * Native English user interface.
  * **Auto Dark Mode:** Detects Windows 10/11 system light/dark theme using `darkdetect` and applies the Sun Valley theme (`sv-ttk`).
  * Standalone single-file executable `BootKernelChanger_EN.exe` bundled with `magiskboot.exe`.
* **Building from source:**
  ```powershell
  cd tools/BootKernelChanger
  pip install sv-ttk darkdetect pyinstaller
  pyinstaller --noconsole --onefile --icon=icon.ico --add-data "magiskboot.exe;." -n BootKernelChanger_EN app.py
  ```

---

## 🏆 Credits & Acknowledgements

* **[Dayto0](https://github.com/Dayto0)** — Huge thanks and credits to **Dayto0** for the original [BootKernelChanger](https://github.com/Dayto0/BootKernelChanger) idea and concept of replacing kernel binaries inside `boot.img` using `magiskboot`.
* **[nothingnesscore / BruhKernel](https://github.com/nothingnesscore/BruhKernel)** — Automated GKI kernel build pipeline with SukiSU, SUSFS, and performance optimizations.
* **[topjohnwu / Magisk](https://github.com/topjohnwu/Magisk)** — `magiskboot` utility for Android boot image unpacking and repacking.
