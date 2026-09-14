/**
 * BootPatcher Cloudflare Worker (Serverless Telegram Webhook)
 * ============================================================
 * Zero idle runners. Zero wasted GitHub Actions minutes.
 *
 * How it works:
 * 1. User sends boot.img to @umbromomento_bot.
 * 2. Telegram triggers this serverless webhook (<20ms).
 * 3. User chooses their kernel flavour (SukiSU, KernelSU-Next, WKSU, ReSukiSU).
 * 4. This worker dispatches GitHub Actions (patch-boot.yml) on-demand!
 * 5. GitHub Actions runner fires up ONLY for the duration of the patch (~3 min),
 *    delivers patched_boot.img directly to the user's Telegram, and shuts down.
 *
 * Environment Secrets required in Cloudflare Worker:
 * - TELEGRAM_BOT_TOKEN : BotFather token
 * - GITHUB_TOKEN       : GitHub PAT with repo + workflow scopes
 * - GITHUB_REPO        : "nothingnesscore/BootPatcher"
 * - KERNEL_REPO        : "nothingnesscore/BruhKernel" (optional)
 */

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("BootPatcher Webhook Active", { status: 200 });
    }

    try {
      const update = await request.json();
      await handleUpdate(update, env);
    } catch (err) {
      console.error("Error processing update:", err);
    }

    return new Response("OK", { status: 200 });
  },
};

const FLAVOURS = [
  { id: "SukiSU",        label: "🟣 SukiSU Ultra (Recommended)" },
  { id: "KernelSU-Next", label: "🔵 KernelSU-Next" },
  { id: "WKSU",          label: "🟤 WKSU" },
  { id: "ReSukiSU",      label: "🟢 ReSukiSU" },
];

