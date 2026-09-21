"""
BootPatcher Telegram Bot (Telethon / MTProto Edition)
=====================================================
Direct MTProto connection — NO 20 MB download limit! Supports boot.img up to 2 GB.

Features:
  - Deep local inspection using magiskboot.exe & binary parsing:
      * Linux kernel banner & exact uname string
      * Android Boot Image header (v0-v4, OS version, security patch level)
      * Architecture (arm64, arm32, x86_64)
      * Compression format (gzip, lz4, zstd, xz, raw)
  - Queries BruhKernel for available build versions across 4 flavours:
      1. SukiSU Ultra
      2. KernelSU-Next
      3. WKSU
      4. ReSukiSU
  - Interactive flavour buttons displaying matched build versions.
  - Universal fork support (/repo or button).
  - Uploads to staging and triggers on-demand GitHub Actions patch runner.
  - Live progress tracking and direct delivery of patched_boot.img.

Configuration (.env or Environment Variables):
  TELEGRAM_BOT_TOKEN  - Telegram Bot token from BotFather
  GITHUB_TOKEN        - GitHub PAT with repo + workflow scopes
  GITHUB_REPO         - e.g. "nothingnesscore/BootPatcher"
  KERNEL_REPO         - e.g. "nothingnesscore/BruhKernel"
  TELEGRAM_API_ID     - (Optional, defaults to official Telegram Desktop ID 2040)
  TELEGRAM_API_HASH   - (Optional, defaults to official Telegram Desktop hash)
"""

import os
import sys
import re
import asyncio
import logging
import tempfile
import shutil
import subprocess
from pathlib import Path

import requests
from telethon import TelegramClient, events, Button

# ── Logging Configuration ───────────────────────────────────────────────────

