"""
BootKernelChanger — English Edition
=====================================
Replace the kernel inside a boot.img with a custom/patched kernel.
Uses magiskboot to unpack and repack.

Original concept by: Dayto0 (https://github.com/Dayto0/BootKernelChanger)
English version with dark mode by: nothingnesscore

Requires: magiskboot.exe in the same directory (or bundled via PyInstaller)
"""

import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import threading
import subprocess
import tempfile
import shutil
import os
from pathlib import Path
import queue
import sys
import sv_ttk
import darkdetect


class ToolTip:
    def __init__(self, widget, text=""):
        self.widget = widget
        self.text = text
        self.tipwindow = None
        widget.bind("<Enter>", self.show)
        widget.bind("<Leave>", self.hide)

    def show(self, _event=None):
        if self.tipwindow or not self.text:
            return
        x = self.widget.winfo_rootx() + 20
        y = self.widget.winfo_rooty() + 20
        self.tipwindow = tw = tk.Toplevel(self.widget)
        tw.wm_overrideredirect(True)
        tw.wm_geometry(f'+{x}+{y}')
        label = ttk.Label(tw, text=self.text, padding=(6, 4))
        label.pack()

    def hide(self, _event=None):
        if self.tipwindow:
            self.tipwindow.destroy()
            self.tipwindow = None


class BootAssemblerApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Boot.img Assembler by Dayto")
        self.geometry("720x460")
        self.minsize(640, 420)
        self.iconbitmap(default='')
        style = ttk.Style(self)

        # Auto dark/light mode based on system setting
        is_dark = darkdetect.isDark()
        sv_ttk.set_theme("dark" if is_dark else "light")

        style.configure('Header.TLabel', font=('Segoe UI', 16, 'bold'))
        style.configure('Sub.TLabel', font=('Segoe UI', 10))
        style.configure('TButton', font=('Segoe UI', 10, 'bold'), padding=8)

        path_fg = '#cccccc' if is_dark else '#333333'
        style.configure('Path.TLabel', font=('Segoe UI', 10), foreground=path_fg)

        self.selected_kernel = None
        self.selected_boot = None

        if getattr(sys, 'frozen', False):
            base_path = Path(getattr(sys, '_MEIPASS', Path(sys.executable).parent))
            self.magiskboot = base_path / 'magiskboot.exe'
        else:
            self.magiskboot = Path(__file__).with_name('magiskboot.exe')

        self.log_queue = queue.Queue()

        self._create_widgets(is_dark)
        self._start_log_pump()

    def _create_widgets(self, is_dark: bool):
        header = ttk.Frame(self, padding=12)
        header.pack(fill=tk.X)
        ttk.Label(header, text="Boot.img Assembler", style='Header.TLabel').pack(anchor=tk.W)
        ttk.Label(
            header,
            text="Replace kernel in boot.img using magiskboot  •  Original idea by Dayto0",
            style='Sub.TLabel',
        ).pack(anchor=tk.W, pady=(2, 6))

        main = ttk.Frame(self, padding=10)
        main.pack(fill=tk.BOTH, expand=True)

        left = ttk.Frame(main)
        left.pack(side=tk.LEFT, fill=tk.BOTH, expand=False)

        # Kernel section
        kernel_frame = ttk.LabelFrame(left, text='Kernel (Image)', padding=8)
        kernel_frame.pack(fill=tk.X, padx=(0, 10), pady=6)
        self.kernel_path_label = ttk.Label(kernel_frame, text='Not selected', style='Path.TLabel')
        self.kernel_path_label.pack(fill=tk.X)
        ToolTip(self.kernel_path_label, text='Path to the selected kernel')

        kbtns = ttk.Frame(kernel_frame)
        kbtns.pack(fill=tk.X, pady=(8, 0))
        ttk.Button(kbtns, text='Select kernel', command=self.select_kernel).pack(side=tk.LEFT)
        ttk.Button(kbtns, text='Clear', command=self.clear_kernel).pack(side=tk.LEFT, padx=6)

        # Boot image section
        boot_frame = ttk.LabelFrame(left, text='Boot image (boot.img)', padding=8)
        boot_frame.pack(fill=tk.X, padx=(0, 10), pady=6)
        self.boot_path_label = ttk.Label(boot_frame, text='Not selected', style='Path.TLabel')
        self.boot_path_label.pack(fill=tk.X)
        ToolTip(self.boot_path_label, text='Path to the selected boot.img')

        bbtns = ttk.Frame(boot_frame)
        bbtns.pack(fill=tk.X, pady=(8, 0))
        ttk.Button(bbtns, text='Select boot.img', command=self.select_boot).pack(side=tk.LEFT)
        ttk.Button(bbtns, text='Clear', command=self.clear_boot).pack(side=tk.LEFT, padx=6)

        # Build button
        build_frame = ttk.Frame(left, padding=8)
        build_frame.pack(fill=tk.X, padx=(0, 10), pady=12)
        self.big_assemble_btn = ttk.Button(build_frame, text='Build', command=self.on_assemble)
        self.big_assemble_btn.pack(fill=tk.X, pady=6, ipady=8)

        # Log section
        right = ttk.Frame(main)
        right.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        log_frame = ttk.LabelFrame(right, text='Operation Log', padding=8)
        log_frame.pack(fill=tk.BOTH, expand=True, padx=(0, 0))

        bg_color = "#1c1c1c" if is_dark else "#ffffff"
        fg_color = "#ffffff" if is_dark else "#000000"
        self.log_text = tk.Text(
            log_frame, height=12, wrap='none', state='disabled',
            bg=bg_color, fg=fg_color, insertbackground=fg_color,
        )
        self.log_text.pack(fill=tk.BOTH, expand=True)

        # Status bar
        bottom = ttk.Frame(self, relief=tk.FLAT, padding=(8, 6))
        bottom.pack(fill=tk.X)
        self.progress = ttk.Progressbar(bottom, mode='indeterminate')
        self.progress.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 8))
        self.status_label = ttk.Label(bottom, text='Ready', anchor='e')
        self.status_label.pack(side=tk.RIGHT)

    def select_kernel(self):
        path = filedialog.askopenfilename(
            title='Select kernel (Image)', filetypes=[('All files', '*.*')]
        )
        if path:
            self.selected_kernel = Path(path)
            self.kernel_path_label.config(text=self.selected_kernel.name)
            ToolTip(self.kernel_path_label, text=str(self.selected_kernel))

    def select_boot(self):
        path = filedialog.askopenfilename(
            title='Select boot.img',
            filetypes=[('boot images', '*.img'), ('All files', '*.*')],
        )
        if path:
            self.selected_boot = Path(path)
            self.boot_path_label.config(text=self.selected_boot.name)
            ToolTip(self.boot_path_label, text=str(self.selected_boot))

    def clear_kernel(self):
        self.selected_kernel = None
        self.kernel_path_label.config(text='Not selected')

    def clear_boot(self):
        self.selected_boot = None
        self.boot_path_label.config(text='Not selected')

    def on_assemble(self):
        if not self.selected_kernel or not self.selected_boot:
            messagebox.showerror('Error', 'Please select both files: kernel (Image) and boot.img')
            return
        if not self.magiskboot.exists():
            messagebox.showerror('Error', f'magiskboot.exe not found: {self.magiskboot}')
            return

        self.big_assemble_btn.config(state='disabled')
        self.progress.start(10)
        self.status_label.config(text='Working...')
        threading.Thread(target=self._assemble_worker, daemon=True).start()

    def _assemble_worker(self):
        temp_dir = Path(tempfile.mkdtemp(prefix='boot_'))
        try:
            self._log(f'Created temporary folder: {temp_dir}')

            boot_dest   = temp_dir / 'boot.img'
            kernel_dest = temp_dir / 'Image'
            shutil.copy(self.selected_boot,   boot_dest)
            self._log(f'Copied boot.img -> {boot_dest.name}')
            shutil.copy(self.selected_kernel, kernel_dest)
            self._log(f'Copied kernel -> {kernel_dest.name}')

            cmd_unpack = [str(self.magiskboot), 'unpack', 'boot.img']
            self._log('Executing: ' + ' '.join(cmd_unpack))
            result = subprocess.run(cmd_unpack, cwd=str(temp_dir), capture_output=True, text=True)
            self._log(result.stdout or result.stderr)
            if result.returncode != 0:
                raise subprocess.CalledProcessError(
                    result.returncode, cmd_unpack,
                    output=result.stdout, stderr=result.stderr,
                )

            kernel_file = temp_dir / 'kernel'
            image_file  = temp_dir / 'Image'
            if kernel_file.exists():
                kernel_file.unlink()
                self._log('Old kernel removed')
            if image_file.exists():
                image_file.rename(kernel_file)
                self._log('Image renamed to kernel')

            cmd_repack = [str(self.magiskboot), 'repack', 'boot.img']
            self._log('Executing: ' + ' '.join(cmd_repack))
            result = subprocess.run(cmd_repack, cwd=str(temp_dir), capture_output=True, text=True)
            self._log(result.stdout or result.stderr)
            if result.returncode != 0:
                raise subprocess.CalledProcessError(
                    result.returncode, cmd_repack,
                    output=result.stdout, stderr=result.stderr,
                )

            new_boot = temp_dir / 'new-boot.img'
            if new_boot.exists():
                self._log(f'new-boot.img created: {new_boot}')
                self.after(0, lambda: self._handle_new_boot_created(str(new_boot), str(temp_dir)))
            else:
                raise FileNotFoundError('new-boot.img not found after repack')

        except Exception as e:
            self._log(f'Error: {e}')
            try:
                shutil.rmtree(str(temp_dir))
                self._log('Temporary folder deleted (error).')
            except Exception:
                pass
            self.after(0, lambda: self._show_error('Error', str(e)))
            self.after(0, self._finish)

    def _handle_new_boot_created(self, new_boot_path: str, temp_dir: str):
        try:
            save_path = filedialog.asksaveasfilename(
                title='Save new boot.img',
                defaultextension='.img',
                filetypes=[('Image', '*.img')],
            )
            if save_path:
                shutil.copy(new_boot_path, save_path)
                self._log(f'New boot.img saved: {save_path}')
                self._show_info('Success', f'New boot.img saved: {save_path}')
            else:
                self._log(f'User canceled saving — file: {new_boot_path}')
                self._show_info('Ready', f'New boot.img created: {new_boot_path}')
        except Exception as e:
            self._log(f'Error saving: {e}')
            self._show_error('Save Error', str(e))
        finally:
            try:
                shutil.rmtree(temp_dir)
                self._log('Temporary folder deleted')
            except Exception as ex:
                self._log(f'Failed to delete temporary folder: {ex}')
        self._finish()

    def _finish(self):
        self.progress.stop()
        self.big_assemble_btn.config(state='normal')
        self.status_label.config(text='Ready')

    def _log(self, text):
        self.log_queue.put(str(text) + '\n')

    def _start_log_pump(self):
        def pump():
            try:
                while True:
                    line = self.log_queue.get_nowait()
                    self.log_text.config(state='normal')
                    self.log_text.insert('end', line)
                    self.log_text.see('end')
                    self.log_text.config(state='disabled')
            except queue.Empty:
                pass
            self.after(200, pump)
        pump()

    def _show_error(self, title, message):
        self.after(0, lambda: messagebox.showerror(title, message))

    def _show_info(self, title, message):
        self.after(0, lambda: messagebox.showinfo(title, message))


if __name__ == '__main__':
    app = BootAssemblerApp()
    app.mainloop()
