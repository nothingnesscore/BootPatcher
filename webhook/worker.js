/**
 * BootPatcher Cloudflare Worker (Serverless Telegram Webhook)
 * ============================================================
 * Zero idle runners. Zero wasted GitHub Actions minutes.
 *
 * Capabilities:
 * 1. Inspects uploaded boot.img directly (kernel banner, uname, arch, format, compression).
 * 2. Queries latest BruhKernel builds and displays interactive flavour buttons.
 * 3. Dispatches GitHub Actions runner on-demand with safe callback_data (<64 bytes).
 * 4. Runner executes patching, repacks with magiskboot, and sends patched_boot.img back!
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

const BASE_FLAVOURS = [
  { id: "SukiSU",        label: "🟣 SukiSU Ultra" },
  { id: "KernelSU-Next", label: "🔵 KernelSU-Next" },
  { id: "WKSU",          label: "🟤 WKSU" },
  { id: "ReSukiSU",      label: "🟢 ReSukiSU" },
];

async function handleUpdate(update, env) {
  const token = env.TELEGRAM_BOT_TOKEN;
  const repo  = env.GITHUB_REPO || "nothingnesscore/BootPatcher";
  const kRepo = env.KERNEL_REPO || "nothingnesscore/BruhKernel";

  if (!token) {
    console.error("TELEGRAM_BOT_TOKEN missing");
    return;
  }

  // 1. Handle Slash Commands
  if (update.message && update.message.text) {
    const text = update.message.text.trim();
    const chatId = update.message.chat.id;

    if (text.startsWith("/start")) {
      const msg =
        "👋 *Welcome to BootPatcher!*\n\n" +
        "I patch Android `boot.img` files on-demand using GKI kernels from *BruhKernel*.\n\n" +
        "⚡ *How It Works:*\n" +
        "1️⃣ Send your stock `boot.img` as an uncompressed *File*\n" +
        "2️⃣ I will inspect the kernel banner, uname, architecture & format\n" +
        "3️⃣ Choose your flavour: *SukiSU, KernelSU-Next, WKSU, or ReSukiSU*\n" +
        "4️⃣ A dedicated GitHub Actions runner fires up on-demand to patch and repack\n" +
        "5️⃣ Receive your ready-to-flash `patched_boot.img` in ~3 mins! 🚀\n\n" +
        "📎 *Upload your stock boot.img as a File to begin!*";
      await tgSend(token, "sendMessage", { chat_id: chatId, text: msg, parse_mode: "Markdown" });
      return;
    }

    if (text.startsWith("/help")) {
      const msg =
        "ℹ️ *BootPatcher Help*\n\n" +
        "• Send a `boot.img` file to inspect and patch.\n" +
        "• Kernels sourced from: `" + kRepo + "` (android14-6.1)\n" +
        "• Patching engine: `magiskboot` on clean Linux runners\n" +
        "• Repository: https://github.com/" + repo;
      await tgSend(token, "sendMessage", { chat_id: chatId, text: msg, parse_mode: "Markdown" });
      return;
    }
  }

  // 2. Handle File Upload (boot.img)
  if (update.message && update.message.document) {
    const doc = update.message.document;
    const chatId = update.message.chat.id;
    const msgId = update.message.message_id;
    const fname = doc.file_name || "boot.img";

    const lower = fname.toLowerCase();
    if (!lower.endsWith(".img") && !lower.includes("boot")) {
      await tgSend(token, "sendMessage", {
        chat_id: chatId,
        text: "⚠️ Please upload a valid `boot.img` file (ends in `.img` or contains `boot`).",
        parse_mode: "Markdown",
        reply_parameters: { message_id: msgId },
      });
      return;
    }

    // Acknowledge upload
    const statusRes = await tgSend(token, "sendMessage", {
      chat_id: chatId,
      text: `📥 Received \`${fname}\` — inspecting kernel strings & headers...`,
      parse_mode: "Markdown",
      reply_parameters: { message_id: msgId },
    });

    const statusMsgId = statusRes.result?.message_id;

    // Get file path from Telegram to inspect headers & strings
    let info = {
      format: "Android Boot Image",
      arch: "arm64 (AArch64)",
      compression: "raw / none",
      kernel_short: "Unknown",
      kernel_banner: "Android GKI",
      os_version: "Unknown",
      os_patch_level: "Unknown",
      file_size_mb: (doc.file_size / (1024 * 1024)).toFixed(2),
    };

    try {
      const fileRes = await tgSend(token, "getFile", { file_id: doc.file_id });
      if (fileRes.ok && fileRes.result.file_path) {
        const fileUrl = `https://api.telegram.org/file/bot${token}/${fileRes.result.file_path}`;
        
        // Fetch first 256 KB of image for header and string inspection
        const rangeRes = await fetch(fileUrl, {
          headers: { Range: "bytes=0-262143" },
        });

        if (rangeRes.ok || rangeRes.status === 206) {
          const buffer = await rangeRes.arrayBuffer();
          info = parseBootBuffer(buffer, doc.file_size, info);
        }
      }
    } catch (e) {
      console.error("Inspection error:", e);
    }

    // Query available flavour versions from GitHub API in parallel
    const versions = await fetchFlavourVersions(kRepo, env.GITHUB_TOKEN);

    // Build inline keyboard with SHORT callback_data (safe <64 bytes)
    // We only pass the flavour key e.g. "p:SukiSU"
    const keyboard = BASE_FLAVOURS.map((f) => {
      const ver = versions[f.id] || "6.1.138";
      return [
        {
          text: `${f.label} (${ver})`,
          callback_data: `p:${f.id}`,
        },
      ];
    });

    const bannerPreview = info.kernel_banner.length > 180
      ? info.kernel_banner.substring(0, 180) + "..."
      : info.kernel_banner;

    const analysisMsg =
      `🔍 *Boot Image Details*\n` +
      `━━━━━━━━━━━━━━━━━━━━\n` +
      `📄 *File:* \`${fname}\`\n` +
      `💾 *Size:* \`${info.file_size_mb} MB\`\n` +
      `🗂 *Format:* \`${info.format}\`\n` +
      `🏗 *Arch:* \`${info.arch}\`\n` +
      `🗜 *Compression:* \`${info.compression}\`\n` +
      `🐧 *Stock Kernel:* \`${info.kernel_short}\`\n` +
      `📅 *OS Patch Level:* \`${info.os_patch_level}\`\n\n` +
      `*Kernel String:*\n` +
      `\`\`\`\n${bannerPreview}\n\`\`\`\n\n` +
      `Select which kernel flavour to patch with:`;

    if (statusMsgId) {
      await tgSend(token, "editMessageText", {
        chat_id: chatId,
        message_id: statusMsgId,
        text: analysisMsg,
        parse_mode: "Markdown",
        reply_markup: { inline_keyboard: keyboard },
      });
    } else {
      await tgSend(token, "sendMessage", {
        chat_id: chatId,
        text: analysisMsg,
        parse_mode: "Markdown",
        reply_parameters: { message_id: msgId },
        reply_markup: { inline_keyboard: keyboard },
      });
    }
    return;
  }

  // 3. Handle Flavour Button Selection (Callback Query)
  if (update.callback_query) {
    const cq = update.callback_query;
    const data = cq.data || "";

    if (data.startsWith("p:")) {
      const flavour = data.substring(2);
      const chatId  = cq.message.chat.id;
      const msgId   = cq.message.message_id;

      await tgSend(token, "answerCallbackQuery", { callback_query_id: cq.id });

      // Retrieve the original document from reply_to_message
      const replyMsg = cq.message.reply_to_message;
      const doc = replyMsg?.document;
      const fname = doc?.file_name || "boot.img";

      if (!doc || !doc.file_id) {
        await tgSend(token, "sendMessage", {
          chat_id: chatId,
          text: "❌ Session expired or original boot.img not found. Please upload your `boot.img` again.",
          parse_mode: "Markdown",
        });
        return;
      }

      const flavourTitle = BASE_FLAVOURS.find((f) => f.id === flavour)?.label || flavour;

      // Update message to show runner status
      await tgSend(token, "editMessageText", {
        chat_id: chatId,
        message_id: msgId,
        text:
          `🚀 *GitHub Actions Runner Dispatched!*\n` +
          `━━━━━━━━━━━━━━━━━━━━\n` +
          `📄 *File:* \`${fname}\`\n` +
          `💉 *Flavour:* ${flavourTitle}\n` +
          `🌐 *Kernel Source:* \`${kRepo}\`\n\n` +
          `⏳ An on-demand runner is spinning up now:\n` +
          `1. Unpacking boot image with magiskboot\n` +
          `2. Verifying kernel banner & uname strings\n` +
          `3. Injecting \`${flavour}\` build\n` +
          `4. Repacking and sending back\n\n` +
          `You will receive live runner inspection and your \`patched_boot.img\` in 3–5 minutes! ☕`,
        parse_mode: "Markdown",
      });

      // Get direct download link from Telegram
      const fileRes = await tgSend(token, "getFile", { file_id: doc.file_id });
      if (!fileRes.ok || !fileRes.result?.file_path) {
        await tgSend(token, "sendMessage", {
          chat_id: chatId,
          text: "❌ Failed to retrieve file link from Telegram. Please re-upload your `boot.img`.",
        });
        return;
      }

      const fileUrl = `https://api.telegram.org/file/bot${token}/${fileRes.result.file_path}`;

      // Dispatch GitHub Actions workflow
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
          text: "❌ Failed to dispatch GitHub Actions runner. Please verify repository tokens and permissions.",
        });
      }
    }
  }
}

// ── Binary Parser Helper ────────────────────────────────────────────────────

function parseBootBuffer(arrayBuffer, totalSize, defaultInfo) {
  const info = { ...defaultInfo };
  const bytes = new Uint8Array(arrayBuffer);
  const view = new DataView(arrayBuffer);

  // Check Magic
  const magic = String.fromCharCode(...bytes.slice(0, 8));
  if (magic.startsWith("ANDROID!")) {
    const v = bytes[40];
    info.format = v !== undefined && v <= 4 ? `Android Boot v${v}` : "Android Boot Image";
    if (bytes.length >= 48) {
      const osVal = view.getUint32(44, true);
      if (osVal !== 0) {
        const a = (osVal >> 25) & 0x7F;
        const b = (osVal >> 18) & 0x7F;
        const c = (osVal >> 11) & 0x7F;
        info.os_version = `${a}.${b}.${c}`;
        const y = ((osVal >> 4) & 0x7F) + 2000;
        const m = osVal & 0x0F;
        info.os_patch_level = `${y}-${String(m).padStart(2, "0")}`;
      }
    }
  } else if (magic.startsWith("VNDR")) {
    info.format = "Vendor Boot Image";
  }

  // Text string search for Linux kernel banner
  const textDecoder = new TextDecoder("utf-8", { fatal: false, ignoreBOM: true });
  const str = textDecoder.decode(bytes);

  const bannerMatch = str.match(/Linux version ([^\x00\r\n]+)/);
  if (bannerMatch) {
    info.kernel_banner = "Linux version " + bannerMatch[1].trim();
    const verNum = bannerMatch[1].match(/(\d+\.\d+\.\d+[\w.-]*)/);
    if (verNum) {
      info.kernel_short = verNum[1];
    }
  }

  // Architecture check
  if (str.includes("aarch64") || str.includes("ARM64") || str.includes("ARM aarch64")) {
    info.arch = "arm64 (AArch64)";
  } else if (str.includes("ARM") || str.includes("armv7")) {
    info.arch = "arm32 (ARMv7)";
  } else if (str.includes("x86_64")) {
    info.arch = "x86_64";
  }

  // Compression signatures
  for (let i = 0; i < bytes.length - 4; i++) {
    if (bytes[i] === 0x1f && bytes[i + 1] === 0x8b && bytes[i + 2] === 0x08) {
      info.compression = "gzip";
      break;
    }
    if (bytes[i] === 0x02 && bytes[i + 1] === 0x21 && bytes[i + 2] === 0x4c && bytes[i + 3] === 0x18) {
      info.compression = "lz4";
      break;
    }
    if (bytes[i] === 0x28 && bytes[i + 1] === 0xb5 && bytes[i + 2] === 0x2f && bytes[i + 3] === 0xfd) {
      info.compression = "zstd";
      break;
    }
    if (bytes[i] === 0xfd && bytes[i + 1] === 0x37 && bytes[i + 2] === 0x7a && bytes[i + 3] === 0x58) {
      info.compression = "xz";
      break;
    }
  }

  return info;
}

// ── GitHub & Telegram API Helpers ───────────────────────────────────────────

async function fetchFlavourVersions(kRepo, ghToken) {
  const versions = { SukiSU: "6.1.138", "KernelSU-Next": "6.1.138", WKSU: "6.1.138", ReSukiSU: "6.1.138" };
  const headers = {
    "Accept": "application/vnd.github+json",
    "User-Agent": "BootPatcher-Cloudflare-Worker",
  };
  if (ghToken) {
    headers["Authorization"] = `Bearer ${ghToken}`;
  }

  try {
    const res = await fetch(`https://api.github.com/repos/${kRepo}/actions/artifacts?per_page=50`, { headers });
    if (res.ok) {
      const data = await res.json();
      for (const a of (data.artifacts || [])) {
        if (a.expired) continue;
        const name = a.name || "";
        for (const key of Object.keys(versions)) {
          if (name.toLowerCase().includes(key.toLowerCase()) && name.toLowerCase().includes("anykernel3")) {
            const m = name.match(/(\d+\.\d+\.\d+)/);
            let dev = "";
            if (name.toLowerCase().includes("peridot")) {
              dev = "peridot ";
            }
            if (m) {
              versions[key] = dev ? `${dev}${m[1]}` : m[1];
            }
          }
        }
      }
    }
  } catch (e) {
    console.error("Failed querying flavour versions:", e);
  }

  return versions;
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