logging.basicConfig(
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger("BootPatcherBot")

# ── Load Environment (.env support) ─────────────────────────────────────────

def load_dotenv():
    env_path = Path(__file__).resolve().parent.parent / ".env"
    if env_path.exists():
        try:
            for line in env_path.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    k = k.strip()
                    v = v.strip().strip("\"'")
                    if k and k not in os.environ:
                        os.environ[k] = v
        except Exception as e:
            logger.warning("Could not read .env: %s", e)

load_dotenv()

BOT_TOKEN   = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
GH_TOKEN    = os.environ.get("GITHUB_TOKEN", "").strip()
GH_REPO     = os.environ.get("GITHUB_REPO", "nothingnesscore/BootPatcher").strip()
KERNEL_REPO = os.environ.get("KERNEL_REPO", "nothingnesscore/BruhKernel").strip()
API_ID      = int(os.environ.get("TELEGRAM_API_ID", "2040"))
API_HASH    = os.environ.get("TELEGRAM_API_HASH", "b18441a1ff607e10a989891a5462e627").strip()
WORKFLOW_ID = "patch-boot.yml"

KERNEL_FLAVOURS = [
    ("SukiSU",        "🟣 SukiSU Ultra"),
    ("KernelSU-Next", "🔵 KernelSU-Next"),
    ("WKSU",          "🟤 WKSU"),
    ("ReSukiSU",      "🟢 ReSukiSU"),
]

# Locate local magiskboot binary
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
MAGISKBOOT_PATHS = [
    PROJECT_ROOT / "tools" / "BootKernelChanger" / "magiskboot.exe",
    PROJECT_ROOT / "tools" / "BootKernelChanger" / "magiskboot",
    Path("magiskboot.exe"),
    Path("magiskboot"),
]
MAGISKBOOT_BIN = next((p for p in MAGISKBOOT_PATHS if p.exists()), None)
if MAGISKBOOT_BIN:
    logger.info("Found local magiskboot: %s", MAGISKBOOT_BIN)
else:
    logger.warning("magiskboot not found locally; will use binary fallback inspection.")


# ── In-Depth Boot Image Inspection ──────────────────────────────────────────

def extract_boot_info(file_path: Path) -> dict:
    """
    Exhaustive boot image parser.
    Uses magiskboot unpack if available, plus header decoding and byte scan fallback.
    """
    info = {
        "format": "Android Boot Image",
        "arch": "arm64 (AArch64)",
        "compression": "raw / none",
        "kernel_short": "Unknown",
        "kernel_version": "Unknown",
        "os_version": "Unknown",
        "os_patch_level": "Unknown",
        "file_size_mb": round(file_path.stat().st_size / (1024 * 1024), 2),
    }

    # 1. Inspect Android Boot Header
    try:
        with open(file_path, "rb") as f:
            header = f.read(4096)
        
        if header.startswith(b"ANDROID!"):
            v = header[40] if len(header) > 40 else 0
            info["format"] = f"Android Boot v{v}" if v <= 4 else "Android Boot Image"
            if len(header) >= 48:
                os_val = int.from_bytes(header[44:48], byteorder="little")
                if os_val != 0:
                    a = (os_val >> 25) & 0x7F
                    b = (os_val >> 18) & 0x7F
                    c = (os_val >> 11) & 0x7F
                    info["os_version"] = f"{a}.{b}.{c}"
                    year = ((os_val >> 4) & 0x7F) + 2000
                    month = os_val & 0x0F
                    info["os_patch_level"] = f"{year:04d}-{month:02d}"
        elif header.startswith(b"VNDR"):
            info["format"] = "Vendor Boot Image"
        elif header.startswith(b"\x27\x05\x19\x56"):
            info["format"] = "U-Boot Legacy Image"
    except Exception as e:
        logger.warning("Header parse error: %s", e)

    # 2. Try magiskboot unpack in a temp directory (decompresses any format)
    unpacked_ok = False
    if MAGISKBOOT_BIN and MAGISKBOOT_BIN.exists():
        tmp_dir = Path(tempfile.mkdtemp(prefix="bkc_inspect_"))
        try:
            boot_copy = tmp_dir / "boot.img"
            shutil.copyfile(file_path, boot_copy)
            res = subprocess.run(
                [str(MAGISKBOOT_BIN), "unpack", "boot.img"],
                cwd=str(tmp_dir),
                capture_output=True,
                text=False,
                timeout=30,
            )
            kernel_file = tmp_dir / "kernel"
            if kernel_file.exists() and kernel_file.stat().st_size > 0:
                kdata = kernel_file.read_bytes()
                idx = kdata.find(b"Linux version ")
                if idx != -1:
                    end = kdata.find(b"\x00", idx)
                    if end == -1 or (end - idx) > 300:
                        end = idx + 250
                    banner = kdata[idx:end].decode("utf-8", errors="ignore").strip()
                    info["kernel_version"] = banner
                    ver_m = re.search(r"Linux version (\d+\.\d+\.\d+[\w.-]*)", banner)
                    if ver_m:
                        info["kernel_short"] = ver_m.group(1)
                    unpacked_ok = True

                # Detect arch from decompressed kernel
                if b"aarch64" in kdata or b"ARM aarch64" in kdata or b"ARM64" in kdata:
                    info["arch"] = "arm64 (AArch64)"
                elif b"armv7" in kdata or b"ARMv7" in kdata:
                    info["arch"] = "arm32 (ARMv7)"
                elif b"x86_64" in kdata:
                    info["arch"] = "x86_64"

        except Exception as e:
            logger.warning("magiskboot unpack inspection failed: %s", e)
        finally:
            shutil.rmtree(tmp_dir, ignore_errors=True)

    # 3. Fallback: Raw binary scan if magiskboot didn't unpack
    if not unpacked_ok:
        try:
            with open(file_path, "rb") as f:
                raw_data = f.read()

            # Check compression magic signatures
            if b"\x1f\x8b\x08" in raw_data:
                info["compression"] = "gzip"
                try:
                    import gzip
                    gz_offset = raw_data.find(b"\x1f\x8b\x08")
                    decomp = gzip.decompress(raw_data[gz_offset:gz_offset + 30 * 1024 * 1024])
                    idx = decomp.find(b"Linux version ")
                    if idx != -1:
                        end = decomp.find(b"\x00", idx)
                        banner = decomp[idx:end].decode("utf-8", errors="ignore").strip()
                        info["kernel_version"] = banner
                        ver_m = re.search(r"Linux version (\d+\.\d+\.\d+[\w.-]*)", banner)
                        if ver_m:
                            info["kernel_short"] = ver_m.group(1)
                except Exception:
                    pass
            elif b"\x02\x21\x4c\x18" in raw_data or b"\x04\x22\x4d\x18" in raw_data:
                info["compression"] = "lz4"
            elif b"\x28\xb5\x2f\xfd" in raw_data:
                info["compression"] = "zstd"
            elif b"\xfd7zXZ\x00" in raw_data:
                info["compression"] = "xz"

            if info["kernel_version"] == "Unknown":
                idx = raw_data.find(b"Linux version ")
                if idx != -1:
                    end = raw_data.find(b"\x00", idx)
                    banner = raw_data[idx:end].decode("utf-8", errors="ignore").strip()
                    info["kernel_version"] = banner
                    ver_m = re.search(r"Linux version (\d+\.\d+\.\d+[\w.-]*)", banner)
                    if ver_m:
                        info["kernel_short"] = ver_m.group(1)

            if b"aarch64" in raw_data or b"ARM aarch64" in raw_data or b"ARM64" in raw_data:
                info["arch"] = "arm64 (AArch64)"
        except Exception as e:
            logger.warning("Raw binary scan error: %s", e)

    return info


# ── Remote BruhKernel Build Versions Query ──────────────────────────────────

def query_flavour_versions(kernel_repo: str) -> dict:
    versions = {f[0]: "6.1.138" for f in KERNEL_FLAVOURS}
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "BootPatcher-Bot",
    }
    if GH_TOKEN:
        headers["Authorization"] = f"Bearer {GH_TOKEN}"

    try:
        url = f"https://api.github.com/repos/{kernel_repo}/actions/artifacts?per_page=60"
        resp = requests.get(url, headers=headers, timeout=10)
        if resp.status_code == 200:
            arts = resp.json().get("artifacts", [])
            for a in arts:
                if a.get("expired"):
                    continue
                name = a.get("name", "")
                for key in versions.keys():
                    if key.lower() in name.lower() and "anykernel3" in name.lower():
                        dev = "peridot " if "peridot" in name.lower() else ""
                        m = re.search(r"(\d+\.\d+\.\d+)", name)
                        if m:
                            versions[key] = f"{dev}{m.group(1)}" if dev else m.group(1)
    except Exception as e:
        logger.warning("Could not query BruhKernel artifacts: %s", e)

    return versions


