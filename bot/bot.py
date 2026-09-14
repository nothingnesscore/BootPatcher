"""
BootPatcher Telegram Bot
========================
Send a boot.img file to this bot → it triggers a GitHub Actions workflow
that patches the kernel using the latest BruhKernel android14-6.1 build
and sends back the patched boot.img.

Setup:
  1. pip install python-telegram-bot requests
  2. Set environment variables (or create a .env file):
       TELEGRAM_BOT_TOKEN   - your bot token from BotFather
       GITHUB_TOKEN         - a GitHub PAT with repo + actions:write scopes
       GITHUB_REPO          - your fork of this repo, e.g. "YourUser/BootPatcher"
  3. python bot.py
"""

import os
import sys
import logging
import asyncio
import tempfile
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

BOT_TOKEN   = os.environ.get("TELEGRAM_BOT_TOKEN", "REDACTED_BOT_TOKEN")
GH_TOKEN    = os.environ.get("GITHUB_TOKEN", "")
GH_REPO     = os.environ.get("GITHUB_REPO", "")          # e.g. "YourUser/BootPatcher"
WORKFLOW_ID = "patch-boot.yml"

KERNEL_VARIANTS = ["SukiSU", "KernelSU-Next", "WKSU", "ReSukiSU"]

logging.basicConfig(
    format="%(asctime)s | %(levelname)s | %(message)s",
    level=logging.INFO,
)
logger = logging.getLogger(__name__)


# ── Helper: upload file to transfer.sh ──────────────────────────────────────

def upload_to_transfer(file_path: str, filename: str) -> str:
    """Upload a file to transfer.sh and return the public URL."""
    url = f"https://transfer.sh/{filename}"
    with open(file_path, "rb") as f:
        resp = requests.put(url, data=f, headers={"Max-Days": "3"}, timeout=120)
    resp.raise_for_status()
    return resp.text.strip()


# ── Helper: trigger GitHub Actions workflow ──────────────────────────────────

def trigger_workflow(boot_url: str, chat_id: str, variant: str) -> bool:
    """Dispatch the patch-boot workflow on GitHub Actions."""
    if not GH_TOKEN or not GH_REPO:
        logger.error("GITHUB_TOKEN or GITHUB_REPO not set!")
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
            "boot_img_url":    boot_url,
            "chat_id":         str(chat_id),
            "kernel_variant":  variant,
        },
    }

    resp = requests.post(api_url, json=payload, headers=headers, timeout=30)
    if resp.status_code == 204:
        return True
    logger.error("GitHub API returned %s: %s", resp.status_code, resp.text)
    return False


# ── Get latest GH Actions run URL for feedback ──────────────────────────────

def get_latest_run_url() -> str:
    if not GH_TOKEN or not GH_REPO:
        return ""
    api_url = f"https://api.github.com/repos/{GH_REPO}/actions/workflows/{WORKFLOW_ID}/runs?per_page=1"
    headers = {
        "Authorization": f"Bearer {GH_TOKEN}",
        "Accept": "application/vnd.github+json",
    }
    try:
        resp = requests.get(api_url, headers=headers, timeout=15)
        data = resp.json()
        runs = data.get("workflow_runs", [])
        if runs:
            return runs[0].get("html_url", "")
    except Exception:
        pass
    return ""


# ── Bot handlers ─────────────────────────────────────────────────────────────

