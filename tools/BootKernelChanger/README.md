# BootKernelChanger (Windows Edition)

A lightweight Windows GUI tool to replace the kernel inside an Android `boot.img`.

> **Original Concept:** [Dayto0/BootKernelChanger](https://github.com/Dayto0/BootKernelChanger)  
> **This Release:** English UI + Auto Dark Mode (Windows 10/11)

---

## 🚀 Pre-built Executable

The pre-compiled, standalone executable is included right in this directory:
👉 **[`BootKernelChanger_EN.exe`](BootKernelChanger_EN.exe)**

It has `magiskboot.exe` bundled inside. Simply double-click to run!

---

## 📋 Features

- **Auto Dark/Light Theme:** Detects your Windows system theme automatically and matches it using Sun Valley (`sv-ttk`).
- **Full English Localization:** Clean, easy-to-use interface.
- **Embedded Engine:** Bundled with `magiskboot.exe` for unpacking and repacking boot images without console commands.
- **Live Output Log:** View unpacking, kernel swapping, and repacking progress in real time.

---

## 🛠 Running from Source

If you prefer to run Python source code directly:

```bash
pip install -r requirements.txt # sv-ttk darkdetect
python app.py
```

### Compiling to single EXE
```powershell
pip install sv-ttk darkdetect pyinstaller
pyinstaller --noconsole --onefile --icon=icon.ico --add-data "magiskboot.exe;." -n BootKernelChanger_EN app.py
```