# ── Cloud Staging & GitHub Dispatch ─────────────────────────────────────────

def upload_to_staging(file_path: Path) -> str:
    """Uploads boot image to transfer.sh for GitHub Actions runner to download."""
    clean_name = re.sub(r"[^\w\.-]", "_", file_path.name)
    url = f"https://transfer.sh/{clean_name}"
    with open(file_path, "rb") as f:
        resp = requests.put(url, data=f, headers={"Max-Days": "2"}, timeout=300)
    resp.raise_for_status()
    return resp.text.strip()


def dispatch_workflow(boot_url: str, chat_id: int, variant: str, kernel_repo: str, stock_kernel: str) -> bool:
    if not GH_TOKEN:
        logger.error("GH_TOKEN is missing")
        return False

    url = f"https://api.github.com/repos/{GH_REPO}/actions/workflows/{WORKFLOW_ID}/dispatches"
    headers = {
        "Authorization": f"Bearer {GH_TOKEN}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "BootPatcher-Bot",
    }
    payload = {
        "ref": "main",
        "inputs": {
            "boot_img_url":   boot_url,
            "chat_id":        str(chat_id),
            "kernel_variant": variant,
            "kernel_repo":    kernel_repo,
            "stock_kernel":   stock_kernel,
        },
    }
    resp = requests.post(url, json=payload, headers=headers, timeout=20)
    return resp.status_code == 204


# ── Bot Session State ────────────────────────────────────────────────────────

USER_SESSIONS = {}  # chat_id -> { "file_path": Path, "fname": str, "info": dict, "kernel_repo": str }


# ── Telethon Client & Event Handlers ────────────────────────────────────────

bot = TelegramClient("bootpatcher_session", API_ID, API_HASH)


@bot.on(events.NewMessage(pattern=r"^/start"))
async def handle_start(event):
    await event.respond(
        "👋 **Welcome to BootPatcher!**\n\n"
        "I inspect your Android `boot.img`, extract its kernel version & architecture, "
        "and patch it using the latest **BruhKernel** GKI builds.\n\n"
        "⚡ **Features:**\n"
        "• Direct MTProto engine — handles files up to **2 GB**\n"
        "• Deep inspection (Linux uname, architecture, format, compression)\n"
        "• 4 kernel flavours: **SukiSU, KernelSU-Next, WKSU, ReSukiSU**\n"
        "• On-demand GitHub Actions cloud patching in ~3 mins!\n\n"
        "📎 **Send your stock `boot.img` as an uncompressed File to start!**"
    )


