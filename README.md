# BootPatcher 🔧

> An automated, **GitHub Actions-powered Telegram bot** & toolkit that inspects and patches your stock Android `boot.img` with custom GKI kernels from [BruhKernel](https://github.com/nothingnesscore/BruhKernel) (or your own custom kernel fork).  
> Zero setup required on your device — just send your boot image to the bot!

---

## 📖 Newbie Guide: Step-by-Step to a Patched Boot Image

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

## 📱 [SOON] Rooted Android APK Plan: `BootPatcher Mobile`

We are planning an on-device Android application for rooted users to eliminate PC/Telegram steps entirely:

* **Tag:** `[SOON]`
* **Target OS:** Android 11+ (Root required via KernelSU / APatch / Magisk)
* **Architecture Blueprint:**
  1. **Direct Partition Dumper:** Uses `libsu` root shell to automatically locate and dump the active slot's boot partition (`/dev/block/by-name/boot_a` or `boot_b`).
  2. **On-Device Kernel Inspection:** Reads `/proc/version` and parses the local boot image header to display current kernel banner, architecture, and patch level.
  3. **BruhKernel Cloud Sync:** Queries the BruhKernel API for the latest builds matching the active Android version and kernel branch (e.g. `android14-6.1`).
  4. **Embedded On-Device `magiskboot` Engine:** Bundles pre-compiled ARM64 `magiskboot` directly in the APK assets. Unpacks the partition, substitutes the `Image` kernel, and repacks locally in seconds.
  5. **Direct In-App Flashing:** One-tap flashing to the inactive slot (OTA style) or active boot partition with automatic backup creation to prevent bootloops.

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