async def cmd_start(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    await update.message.reply_text(
        "👋 *Welcome to BootPatcher!*\n\n"
        "Send me your stock `boot.img` file and I'll patch it with the latest "
        "*BruhKernel* (android14-6.1) kernel and send back the modded image.\n\n"
        "📋 *How to use:*\n"
        "1️⃣ Send your `boot.img` as a *file* (not compressed)\n"
        "2️⃣ Choose the kernel variant you want\n"
        "3️⃣ Wait ~5 minutes while GitHub Actions patches it\n"
        "4️⃣ Receive your patched `boot.img`!\n\n"
        "Use /help for more info.",
        parse_mode="Markdown",
    )


async def cmd_help(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    await update.message.reply_text(
        "ℹ️ *BootPatcher Help*\n\n"
        "*Commands:*\n"
        "/start — Introduction\n"
        "/help  — This message\n"
        "/status — Check GitHub Actions status\n\n"
        "*Kernel variants available:*\n"
        "• SukiSU _(default, recommended)_\n"
        "• KernelSU-Next\n"
        "• WKSU\n"
        "• ReSukiSU\n\n"
        "*Source:* nothingnesscore/BruhKernel\n"
        "All builds are android14-6.1",
        parse_mode="Markdown",
    )


async def cmd_status(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    run_url = get_latest_run_url()
    if run_url:
        await update.message.reply_text(
            f"🔗 Latest workflow run:\n{run_url}",
        )
    else:
        await update.message.reply_text("⚠️ Could not fetch workflow status. Check GitHub manually.")


async def handle_document(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    doc = update.message.document
    if not doc:
        return

    fname = doc.file_name or ""
    # Accept only .img files (or files without extension that might be boot images)
    if not (fname.lower().endswith(".img") or fname.lower() == "boot" or "boot" in fname.lower()):
        await update.message.reply_text(
            "⚠️ Please send a valid `boot.img` file.\n"
            "The filename should contain 'boot' or end with '.img'.",
            parse_mode="Markdown",
        )
        return

    # Check file size — GitHub Actions can handle up to ~100 MB, Telegram allows 50 MB
    if doc.file_size and doc.file_size > 50 * 1024 * 1024:
        await update.message.reply_text("❌ File too large. Maximum size is 50 MB.")
        return

    # Store file info in user_data and ask which kernel variant
    ctx.user_data["pending_file_id"]   = doc.file_id
    ctx.user_data["pending_file_name"] = fname

    keyboard = [
        [InlineKeyboardButton(f"✅ {v}" if v == "SukiSU" else v, callback_data=f"variant:{v}")]
        for v in KERNEL_VARIANTS
    ]
    await update.message.reply_text(
        f"📁 Received: `{fname}` ({doc.file_size // 1024} KB)\n\n"
        "Choose the kernel variant to patch with:",
        reply_markup=InlineKeyboardMarkup(keyboard),
        parse_mode="Markdown",
    )


async def handle_variant_choice(update: Update, ctx: ContextTypes.DEFAULT_TYPE) -> None:
    query = update.callback_query
    await query.answer()

    variant = query.data.replace("variant:", "")
    file_id = ctx.user_data.get("pending_file_id")
    fname   = ctx.user_data.get("pending_file_name", "boot.img")
    chat_id = query.message.chat_id

    if not file_id:
        await query.edit_message_text("❌ Session expired. Please send your boot.img again.")
        return

    status_msg = await query.edit_message_text(
        f"⏳ *Processing your boot.img...*\n\n"
        f"📦 Variant: `{variant}`\n"
        f"📤 Uploading to staging server...",
        parse_mode="Markdown",
    )

    try:
        # 1. Download the file from Telegram
        tg_file = await ctx.bot.get_file(file_id)
        with tempfile.NamedTemporaryFile(suffix=".img", delete=False) as tmp:
            tmp_path = tmp.name

        await tg_file.download_to_drive(tmp_path)
        logger.info("Downloaded boot.img to %s", tmp_path)

        # 2. Upload to transfer.sh
        await ctx.bot.edit_message_text(
            chat_id=chat_id,
            message_id=status_msg.message_id,
            text=(
                f"⏳ *Processing your boot.img...*\n\n"
                f"📦 Variant: `{variant}`\n"
                f"📤 Uploading to staging server... ✅\n"
                f"🚀 Triggering GitHub Actions..."
            ),
            parse_mode="Markdown",
        )

        boot_url = upload_to_transfer(tmp_path, fname)
        logger.info("Uploaded boot.img to: %s", boot_url)

        os.unlink(tmp_path)

        # 3. Trigger GitHub Actions
        ok = trigger_workflow(boot_url, str(chat_id), variant)

        if ok:
            run_url = get_latest_run_url()
            run_link = f"\n🔗 [Watch progress]({run_url})" if run_url else ""

            await ctx.bot.edit_message_text(
                chat_id=chat_id,
                message_id=status_msg.message_id,
                text=(
                    f"✅ *Workflow triggered!*\n\n"
                    f"📦 Variant: `{variant}`\n"
                    f"⏱ Estimated time: *3–7 minutes*\n\n"
                    f"I'll send your patched `boot.img` here when it's ready!{run_link}"
                ),
                parse_mode="Markdown",
                disable_web_page_preview=True,
            )
        else:
            await ctx.bot.edit_message_text(
                chat_id=chat_id,
                message_id=status_msg.message_id,
                text=(
                    "❌ *Failed to trigger GitHub Actions.*\n\n"
                    "Make sure the bot is configured with a valid GITHUB_TOKEN and GITHUB_REPO."
                ),
                parse_mode="Markdown",
            )

    except Exception as e:
        logger.exception("Error handling file")
        await ctx.bot.send_message(
            chat_id=chat_id,
            text=f"❌ An error occurred:\n`{e}`",
            parse_mode="Markdown",
        )


# ── Main ─────────────────────────────────────────────────────────────────────

def main() -> None:
    if not BOT_TOKEN:
        logger.error("TELEGRAM_BOT_TOKEN is not set!")
        sys.exit(1)

    if not GH_TOKEN:
        logger.warning("GITHUB_TOKEN is not set — workflow triggering will fail!")
    if not GH_REPO:
        logger.warning("GITHUB_REPO is not set — workflow triggering will fail!")

    app = Application.builder().token(BOT_TOKEN).build()

    app.add_handler(CommandHandler("start",  cmd_start))
    app.add_handler(CommandHandler("help",   cmd_help))
    app.add_handler(CommandHandler("status", cmd_status))
    app.add_handler(MessageHandler(filters.Document.ALL, handle_document))
    app.add_handler(CallbackQueryHandler(handle_variant_choice, pattern=r"^variant:"))

    logger.info("BootPatcher bot is running...")
    app.run_polling(allowed_updates=Update.ALL_TYPES)


if __name__ == "__main__":
    main()