@bot.on(events.NewMessage(pattern=r"^/help"))
async def handle_help(event):
    await event.respond(
        "ℹ️ **BootPatcher Help**\n\n"
        "• Send any `boot.img` file to inspect and patch.\n"
        "• Kernels sourced from: `" + KERNEL_REPO + "`\n"
        "• Flashing instructions included with every patched build.\n"
        "• Source: https://github.com/" + GH_REPO
    )


@bot.on(events.NewMessage(pattern=r"^/repo(?:\s+(.+))?"))
async def handle_repo(event):
    chat_id = event.chat_id
    new_repo = event.pattern_match.group(1)
    if new_repo and "/" in new_repo:
        new_repo = new_repo.strip()
        USER_SESSIONS.setdefault(chat_id, {})["kernel_repo"] = new_repo
        await event.respond(f"✅ Kernel source set to: `{new_repo}`")
    else:
        current = USER_SESSIONS.get(chat_id, {}).get("kernel_repo", KERNEL_REPO)
        await event.respond(
            f"⚙️ **Current Kernel Source:** `{current}`\n\n"
            "To use your own BruhKernel fork, send:\n`/repo youruser/BruhKernel`"
        )


@bot.on(events.NewMessage)
async def handle_document(event):
    # Ignore text commands
    if event.text and event.text.startswith("/"):
        return

    msg = event.message

    # 1. Reject non-document media (GIFs, photos, videos, stickers, voice, audio)
    media_type = None
    media_detail = None

    if getattr(msg, "gif", False) or (msg.document and any(getattr(a, "mime_type", "").startswith("image/gif") for a in getattr(msg.document, "attributes", []))):
        media_type = "GIF Animation"
        media_detail = "Meme GIF / Animation"
    elif msg.photo:
        media_type = "Photo / Image"
        media_detail = "Compressed Photo"
    elif msg.video:
        media_type = "Video"
        media_detail = "Video File"
    elif msg.sticker:
        media_type = "Sticker"
        media_detail = "Sticker"
    elif msg.voice:
        media_type = "Voice Message"
        media_detail = "Voice Audio"
    elif msg.audio:
        media_type = "Audio"
        media_detail = "Audio Track"

    if media_type:
        reject_msg = (
            "⚠️ **Upload Rejected by BootPatcher**\n"
            "━━━━━━━━━━━━━━━━━━━━\n"
            "🤖 **Bot:** `BootPatcher`\n"
            f"📦 **Detected Media:** `{media_type}` ({media_detail})\n"
            "❌ **Reason:** BootPatcher only processes raw Android boot images.\n\n"
            "📋 **How to send your boot image:**\n"
            "1️⃣ Tap 📎 (Attachment) in Telegram\n"
            "2️⃣ Choose **File / Document** (DO NOT send as Photo, Video, or GIF)\n"
            "3️⃣ Ensure the file has an `.img` extension (e.g. `boot.img` or `init_boot.img`)"
        )
        await event.reply(reject_msg)
        return

    # 2. Strict document validation
    if not msg.document:
        return

    fname = None
    for attr in msg.document.attributes:
        if hasattr(attr, "file_name") and attr.file_name:
            fname = attr.file_name
            break

    mime = (getattr(msg.document, "mime_type", "") or "").lower()
    size_bytes = msg.document.size or 0
    size_mb = round(size_bytes / (1024 * 1024), 2)

    if not fname:
        await event.reply(
            "⚠️ **Upload Rejected by BootPatcher**\n"
            "━━━━━━━━━━━━━━━━━━━━\n"
            "🤖 **Bot:** `BootPatcher`\n"
            f"📄 **Detected:** `Unnamed Stream` ({mime or 'raw'})\n"
            "❌ **Reason:** No file name detected.\n\n"
            "👉 Please send your stock Android boot image as a named file ending with `.img` (e.g. `boot.img`)."
        )
        return

    lower = fname.lower()

    if mime.startswith(("image/", "video/", "audio/", "text/")):
        await event.reply(
            "⚠️ **Upload Rejected by BootPatcher**\n"
            "━━━━━━━━━━━━━━━━━━━━\n"
            "🤖 **Bot:** `BootPatcher`\n"
            f"📄 **File:** `{fname}`\n"
            f"📦 **Detected Type:** `{mime}`\n"
            "❌ **Reason:** This file is an image/video/media file, not an Android boot image.\n\n"
            "👉 Please send your actual stock `boot.img` or `init_boot.img` extracted from your ROM."
        )
        return

    invalid_exts = [
        ".gif", ".mp4", ".mov", ".avi", ".mkv", ".webm",
        ".png", ".jpg", ".jpeg", ".webp", ".svg", ".bmp",
        ".zip", ".rar", ".7z", ".tar", ".gz", ".xz", ".bz2",
        ".apk", ".exe", ".msi", ".dmg", ".iso", ".pdf",
        ".txt", ".log", ".json", ".xml", ".py", ".sh", ".bat", ".js"
    ]
    for ext in invalid_exts:
        if lower.endswith(ext):
            await event.reply(
                "⚠️ **Upload Rejected by BootPatcher**\n"
                "━━━━━━━━━━━━━━━━━━━━\n"
                "🤖 **Bot:** `BootPatcher`\n"
                f"📄 **File:** `{fname}`\n"
                f"📦 **Extension:** `{ext}`\n"
                f"❌ **Reason:** Files ending in `{ext}` are not boot images.\n\n"
                "👉 Please send an uncompressed `boot.img` or `init_boot.img` file."
            )
            return

    is_valid_boot_ext = lower.endswith(".img") or ((lower.endswith(".bin") or lower.endswith(".raw")) and "boot" in lower)
    if not is_valid_boot_ext:
        await event.reply(
            "⚠️ **Upload Rejected by BootPatcher**\n"
            "━━━━━━━━━━━━━━━━━━━━\n"
            "🤖 **Bot:** `BootPatcher`\n"
            f"📄 **File:** `{fname}`\n"
            "❌ **Reason:** File must have an `.img` extension (e.g. `boot.img`).\n\n"
            "👉 Please ensure you are sending your partition image file."
        )
        return

    if size_bytes < 4 * 1024 * 1024:
        await event.reply(
            "⚠️ **Upload Rejected by BootPatcher**\n"
            "━━━━━━━━━━━━━━━━━━━━\n"
            "🤖 **Bot:** `BootPatcher`\n"
            f"📄 **File:** `{fname}`\n"
            f"💾 **Size:** `{size_mb} MB`\n"
            "❌ **Reason:** File is too small (< 4 MB) to be a valid Android boot image.\n\n"
            "👉 Modern Android boot images are typically 32 MB to 128 MB."
        )
        return

    chat_id = event.chat_id
    size_mb = round(msg.document.size / (1024 * 1024), 2)

    status_msg = await event.reply(
        f"📥 **Downloading `{fname}` ({size_mb} MB)...**\n"
        "⏳ *Inspecting kernel banner, uname & headers...*"
    )

    # 1. Download file directly via MTProto (no 20 MB limit!)
    tmp_file = Path(tempfile.gettempdir()) / f"boot_{chat_id}_{msg.id}.img"
    try:
        await msg.download_media(file=str(tmp_file))

        # 2. Extract in-depth boot information
        info = extract_boot_info(tmp_file)

        # 3. Query flavour versions from BruhKernel
        kernel_repo = USER_SESSIONS.get(chat_id, {}).get("kernel_repo", KERNEL_REPO)
        versions = query_flavour_versions(kernel_repo)

        # Save session
        USER_SESSIONS[chat_id] = {
            "file_path": tmp_file,
            "fname": fname,
            "info": info,
            "kernel_repo": kernel_repo,
            "msg_id": msg.id,
        }

        # 4. Create Buttons showing exact version for each flavour
        buttons = []
        for key, label in KERNEL_FLAVOURS:
            ver = versions.get(key, "6.1.138")
            buttons.append([Button.inline(f"{label} ({ver})", data=f"patch:{key}")])

        banner_preview = info["kernel_version"]
        if len(banner_preview) > 180:
            banner_preview = banner_preview[:180] + "..."

        analysis_card = (
            f"🔍 **Boot Image Details**\n"
            f"━━━━━━━━━━━━━━━━━━━━\n"
            f"📄 **File:** `{fname}`\n"
            f"💾 **Size:** `{info['file_size_mb']} MB`\n"
            f"🗂 **Format:** `{info['format']}`\n"
            f"🏗 **Arch:** `{info['arch']}`\n"
            f"🗜 **Compression:** `{info['compression']}`\n"
            f"🐧 **Stock Kernel:** `{info['kernel_short']}`\n"
            f"📅 **OS Patch Level:** `{info['os_patch_level']}`\n\n"
            f"**Kernel String:**\n"
            f"```\n{banner_preview}\n```\n\n"
            f"Select which kernel flavour to patch with:"
        )

        await status_msg.edit(analysis_card, buttons=buttons)

    except Exception as e:
        logger.exception("Failed processing boot image: %s", e)
        await status_msg.edit(f"❌ **Failed to process boot image:**\n`{e}`")
        if tmp_file.exists():
            tmp_file.unlink(missing_ok=True)


