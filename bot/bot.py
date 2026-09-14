"""
BootPatcher Telegram Bot
========================
Universal Android boot.img kernel patcher powered by GitHub Actions.

Features:
  - In-depth local analysis of uploaded boot.img (Linux version, uname, arch, compression, format).
  - Fetches and displays available kernel builds for 4 flavours from BruhKernel or custom forks:
      1. SukiSU
      2. KernelSU-Next
      3. WKSU
      4. ReSukiSU
  - Interactive flavour buttons showing the target kernel version for each flavour.
  - Universal fork support: user can switch to any GitHub fork (e.g. username/repo).
  - Triggers automated GitHub Actions workflow to patch with magiskboot.
  - Sends real-time progress and final patched_boot.img back via Telegram.

Configuration (Environment Variables):
  TELEGRAM_BOT_TOKEN  - Telegram Bot Token from BotFather
  GITHUB_TOKEN        - GitHub PAT with repo + workflow scopes
  GITHUB_REPO         - This BootPatcher repository, e.g. "nothingnesscore/BootPatcher"
  KERNEL_REPO         - (Optional) Default kernel repo (default: "nothingnesscore/BruhKernel")
"""

import os
import sys
import logging
import tempfile
import re
import requests

from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.ext import (
    Application,
    CommandHandler,
    MessageHandler,
    CallbackQueryHandler,
    ContextTypes,
    filters,
)

# ── Configuration ────────────────────────────────────────────────────────────

BOT_TOKEN   = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
GH_TOKEN    = os.environ.get("GITHUB_TOKEN", "").strip()
GH_REPO     = os.environ.get("GITHUB_REPO", "").strip()
KERNEL_REPO = os.environ.get("KERNEL_REPO", "nothingnesscore/BruhKernel").strip()
WORKFLOW_ID = "patch-boot.yml"

KERNEL_FLAVOURS = [
    ("SukiSU",        "🟣 SukiSU Ultra"),
    ("KernelSU-Next", "🔵 KernelSU-Next"),
    ("WKSU",          "🟤 WKSU"),
    ("ReSukiSU",      "🟢 ReSukiSU"),
]

