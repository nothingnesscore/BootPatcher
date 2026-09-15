"""
Helper to update Telegram status messages with live progress and specs.
"""
import os
import sys
import json
import urllib.request

BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()

def edit_message(chat_id: str, message_id: str, text: str):
    if not BOT_TOKEN or not chat_id or not message_id:
        return
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/editMessageText"
    payload = {
        "chat_id": chat_id,
        "message_id": int(message_id),
        "text": text,
        "parse_mode": "Markdown",
        "disable_web_page_preview": True
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as res:
            pass
    except Exception as e:
        print(f"[Warning] Failed to edit status message: {e}", file=sys.stderr)

if __name__ == "__main__":
    if len(sys.argv) >= 4:
        c_id = sys.argv[1]
        m_id = sys.argv[2]
        msg_file_or_text = sys.argv[3]
        if os.path.exists(msg_file_or_text):
            with open(msg_file_or_text, "r", encoding="utf-8") as f:
                content = f.read()
        else:
            content = msg_file_or_text
        edit_message(c_id, m_id, content)