@bot.on(events.CallbackQuery)
async def handle_callback(event):
    data = event.data.decode("utf-8")
    chat_id = event.chat_id

    if not data.startswith("patch:"):
        return

    flavour = data.replace("patch:", "")
    session = USER_SESSIONS.get(chat_id)

    if not session or not session.get("file_path") or not session["file_path"].exists():
        await event.answer("❌ Session expired. Please re-upload your boot.img.", alert=True)
        return

    await event.answer(f"Selected {flavour}!")

    fname = session["fname"]
    tmp_file = session["file_path"]
    info = session["info"]
    kernel_repo = session["kernel_repo"]

    flavour_title = next((label for k, label in KERNEL_FLAVOURS if k == flavour), flavour)

    await event.edit(
        f"🚀 **GitHub Actions Runner Dispatched!**\n"
        f"━━━━━━━━━━━━━━━━━━━━\n"
        f"📄 **File:** `{fname}`\n"
        f"🐧 **Stock Kernel:** `{info['kernel_short']}`\n"
        f"💉 **Injecting:** {flavour_title}\n"
        f"🌐 **Kernel Source:** `{kernel_repo}`\n\n"
        f"⏳ Staging boot image and starting clean Linux runner...\n"
        f"You will receive live auto-detection and your final `{info['kernel_short']}_{flavour.lower()}.img` in 3–5 minutes! ☕"
    )

    try:
        # Upload file to transfer.sh for runner
        boot_url = upload_to_staging(tmp_file)

        # Dispatch GitHub Actions workflow
        ok = dispatch_workflow(
            boot_url=boot_url,
            chat_id=chat_id,
            variant=flavour,
            kernel_repo=kernel_repo,
            stock_kernel=info.get("kernel_short", "Unknown"),
        )

        if ok:
            logger.info("Successfully dispatched patch job for chat %s", chat_id)
        else:
            await event.respond("❌ Failed to trigger GitHub Actions workflow. Check GITHUB_TOKEN.")

    except Exception as e:
        logger.exception("Error dispatching patch workflow: %s", e)
        await event.respond(f"❌ **Error staging file or dispatching workflow:**\n`{e}`")
    finally:
        # Cleanup temp file
        if tmp_file.exists():
            tmp_file.unlink(missing_ok=True)


# ── Main Entry Point ────────────────────────────────────────────────────────

def main():
    if not BOT_TOKEN:
        logger.error("TELEGRAM_BOT_TOKEN environment variable is not set!")
        sys.exit(1)

    logger.info("Starting BootPatcher Telethon Bot as @%s...", BOT_TOKEN.split(":")[0])

    # Remove any conflicting webhook so MTProto receives events directly
    try:
        del_resp = requests.post(
            f"https://api.telegram.org/bot{BOT_TOKEN}/deleteWebhook",
            json={"drop_pending_updates": False},
            timeout=10,
        ).json()
        logger.info("Cleared webhook: %s", del_resp)
    except Exception as e:
        logger.warning("Could not clear webhook: %s", e)

    bot.start(bot_token=BOT_TOKEN)
    logger.info("Bot is connected and listening via MTProto. Ready to receive files up to 2 GB!")
    bot.run_until_disconnected()


if __name__ == "__main__":
    main()