logging.basicConfig(
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger(__name__)


# ── In-Depth Boot Image Analyzer ─────────────────────────────────────────────

def extract_boot_info(file_path: str) -> dict:
    """
    Examines raw boot.img headers, magic signatures, and kernel strings.
    No external binaries required.
    """
    info = {
        "kernel_version": "Unknown",
        "kernel_short":   "Unknown",
        "kernel_branch":  "Unknown",
        "arch":           "Unknown",
        "compression":    "raw / none",
        "format":         "Unknown",
        "os_version":     "Unknown",
        "os_patch_level": "Unknown",
        "file_size_mb":   0.0,
    }

    try:
        with open(file_path, "rb") as f:
            header = f.read(4096)
            f.seek(0)
            data = f.read()

        info["file_size_mb"] = round(len(data) / (1024 * 1024), 2)

        # Detect Header / Magic
        if header[:8] == b"ANDROID!":
            info["format"] = "Android Boot Image"
            # Header version v0-v4
            header_version = header[40] if len(header) > 40 else 0
            if header_version in (0, 1, 2, 3, 4):
                info["format"] = f"Android Boot v{header_version}"
            
            # Extract OS version & patch level (v1/v2 header offset 44)
            if len(header) >= 48:
                os_val = int.from_bytes(header[44:48], byteorder="little")
                if os_val != 0:
                    os_a = (os_val >> 25) & 0x7F
                    os_b = (os_val >> 18) & 0x7F
                    os_c = (os_val >> 11) & 0x7F
                    info["os_version"] = f"{os_a}.{os_b}.{os_c}"
                    year = ((os_val >> 4) & 0x7F) + 2000
                    month = os_val & 0x0F
                    info["os_patch_level"] = f"{year:04d}-{month:02d}"

        elif header[:4] == b"\x27\x05\x19\x56":
            info["format"] = "U-Boot Legacy Image"
        elif header[:4] == b"VNDR":
            info["format"] = "Vendor Boot Image"

        # Search for Linux kernel banner string
        linux_idx = data.find(b"Linux version ")
        if linux_idx != -1:
            end = data.find(b"\x00", linux_idx)
            if end == -1 or (end - linux_idx) > 300:
                end = linux_idx + 250
            banner = data[linux_idx:end].decode("utf-8", errors="ignore").strip()
            info["kernel_version"] = banner

            # Match version numbers like 6.1.138, 5.10.209, etc.
            ver_match = re.search(r"Linux version (\d+\.\d+\.\d+[\w.-]*)", banner)
            if ver_match:
                info["kernel_short"] = ver_match.group(1)
                # Major.Minor branch (e.g. 6.1, 5.10, 5.15)
                branch_match = re.search(r"^(\d+\.\d+)", info["kernel_short"])
                if branch_match:
                    info["kernel_branch"] = branch_match.group(1)

        # Detect Architecture
        if b"aarch64" in data or b"ARM aarch64" in data or b"ARM64" in data:
            info["arch"] = "arm64 (AArch64)"
        elif b"armv7" in data or b"ARMv7" in data:
            info["arch"] = "arm32 (ARMv7)"
        elif b"x86_64" in data or b"x86-64" in data:
            info["arch"] = "x86_64"

        # Detect Kernel Compression
        if b"\x1f\x8b\x08" in data:
            info["compression"] = "gzip"
        elif b"\x02\x21\x4c\x18" in data or b"\x04\x22\x4d\x18" in data:
            info["compression"] = "lz4"
        elif b"\x28\xb5\x2f\xfd" in data:
            info["compression"] = "zstd"
        elif b"\xfd7zXZ\x00" in data:
            info["compression"] = "xz"

    except Exception as e:
        logger.warning("Error analyzing boot.img: %s", e)

    return info


def format_analysis_summary(info: dict, filename: str) -> str:
    banner = info["kernel_version"]
    if len(banner) > 160:
        banner = banner[:160] + "..."

    return (
        f"🔍 *Boot Image Details*\n"
        f"━━━━━━━━━━━━━━━━━━━━\n"
        f"📄 *File:* `{filename}`\n"
        f"💾 *Size:* `{info['file_size_mb']} MB`\n"
        f"🗂 *Format:* `{info['format']}`\n"
        f"🏗 *Arch:* `{info['arch']}`\n"
        f"🗜 *Compression:* `{info['compression']}`\n"
        f"🐧 *Stock Kernel:* `{info['kernel_short']}`\n"
        f"📅 *OS Patch Level:* `{info['os_patch_level']}`\n"
        f"\n*Kernel String:*\n"
        f"```\n{banner}\n```"
    )


# ── Remote Artifact & Flavour Version Query ──────────────────────────────────

def query_flavour_versions(kernel_repo: str, branch: str = "6.1") -> dict:
    """
    Queries latest GitHub Actions artifacts from the kernel repo
    to find the specific version available for each of the 4 flavours.
    """
    flavour_map = {f[0]: "Latest" for f in KERNEL_FLAVOURS}
    headers = {"Accept": "application/vnd.github+json"}
    if GH_TOKEN:
        headers["Authorization"] = f"Bearer {GH_TOKEN}"

    api_url = f"https://api.github.com/repos/{kernel_repo}/actions/artifacts?per_page=100"
    try:
        resp = requests.get(api_url, headers=headers, timeout=12)
        if resp.status_code == 200:
            artifacts = resp.json().get("artifacts", [])
            for a in artifacts:
                if a.get("expired"):
                    continue
                name = a.get("name", "")
                for flavour_key, _ in KERNEL_FLAVOURS:
                    if flavour_key.lower() in name.lower() and "anykernel3" in name.lower():
                        # Extract kernel version if present e.g. 6.1.138
                        ver_m = re.search(r"(\d+\.\d+\.\d+)", name)
                        if ver_m and flavour_map[flavour_key] == "Latest":
                            flavour_map[flavour_key] = ver_m.group(1)
    except Exception as e:
        logger.warning("Could not query flavour versions: %s", e)

    return flavour_map


# ── Cloud Staging & Dispatch ─────────────────────────────────────────────────

def upload_to_transfer(file_path: str, filename: str) -> str:
    """Uploads file to transfer.sh returning direct download URL."""
    clean_name = re.sub(r"[^\w\.-]", "_", filename)
    url = f"https://transfer.sh/{clean_name}"
    with open(file_path, "rb") as f:
        resp = requests.put(url, data=f, headers={"Max-Days": "3"}, timeout=180)
    resp.raise_for_status()
    return resp.text.strip()


def trigger_patch_workflow(
    boot_url: str,
    chat_id: int,
    variant: str,
    kernel_repo: str,
    stock_kernel: str,
) -> bool:
    """Dispatches patch-boot.yml GitHub Actions workflow."""
    if not GH_TOKEN or not GH_REPO:
        logger.error("GITHUB_TOKEN or GITHUB_REPO not configured")
        return False

    api_url = f"https://api.github.com/repos/{GH_REPO}/actions/workflows/{WORKFLOW_ID}/dispatches"
    headers = {
        "Authorization": f"Bearer {GH_TOKEN}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
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
    resp = requests.post(api_url, json=payload, headers=headers, timeout=30)
    if resp.status_code == 204:
        return True
    logger.error("Dispatch failed: %s %s", resp.status_code, resp.text)
    return False


def get_latest_run_url() -> str:
    if not GH_TOKEN or not GH_REPO:
        return ""
    try:
        headers = {
            "Authorization": f"Bearer {GH_TOKEN}",
            "Accept": "application/vnd.github+json",
        }
        resp = requests.get(
            f"https://api.github.com/repos/{GH_REPO}/actions/workflows/{WORKFLOW_ID}/runs?per_page=1",
            headers=headers,
            timeout=10,
        )
        runs = resp.json().get("workflow_runs", [])
        if runs:
            return runs[0].get("html_url", "")
    except Exception:
        pass
    return ""


# ── Telegram Handlers ────────────────────────────────────────────────────────

async def cmd_start(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    await update.message.reply_text(
        "👋 *Welcome to BootPatcher!*\n\n"
        "I patch stock Android `boot.img` images using kernels built by **BruhKernel** "
        "(or your own custom kernel fork).\n\n"
        "📋 *How it works:*\n"
        "1️⃣ Send your stock `boot.img` as a **File**\n"
        "2️⃣ I will extract its kernel version, architecture & compression\n"
        "3️⃣ Select your desired flavour: *SukiSU, KernelSU-Next, WKSU, or ReSukiSU*\n"
        "4️⃣ GitHub Actions unpacks, swaps the kernel, and repacks\n"
        "5️⃣ Receive your ready-to-flash `patched_boot.img` directly in chat! 🚀\n\n"
        "Send your `boot.img` now to get started!",
        parse_mode="Markdown",
    )


async def cmd_help(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    kernel_repo = ctx.user_data.get("kernel_repo", KERNEL_REPO)
    await update.message.reply_text(
        "ℹ️ *BootPatcher Guide & Commands*\n\n"
        "*Commands:*\n"
        "/start — Introduction & instructions\n"
        "/help — This help message\n"
        "/status — Check latest GitHub Actions run\n"
        "/source — View source code repositories\n"
        "/repo — Set a custom BruhKernel fork\n\n"
        f"*Current Kernel Source:* `{kernel_repo}`\n"
        "*Available Flavours:*\n"
        "• 🟣 *SukiSU Ultra* (SUSFS + NoMount)\n"
        "• 🔵 *KernelSU-Next* (Modern KSU + SUSFS)\n"
        "• 🟤 *WKSU* (Wild KernelSU)\n"
        "• 🟢 *ReSukiSU* (Alternative SukiSU)\n\n"
        "Credits: Dayto0 for BootKernelChanger concept.",
        parse_mode="Markdown",
    )


async def cmd_source(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    repo = GH_REPO or "nothingnesscore/BootPatcher"
    await update.message.reply_text(
        "📦 *Source Code Repositories*\n\n"
        f"• *BootPatcher Bot & Workflows:*\nhttps://github.com/{repo}\n\n"
        f"• *BruhKernel Build Pipeline:*\nhttps://github.com/nothingnesscore/BruhKernel\n\n"
        "• *BootKernelChanger (Original Concept):*\nhttps://github.com/Dayto0/BootKernelChanger",
        parse_mode="Markdown",
        disable_web_page_preview=True,
    )


async def cmd_status(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    url = get_latest_run_url()
    if url:
        await update.message.reply_text(
            f"🔗 *Latest Workflow Run:*\n{url}",
            parse_mode="Markdown",
            disable_web_page_preview=True,
        )
    else:
        await update.message.reply_text("⚠️ No recent runs found or GitHub credentials missing.")


async def cmd_repo(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    ctx.user_data["awaiting_custom_repo"] = True
    current = ctx.user_data.get("kernel_repo", KERNEL_REPO)
    await update.message.reply_text(
        f"⚙️ *Custom Kernel Repository*\n\n"
        f"Current repo: `{current}`\n\n"
        "To use your own BruhKernel fork, reply with:\n"
        "`username/repo`\n\n"
        "Send /cancel to keep the current repository.",
        parse_mode="Markdown",
    )


async def cmd_cancel(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    ctx.user_data["awaiting_custom_repo"] = False
    await update.message.reply_text("✅ Action cancelled.")


# ── File Upload Handler ──────────────────────────────────────────────────────

async def handle_document(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    doc = update.message.document
    if not doc:
        return

    fname = doc.file_name or "boot.img"
    lower = fname.lower()
    if not (lower.endswith(".img") or "boot" in lower or lower == "boot"):
        await update.message.reply_text(
            "⚠️ Please upload a valid `boot.img` file (ends in `.img` or contains `boot`).",
            parse_mode="Markdown",
        )
        return

    if doc.file_size and doc.file_size > 64 * 1024 * 1024:
        await update.message.reply_text("❌ File too large. Telegram max file size is 64 MB.")
        return

    progress_msg = await update.message.reply_text(
        f"📥 *Received:* `{fname}`\n⏳ *Analysing kernel details...*",
        parse_mode="Markdown",
    )

    try:
        # Download locally for analysis
        tg_file = await ctx.bot.get_file(doc.file_id)
        with tempfile.NamedTemporaryFile(suffix=".img", delete=False) as tmp:
            tmp_path = tmp.name
        await tg_file.download_to_drive(tmp_path)

        # In-depth analysis
        info = extract_boot_info(tmp_path)
        summary_text = format_analysis_summary(info, fname)

        # Upload to staging in parallel
        await ctx.bot.edit_message_text(
            chat_id=update.effective_chat.id,
            message_id=progress_msg.message_id,
            text=f"{summary_text}\n\n⏫ *Uploading to staging server...*",
            parse_mode="Markdown",
        )
        boot_url = upload_to_transfer(tmp_path, fname)
        os.unlink(tmp_path)

        # Query flavour versions from the kernel repo
        kernel_repo = ctx.user_data.get("kernel_repo", KERNEL_REPO)
        await ctx.bot.edit_message_text(
            chat_id=update.effective_chat.id,
            message_id=progress_msg.message_id,
            text=f"{summary_text}\n\n🔍 *Checking available kernel flavours from* `{kernel_repo}`...",
            parse_mode="Markdown",
        )

        flavour_versions = query_flavour_versions(kernel_repo, info.get("kernel_branch", "6.1"))

        # Save session context
        ctx.user_data["boot_url"]   = boot_url
        ctx.user_data["boot_fname"] = fname
        ctx.user_data["boot_info"]  = info

        # Build Interactive Keyboard with flavour names AND their respective target versions!
        keyboard = []
        for key, label in KERNEL_FLAVOURS:
            ver = flavour_versions.get(key, "android14-6.1")
            button_label = f"{label} ({ver})"
            keyboard.append([InlineKeyboardButton(button_label, callback_data=f"patch:{key}")])

        # Fork / Custom Repo Switcher button
        keyboard.append([
            InlineKeyboardButton(f"⚙️ Kernel Repo: {kernel_repo}", callback_data="btn_custom_repo")
        ])

        await ctx.bot.edit_message_text(
            chat_id=update.effective_chat.id,
            message_id=progress_msg.message_id,
            text=(
                f"{summary_text}\n\n"
                f"✅ *Upload & Analysis Complete!*\n"
                f"Select which kernel flavour to patch with:"
            ),
            reply_markup=InlineKeyboardMarkup(keyboard),
            parse_mode="Markdown",
        )

    except Exception as e:
        logger.exception("Failed processing boot.img")
        await ctx.bot.edit_message_text(
            chat_id=update.effective_chat.id,
            message_id=progress_msg.message_id,
            text=f"❌ *Failed to process image:*\n`{e}`",
            parse_mode="Markdown",
        )


# ── Interactive Callback Handler ─────────────────────────────────────────────

async def handle_callback(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    query = update.callback_query
    await query.answer()

    data = query.data
    chat_id = query.message.chat_id

    if data == "btn_custom_repo":
        ctx.user_data["awaiting_custom_repo"] = True
        current = ctx.user_data.get("kernel_repo", KERNEL_REPO)
        await query.edit_message_text(
            f"⚙️ *Configure Kernel Source*\n\n"
            f"Current: `{current}`\n\n"
            "Reply with your GitHub fork in `username/repo` format.\n"
            "e.g. `myuser/BruhKernel`\n\n"
            "Send /cancel to keep current.",
            parse_mode="Markdown",
        )
        return

    if data.startswith("patch:"):
        variant    = data.replace("patch:", "")
        boot_url   = ctx.user_data.get("boot_url")
        boot_fname = ctx.user_data.get("boot_fname", "boot.img")
        boot_info  = ctx.user_data.get("boot_info", {})
        kernel_repo = ctx.user_data.get("kernel_repo", KERNEL_REPO)

        if not boot_url:
            await query.edit_message_text("❌ Session expired. Please upload your `boot.img` again.")
            return

        variant_title = next((label for k, label in KERNEL_FLAVOURS if k == variant), variant)

        await query.edit_message_text(
            f"🚀 *Patching Dispatched to GitHub Actions!*\n"
            f"━━━━━━━━━━━━━━━━━━━━\n"
            f"📄 *File:* `{boot_fname}`\n"
            f"🐧 *Stock Kernel:* `{boot_info.get('kernel_short', 'Unknown')}`\n"
            f"🏗 *Arch:* `{boot_info.get('arch', 'Unknown')}`\n"
            f"💉 *Injecting:* {variant_title}\n"
            f"📦 *Kernel Source:* `{kernel_repo}`\n\n"
            f"⏱ *Estimated Time:* ~3 to 6 minutes\n"
            f"I will send live status updates and your final `patched_boot.img` when finished! ☕",
            parse_mode="Markdown",
        )

        ok = trigger_patch_workflow(
            boot_url=boot_url,
            chat_id=chat_id,
            variant=variant,
            kernel_repo=kernel_repo,
            stock_kernel=boot_info.get("kernel_short", "Unknown"),
        )

        run_url = get_latest_run_url()
        if run_url:
            await ctx.bot.send_message(
                chat_id=chat_id,
                text=f"🔗 [Watch GitHub Actions Live Log]({run_url})",
                parse_mode="Markdown",
                disable_web_page_preview=True,
            )


# ── Text Input Handler (Custom Repo) ─────────────────────────────────────────

async def handle_text(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    if not ctx.user_data.get("awaiting_custom_repo"):
        return

    text = update.message.text.strip()
    if "/" not in text or len(text.split("/")) != 2:
        await update.message.reply_text(
            "⚠️ Invalid format. Must be `owner/repository`, e.g. `nothingnesscore/BruhKernel`.",
            parse_mode="Markdown",
        )
        return

    ctx.user_data["kernel_repo"] = text
    ctx.user_data["awaiting_custom_repo"] = False

    boot_info = ctx.user_data.get("boot_info")
    if boot_info and ctx.user_data.get("boot_url"):
        # Re-query flavour versions for this newly selected repo
        flavour_versions = query_flavour_versions(text, boot_info.get("kernel_branch", "6.1"))
        keyboard = []
        for key, label in KERNEL_FLAVOURS:
            ver = flavour_versions.get(key, "android14-6.1")
            keyboard.append([InlineKeyboardButton(f"{label} ({ver})", callback_data=f"patch:{key}")])
        keyboard.append([InlineKeyboardButton(f"⚙️ Kernel Repo: {text}", callback_data="btn_custom_repo")])

        await update.message.reply_text(
            f"✅ Kernel source set to `{text}`!\n\n"
            f"Stock Kernel: `{boot_info.get('kernel_short', 'Unknown')}`\n"
            f"Select your flavour to proceed with patching:",
            reply_markup=InlineKeyboardMarkup(keyboard),
            parse_mode="Markdown",
        )
    else:
        await update.message.reply_text(
            f"✅ Kernel source updated to `{text}`!\n"
            f"Send a `boot.img` anytime to start patching.",
            parse_mode="Markdown",
        )


# ── Entry Point ──────────────────────────────────────────────────────────────

def main() -> None:
    if not BOT_TOKEN:
        logger.error("FATAL: TELEGRAM_BOT_TOKEN environment variable is not set.")
        sys.exit(1)

    if not GH_TOKEN:
        logger.warning("WARNING: GITHUB_TOKEN is not set. Workflow dispatching will fail.")
    if not GH_REPO:
        logger.warning("WARNING: GITHUB_REPO is not set. Default repo will not be targeted.")

    app = Application.builder().token(BOT_TOKEN).build()

    app.add_handler(CommandHandler("start",   cmd_start))
    app.add_handler(CommandHandler("help",    cmd_help))
    app.add_handler(CommandHandler("source",  cmd_source))
    app.add_handler(CommandHandler("status",  cmd_status))
    app.add_handler(CommandHandler("repo",    cmd_repo))
    app.add_handler(CommandHandler("cancel",  cmd_cancel))
    app.add_handler(MessageHandler(filters.Document.ALL, handle_document))
    app.add_handler(CallbackQueryHandler(handle_callback))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_text))

    logger.info("BootPatcher Bot running with default kernel repo: %s", KERNEL_REPO)
    app.run_polling(allowed_updates=Update.ALL_TYPES)


if __name__ == "__main__":
    main()
