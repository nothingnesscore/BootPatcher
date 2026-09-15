"""
Helper to update and manage Telegram status messages with dynamic live progress,
hardware specs, and clean message deletion.
"""

import os
import sys
import json
import time
import threading
import urllib.request
import urllib.error

BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()


def make_bar(pct: int, length: int = 10) -> str:
    """Generate a clean visual block progress bar: e.g. [■■■■□□□□□□]"""
    pct = max(0, min(100, pct))
    filled = int(round(length * (pct / 100.0)))
    filled = max(0, min(length, filled))
    empty = length - filled
    return f"[{'■' * filled}{'□' * empty}]"


def edit_message(chat_id: str, message_id: str, text: str) -> bool:
    """Edit an existing Telegram message via Bot API editMessageText."""
    token = os.environ.get("TELEGRAM_BOT_TOKEN", BOT_TOKEN).strip()
    if not token or not chat_id or not message_id:
        return False

    url = f"https://api.telegram.org/bot{token}/editMessageText"
    payload = {
        "chat_id": chat_id,
        "message_id": int(message_id),
        "text": text,
        "parse_mode": "Markdown",
        "disable_web_page_preview": True,
    }

    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )

    try:
        with urllib.request.urlopen(req, timeout=8) as res:
            return res.status == 200
    except urllib.error.HTTPError as e:
        # 429 Too Many Requests or 400 MessageNotModified are non-fatal
        if e.code not in (429, 400):
            print(f"[Warning] Failed to edit status message ({e.code}): {e.reason}", file=sys.stderr, flush=True)
        return False
    except Exception as e:
        print(f"[Warning] Error editing status message: {e}", file=sys.stderr, flush=True)
        return False


def delete_message(chat_id: str, message_id: str) -> bool:
    """Delete a Telegram message via Bot API deleteMessage (for clean chat cleanup)."""
    token = os.environ.get("TELEGRAM_BOT_TOKEN", BOT_TOKEN).strip()
    if not token or not chat_id or not message_id:
        return False

    url = f"https://api.telegram.org/bot{token}/deleteMessage"
    payload = {
        "chat_id": chat_id,
        "message_id": int(message_id),
    }

    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )

    try:
        with urllib.request.urlopen(req, timeout=8) as res:
            return res.status == 200
    except Exception as e:
        print(f"[Warning] Could not delete status message {message_id}: {e}", file=sys.stderr, flush=True)
        return False


class TelegramProgressReporter:
    """
    Throttled live progress updater for long-running streaming operations
    (MTProto download and upload). Ensures Telegram rate limits (~1 edit/sec)
    are respected by enforcing a minimum interval (default 2.8s).
    Dispatches edits in background daemon threads so transfer loops never stall.
    """

    def __init__(
        self,
        chat_id: str,
        status_message_id: str,
        flavour: str = "SukiSU",
        device: str = "peridot",
        min_interval: float = 2.8,
    ):
        self.chat_id = str(chat_id) if chat_id else ""
        self.status_message_id = str(status_message_id) if status_message_id else ""
        self.flavour = flavour or "SukiSU"
        self.device = device or "peridot"
        self.min_interval = min_interval
        self.last_update_time = 0.0
        self.last_pct = -1
        self.repo = os.environ.get("GITHUB_REPOSITORY", "nothingnesscore/BootPatcher")
        self.run_id = os.environ.get("GITHUB_RUN_ID", "")

    def is_active(self) -> bool:
        return bool(self.chat_id and self.status_message_id)

    def update(
        self,
        current: int,
        total: int,
        stage_label: str,
        stage_min_pct: int,
        stage_max_pct: int,
        force: bool = False,
    ):
        if not self.is_active() or total <= 0:
            return

        pct_transfer = max(0, min(100, int(current / total * 100)))
        now = time.time()

        # Throttle: update if forced, 100%, or elapsed >= min_interval and progressed >= 5%
        if not force and pct_transfer < 100:
            if now - self.last_update_time < self.min_interval:
                return
            if abs(pct_transfer - self.last_pct) < 5:
                return

        self.last_update_time = now
        self.last_pct = pct_transfer

        cur_mb = round(current / (1024 * 1024), 1)
        tot_mb = round(total / (1024 * 1024), 1)

        # Scale transfer pct to stage's overall progress percentage
        span = stage_max_pct - stage_min_pct
        overall_pct = stage_min_pct + int(pct_transfer * (span / 100.0))
        overall_pct = max(stage_min_pct, min(stage_max_pct, overall_pct))

        bar = make_bar(overall_pct, length=10)

        run_link = f"https://github.com/{self.repo}/actions/runs/{self.run_id}" if self.run_id else ""
        log_line = f"\n🔗 [Live Runner Log]({run_link})" if run_link else ""

        text = (
            f"⚡ *Building Your Patched Boot Image*\n"
            f"━━━━━━━━━━━━━━━━━━━━\n"
            f"📦 *Flavour:* `{self.flavour}`\n"
            f"📱 *Target Device:* `{self.device}` / GKI 2.0\n"
            f"🔄 *Progress:* `{bar} {overall_pct}%`\n"
            f"⏳ *Status:* {stage_label} ({cur_mb}/{tot_mb} MB)..."
            f"{log_line}"
        )

        # Dispatch non-blocking in background daemon thread
        threading.Thread(
            target=edit_message,
            args=(self.chat_id, self.status_message_id, text),
            daemon=True,
        ).start()


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage:")
        print("  python3 tg_progress.py delete <chat_id> <message_id>")
        print("  python3 tg_progress.py edit <chat_id> <message_id> <text_or_file>")
        print("  python3 tg_progress.py <chat_id> <message_id> <text_or_file>  (default edit)")
        sys.exit(1)

    cmd = sys.argv[1].lower()

    if cmd == "delete":
        c_id = sys.argv[2]
        m_id = sys.argv[3]
        delete_message(c_id, m_id)
    elif cmd == "edit":
        c_id = sys.argv[2]
        m_id = sys.argv[3]
        msg_input = sys.argv[4] if len(sys.argv) > 4 else ""
        if os.path.exists(msg_input):
            with open(msg_input, "r", encoding="utf-8") as f:
                content = f.read()
        else:
            content = msg_input
        edit_message(c_id, m_id, content)
    else:
        # Backward-compatible 3-arg mode: sys.argv[1]=chat_id, sys.argv[2]=message_id, sys.argv[3]=text
        c_id = sys.argv[1]
        m_id = sys.argv[2]
        msg_input = sys.argv[3] if len(sys.argv) > 3 else ""
        if os.path.exists(msg_input):
            with open(msg_input, "r", encoding="utf-8") as f:
                content = f.read()
        else:
            content = msg_input
        edit_message(c_id, m_id, content)

