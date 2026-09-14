# BootKernelChanger (Windows)

A lightweight Windows GUI tool to replace the kernel inside a `boot.img`.

> Original concept by [Dayto0](https://github.com/Dayto0/BootKernelChanger)  
> This English + dark mode version is published here as part of BootPatcher

## Requirements

- Windows 10/11
- `magiskboot.exe` (place in the same folder as the script or exe)
- Python 3.10+ with `sv-ttk` and `darkdetect` (for running from source)

## Usage

1. Run `BootKernelChanger_EN.exe` (or `python app.py`)
2. Select your `boot.img`
3. Select your replacement kernel (`Image` file)
4. Click **Build**
5. Save the new `boot.img`

## Building from source

```bash
pip install sv-ttk darkdetect pyinstaller
pyinstaller --noconsole --onefile --icon=icon.ico --add-data "magiskboot.exe;." -n BootKernelChanger_EN app.py
```

The exe will appear in the `dist/` folder.
