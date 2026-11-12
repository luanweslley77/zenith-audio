#!/usr/bin/env python3
"""
Zenith Audio Engine — Linux Desktop Control Center & Tray Applet
Provides GUI management, volume sync, PipeWire app selector, and telemetry.
"""

import sys
import os
import subprocess
import threading
import time

try:
    import gi
    gi.require_version('Gtk', '3.0')
    from gi.repository import Gtk, GLib, Gdk
except ImportError:
    print("[Error] PyGObject / GTK3 is required. Please install python-gobject.")
    sys.exit(1)


class ZenithControlApp(Gtk.Window):
    def __init__(self):
        super().__init__(title="Zenith Audio Server — Control Center")
        self.set_default_size(480, 520)
        self.set_position(Gtk.WindowPosition.CENTER)
        self.set_resizable(False)
        self._syncing_volume = False
        self._pending_volume = 100
        self._volume_timeout = None

        # Apply Dark Theme CSS
        css_provider = Gtk.CssProvider()
        css = """
        window { background-color: #121212; color: #ffffff; font-family: sans-serif; }
        label { color: #ffffff; }
        .title-label { font-size: 18px; font-weight: bold; color: #00E676; }
        .subtitle-label { font-size: 11px; color: #888888; }
        .card { background-color: #1E1E1E; border-radius: 12px; padding: 14px; margin-bottom: 10px; }
        .card-title { font-size: 12px; font-weight: bold; color: #00E676; margin-bottom: 6px; }
        button { background-color: #2E7D32; color: #ffffff; border-radius: 8px; font-weight: bold; padding: 6px 12px; }
        button:hover { background-color: #00E676; color: #000000; }
        button.danger { background-color: #D32F2F; color: #ffffff; }
        button.danger:hover { background-color: #FF5252; color: #ffffff; }
        """
        css_provider.load_from_data(css.encode())
        Gtk.StyleContext.add_provider_for_screen(
            Gdk.Screen.get_default(),
            css_provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

        main_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14)
        main_box.set_margin_top(16)
        main_box.set_margin_bottom(16)
        main_box.set_margin_start(18)
        main_box.set_margin_end(18)
        self.add(main_box)

        # Header
        header_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        title_vbox = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        title_lbl = Gtk.Label(label="⚡ ZENITH AUDIO ENGINE", xalign=0)
        title_lbl.get_style_context().add_class("title-label")
        sub_lbl = Gtk.Label(label="Ultra-Low-Latency Audio Server (PipeWire 1.6.8)", xalign=0)
        sub_lbl.get_style_context().add_class("subtitle-label")
        title_vbox.pack_start(title_lbl, False, False, 0)
        title_vbox.pack_start(sub_lbl, False, False, 0)
        header_box.pack_start(title_vbox, True, True, 0)

        self.status_badge = Gtk.Label(label="● CHECKING")
        self.status_badge.override_color(Gtk.StateFlags.NORMAL, Gdk.RGBA(0.6, 0.6, 0.6, 1))
        header_box.pack_end(self.status_badge, False, False, 0)
        main_box.pack_start(header_box, False, False, 0)

        # Server Status Card
        srv_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        srv_card.get_style_context().add_class("card")
        srv_title = Gtk.Label(label="SERVER STATUS", xalign=0)
        srv_title.get_style_context().add_class("card-title")
        srv_card.pack_start(srv_title, False, False, 0)

        self.srv_info_lbl = Gtk.Label(label="Port: 59100 | Format: 48kHz Stereo | Bitrate: 320 kbps", xalign=0)
        srv_card.pack_start(self.srv_info_lbl, False, False, 0)

        self.client_info_lbl = Gtk.Label(label="Connected Clients: Scanning...", xalign=0)
        self.client_info_lbl.override_color(Gtk.StateFlags.NORMAL, Gdk.RGBA(0.8, 0.8, 0.8, 1))
        srv_card.pack_start(self.client_info_lbl, False, False, 0)
        main_box.pack_start(srv_card, False, False, 0)

        # Virtual Microphone Card
        mic_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        mic_card.get_style_context().add_class("card")
        mic_title = Gtk.Label(label="🎙️ REVERSE WIRELESS MICROPHONE", xalign=0)
        mic_title.get_style_context().add_class("card-title")
        mic_card.pack_start(mic_title, False, False, 0)

        self.mic_status_lbl = Gtk.Label(
            label="Device: 'Zenith Wireless Microphone'\nStatus: Active in PipeWire Graph (Discord, Zoom ready)",
            xalign=0
        )
        mic_card.pack_start(self.mic_status_lbl, False, False, 0)
        main_box.pack_start(mic_card, False, False, 0)

        # PipeWire App Selector Card
        app_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        app_card.get_style_context().add_class("card")
        app_title = Gtk.Label(label="🔀 AUDIO SOURCE ROUTING", xalign=0)
        app_title.get_style_context().add_class("card-title")
        app_card.pack_start(app_title, False, False, 0)

        self.source_combo = Gtk.ComboBoxText()
        self.source_combo.append_text("Entire Linux System (Default Mixed Output)")
        self.source_combo.set_active(0)
        app_card.pack_start(self.source_combo, False, False, 0)
        main_box.pack_start(app_card, False, False, 0)

        # Master Volume Card
        vol_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        vol_card.get_style_context().add_class("card")
        vol_title = Gtk.Label(label="🎚️ MASTER VOLUME SYNC", xalign=0)
        vol_title.get_style_context().add_class("card-title")
        vol_card.pack_start(vol_title, False, False, 0)

        vol_hbox = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        self.vol_slider = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 100, 1)
        self.vol_slider.set_value(100)
        self.vol_slider.connect("value-changed", self.on_volume_changed)
        vol_hbox.pack_start(self.vol_slider, True, True, 0)

        self.vol_pct_lbl = Gtk.Label(label="100%")
        self.vol_pct_lbl.override_color(Gtk.StateFlags.NORMAL, Gdk.RGBA(0, 0.9, 0.46, 1))
        vol_hbox.pack_end(self.vol_pct_lbl, False, False, 0)
        vol_card.pack_start(vol_hbox, False, False, 0)
        main_box.pack_start(vol_card, False, False, 0)

        # Action Buttons
        btn_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        self.btn_restart = Gtk.Button(label="Restart Daemon")
        self.btn_restart.connect("clicked", self.on_restart_daemon)
        btn_box.pack_start(self.btn_restart, True, True, 0)

        self.btn_usb = Gtk.Button(label="⚡ 5ms USB Mode")
        self.btn_usb.connect("clicked", self.on_usb_mode)
        btn_box.pack_start(self.btn_usb, True, True, 0)

        main_box.pack_start(btn_box, False, False, 0)

        # Status Update Thread
        self.running = True
        self.poll_thread = threading.Thread(target=self.poll_status, daemon=True)
        self.poll_thread.start()

        self.connect("destroy", self.on_close)

    def on_volume_changed(self, scale):
        if self._syncing_volume:
            return
        vol = int(scale.get_value())
        self.vol_pct_lbl.set_text(f"{vol}%")
        self._pending_volume = vol
        if self._volume_timeout is None:
            self._volume_timeout = GLib.timeout_add(150, self._apply_volume)

    def _apply_volume(self):
        self._volume_timeout = None
        factor = self._pending_volume / 100.0
        subprocess.run(
            ["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", f"{factor:.2f}"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        return False

    def on_restart_daemon(self, btn):
        subprocess.Popen(["systemctl", "--user", "restart", "zenith-server.service"])

    def _usb_script_path(self):
        candidates = [
            os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts", "zenith-usb-mode.sh"),
            os.path.expanduser("~/.local/bin/zenith-usb-mode.sh"),
            "/usr/local/bin/zenith-usb-mode.sh",
        ]
        for path in candidates:
            if os.path.isfile(path):
                return path
        return None

    def on_usb_mode(self, btn):
        script_path = self._usb_script_path()
        if script_path is None:
            dialog = Gtk.MessageDialog(
                transient_for=self,
                flags=0,
                message_type=Gtk.MessageType.WARNING,
                buttons=Gtk.ButtonsType.OK,
                text="zenith-usb-mode.sh not found",
            )
            dialog.format_secondary_text(
                "Re-run install.sh or launch the tray from the repository checkout."
            )
            dialog.run()
            dialog.destroy()
            return
        subprocess.Popen(["bash", script_path])

    def poll_status(self):
        while self.running:
            # Check if zenith-server is active
            res = subprocess.run("pgrep -x zenith-server", shell=True, capture_output=True, text=True)
            is_active = (res.returncode == 0)

            # Check PipeWire mic
            pw_res = subprocess.run("wpctl status 2>/dev/null | grep -i 'Zenith Wireless Microphone'", shell=True, capture_output=True, text=True)
            has_mic = bool(pw_res.stdout.strip())

            # Check volume
            v_res = subprocess.run("wpctl get-volume @DEFAULT_AUDIO_SINK@ 2>/dev/null", shell=True, capture_output=True, text=True)
            vol_val = 100
            if v_res.returncode == 0 and "Volume:" in v_res.stdout:
                try:
                    raw_v = float(v_res.stdout.split("Volume:")[1].split()[0])
                    vol_val = int(raw_v * 100)
                except Exception:
                    pass

            GLib.idle_add(self.update_ui, is_active, has_mic, vol_val)
            time.sleep(2)

    def update_ui(self, is_active, has_mic, vol_val):
        if is_active:
            self.status_badge.set_text("● RUNNING")
            self.status_badge.override_color(Gtk.StateFlags.NORMAL, Gdk.RGBA(0, 0.9, 0.46, 1))
            self.client_info_lbl.set_text("Server running — waiting for a client")
        else:
            self.status_badge.set_text("● STOPPED")
            self.status_badge.override_color(Gtk.StateFlags.NORMAL, Gdk.RGBA(1, 0.3, 0.3, 1))
            self.client_info_lbl.set_text("Server not running. Tap 'Restart Daemon'")

        if has_mic:
            self.mic_status_lbl.set_text("Device: 'Zenith Wireless Microphone'\nStatus: Online & Ready for Apps (Discord/Zoom)")
        else:
            self.mic_status_lbl.set_text("Device: 'Zenith Wireless Microphone'\nStatus: Registering with PipeWire...")

        # Reflect the sink volume without feeding it back as a wpctl command.
        if not self.vol_slider.has_focus():
            self._syncing_volume = True
            try:
                self.vol_slider.set_value(float(vol_val))
                self.vol_pct_lbl.set_text(f"{int(vol_val)}%")
            finally:
                self._syncing_volume = False

        return False

    def on_close(self, widget):
        self.running = False
        Gtk.main_quit()


def main():
    app = ZenithControlApp()
    app.show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