async function handleUpdate(update, env) {
  const token = env.TELEGRAM_BOT_TOKEN;
  const repo  = env.GITHUB_REPO || "nothingnesscore/BootPatcher";
  const kRepo = env.KERNEL_REPO || "nothingnesscore/BruhKernel";

  // 1. Handle Slash Commands
  if (update.message && update.message.text) {
    const text = update.message.text.trim();
    const chatId = update.message.chat.id;

    if (text.startsWith("/start")) {
      const msg =
        "👋 *Welcome to BootPatcher!*\n\n" +
        "I patch Android `boot.img` files on-demand using GKI kernels from *BruhKernel*.\n\n" +
        "⚡ *On-Demand Architecture:*\n" +
        "GitHub Actions runners are only fired up when you upload a file. " +
        "Zero queue congestion, fair share for everyone!\n\n" +
        "📋 *How to use:*\n" +
        "1️⃣ Send your `boot.img` as an uncompressed *File*\n" +
        "2️⃣ Pick your flavour: *SukiSU, KernelSU-Next, WKSU, ReSukiSU*\n" +
        "3️⃣ A dedicated runner builds and returns your `patched_boot.img` in ~3 mins!";
      await tgSend(token, "sendMessage", { chat_id: chatId, text: msg, parse_mode: "Markdown" });
      return;
    }

    if (text.startsWith("/help")) {
      const msg =
        "ℹ️ *BootPatcher Help*\n\n" +
        "• Send a `boot.img` file to begin patching.\n" +
        "• Kernels are pulled from: `" + kRepo + "` (android14-6.1)\n" +
        "• Patching engine: `magiskboot` on clean Linux runners\n" +
        "• Source code: https://github.com/" + repo;
      await tgSend(token, "sendMessage", { chat_id: chatId, text: msg, parse_mode: "Markdown" });
      return;
    }
  }

  // 2. Handle File Upload (boot.img)
  if (update.message && update.message.document) {
    const doc = update.message.document;
    const chatId = update.message.chat.id;
    const fname = doc.file_name || "boot.img";

    const lower = fname.toLowerCase();
    if (!lower.endsWith(".img") && !lower.includes("boot")) {
      await tgSend(token, "sendMessage", {
        chat_id: chatId,
        text: "⚠️ Please upload a valid `boot.img` file.",
        parse_mode: "Markdown",
      });
      return;
    }

    // Create inline buttons for the 4 flavours with file_id encoded
    const keyboard = FLAVOURS.map((f) => [
      {
        text: f.label,
        callback_data: `p:${f.id}:${doc.file_id}:${encodeURIComponent(fname)}`,
      },
    ]);

    const sizeMB = (doc.file_size / (1024 * 1024)).toFixed(2);
    const msg =
      `📁 *Received:* \`${fname}\` (${sizeMB} MB)\n\n` +
      `Choose which kernel flavour to inject with your stock boot image:`;

    await tgSend(token, "sendMessage", {
      chat_id: chatId,
      text: msg,
      parse_mode: "Markdown",
      reply_markup: { inline_keyboard: keyboard },
    });
    return;
  }

  // 3. Handle Flavour Button Selection (Callback Query)
  if (update.callback_query) {
    const cq = update.callback_query;
    const data = cq.data || "";

    if (data.startsWith("p:")) {
      const parts = data.split(":");
      const flavour = parts[1];
      const fileId  = parts[2];
      const fname   = decodeURIComponent(parts[3] || "boot.img");
      const chatId  = cq.message.chat.id;

      await tgSend(token, "answerCallbackQuery", { callback_query_id: cq.id });

      // Edit message to indicate job dispatching
      await tgSend(token, "editMessageText", {
        chat_id: chatId,
        message_id: cq.message.message_id,
        text:
          `🚀 *Spinning up GitHub Actions Cloud Runner!*\n` +
          `━━━━━━━━━━━━━━━━━━━━\n` +
          `📄 *File:* \`${fname}\`\n` +
          `💉 *Flavour:* \`${flavour}\`\n` +
          `🌐 *Kernel Source:* \`${kRepo}\`\n\n` +
          `⏳ A dedicated runner has been queued. Unpacking, swapping kernel, and repacking now...\n` +
          `You will receive \`patched_boot.img\` here in 3–5 minutes!`,
        parse_mode: "Markdown",
      });

      // Get direct file download link from Telegram
      const fileRes = await tgSend(token, "getFile", { file_id: fileId });
      if (!fileRes.ok || !fileRes.result.file_path) {
        await tgSend(token, "sendMessage", {
          chat_id: chatId,
          text: "❌ Failed to retrieve file from Telegram servers. Please re-upload.",
        });
        return;
      }

      const fileUrl = `https://api.telegram.org/file/bot${token}/${fileRes.result.file_path}`;

      // Dispatch GitHub Actions workflow_dispatch
      const dispatchOk = await dispatchGitHubAction(env, {
        boot_img_url:   fileUrl,
        chat_id:        String(chatId),
        kernel_variant: flavour,
        kernel_repo:    kRepo,
        stock_kernel:   "Android GKI",
      });

      if (!dispatchOk) {
        await tgSend(token, "sendMessage", {
          chat_id: chatId,
          text: "❌ Failed to dispatch GitHub Actions runner. Please check repository permissions.",
        });
      }
    }
  }
}

async function tgSend(token, method, payload) {
  const url = `https://api.telegram.org/bot${token}/${method}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  return res.json();
}

async function dispatchGitHubAction(env, inputs) {
  const repo = env.GITHUB_REPO || "nothingnesscore/BootPatcher";
  const url = `https://api.github.com/repos/${repo}/actions/workflows/patch-boot.yml/dispatches`;

  const res = await fetch(url, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
      "Accept": "application/vnd.github+json",
      "User-Agent": "BootPatcher-Serverless-Webhook",
      "X-GitHub-Api-Version": "2022-11-28",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      ref: "main",
      inputs: inputs,
    }),
  });

  return res.status === 204;
}
