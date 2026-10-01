import argparse
import dataclasses
import logging
import sys
import threading
from typing import Optional

from src.config.paths import album_art_dir, album_base_path, track_index_file
from src.config.settings import load_settings, save_settings
from src.graphics.art_cache import AlbumArtCache
from src.graphics.base_cache import base_cache_key
from src.graphics.layout import compute_layout
from src.graphics.renderer import render_for_now_playing
from src.lyrics_widget.colors import TintHolder
from src.lyrics_widget.qt_host import QtHost
from src.settings_window.style_actions import StyleActions
from src.settings_window.window import SettingsWindow
from src.os_integration import lockscreen
from src.os_integration import app_version
from src.os_integration import restart
from src.os_integration import single_instance
from src.os_integration.autostart import install_autostart, uninstall_autostart
from src.os_integration.desktop import MediaWatcher, next_output_path, set_wallpaper, tray_icon_factory
from src.onboarding.state import needs_onboarding, should_abort_after_wizard
from src.onboarding.wizard import run_wizard
from src.os_integration.session_lock import is_workstation_locked
from src.os_integration.tray import TrayApp
from src.os_integration.updater import Updater
from src.spotify.auth import build_auth_manager, reauthenticate
from src.spotify.client import NowPlaying
from src.spotify.poller import Poller
from src.spotify.track_index import TrackAlbumStore
from src.utils.app_state import AppState
from src.utils.logger import setup_logging

logger = logging.getLogger(__name__)


def _build_client(settings):
    from spotipy import Spotify

    auth_manager = build_auth_manager(settings.client_id, settings.redirect_uri, settings.scope)
    return Spotify(auth_manager=auth_manager)


def _make_render_fn(settings, tint: Optional[TintHolder] = None, art_cache: Optional[AlbumArtCache] = None):
    def render(now_playing: NowPlaying) -> None:
        if not now_playing.art_url or not now_playing.album_id:
            logger.warning("No album art URL for track %s, skipping render", now_playing.track_id)
            return

        # The tray edits [settings] from its own thread. A copy keeps the cache
        # key and the drawing on the same style when it changes mid-render.
        snapshot = dataclasses.replace(settings)
        layout = compute_layout(snapshot)
        base_path = album_base_path(base_cache_key(now_playing.album_id, layout.canvas_size, snapshot))
        output_path = next_output_path()
        album_color = render_for_now_playing(now_playing, snapshot, layout, base_path, output_path, art_cache)
        if tint is not None:
            tint.set(album_color)  # the lyrics widget's background follows the album
        set_wallpaper(output_path, smooth=snapshot.smooth_transition)
        if snapshot.sync_lock_screen:
            lockscreen.request_update(output_path)

    return render


def main() -> int:
    parser = argparse.ArgumentParser(description="Spotify Dynamic Wallpaper Engine")
    parser.add_argument("--install-autostart", action="store_true")
    parser.add_argument("--uninstall-autostart", action="store_true")
    parser.add_argument("--apply-lockscreen", action="store_true")
    parser.add_argument("--setup", action="store_true", help="Re-run the guided setup wizard")
    args = parser.parse_args()

    settings = load_settings()
    setup_logging(settings.log_level)

    if args.apply_lockscreen:
        lockscreen.apply_pending()
        return 0

    if args.install_autostart:
        install_autostart()
        print("Autostart installed.")
        return 0
    if args.uninstall_autostart:
        uninstall_autostart()
        print("Autostart removed.")
        return 0

    if args.setup or needs_onboarding(settings.client_id):
        # First run (or an explicit --setup) walks the user through registering
        # a Spotify app, the redirect URI, the client id, the OAuth login, a
        # connection check, and the optional autostart/lock screen toggles.
        completed = run_wizard(settings)
        if should_abort_after_wizard(completed, settings.client_id):
            logger.info("Setup cancelled and no client_id configured; nothing to run")
            return 1

    # After the wizard, so --setup still works while the app is open. The wait
    # covers Restart, whose new instance starts before the old one exits.
    instance_lock = single_instance.acquire()
    if instance_lock is None:
        logger.info("Another instance is already running; exiting")
        return 0
    try:
        return _run_app(settings)
    finally:
        instance_lock.release()  # same (main) thread that took it


def _run_app(settings) -> int:
    # Before the poller starts: its first render sets the DPI awareness, and Qt
    # has to set it first (see QtHost). After the wizard: its Tk root is gone.
    album_tint = TintHolder()
    qt_host = QtHost(settings, save=save_settings, tint=album_tint)

    app_state = AppState()

    smtc_watcher = None
    if settings.use_smtc:  # "the local source": SMTC on Windows, MPRIS on Linux
        smtc_watcher = MediaWatcher()
        smtc_watcher.start()
    qt_host.attach_smtc(smtc_watcher)
    style = StyleActions(settings, save_settings, app_state.force_sync_event.set)
    # Built on first open, on the Qt thread; the tray exists by then.
    qt_host.attach_settings_window(lambda: SettingsWindow(settings, style, qt_host, tray.commands()))

    try:
        client = _build_client(settings)
    except Exception as exc:  # noqa: BLE001 - a clean message beats a raw traceback on first run
        logger.exception("Failed to authenticate with Spotify: %s", exc)
        print(f"Failed to authenticate with Spotify: {exc}")
        if smtc_watcher is not None:
            smtc_watcher.stop()
        return 1

    def reauth():
        from spotipy import Spotify

        auth_manager = reauthenticate(settings.client_id, settings.redirect_uri, settings.scope)
        return Spotify(auth_manager=auth_manager)

    poller = Poller(
        client=client,
        settings=settings,
        app_state=app_state,
        render_fn=_make_render_fn(settings, album_tint, AlbumArtCache(album_art_dir())),
        reauth_fn=reauth,
        smtc_watcher=smtc_watcher,
        is_locked_fn=is_workstation_locked,
        track_index=TrackAlbumStore(track_index_file()),
    )

    if smtc_watcher is not None:
        # Bounded wait so the poller's very first cycle already sees a live
        # SMTC snapshot instead of racing the watcher thread's startup and
        # briefly falling back to an unresolved low-res render.
        smtc_watcher.wait_ready(timeout=3.0)

    poll_thread = threading.Thread(target=poller.run_forever, daemon=True, name="poller")
    poll_thread.start()

    def on_reauthenticate() -> None:
        try:
            poller.set_client(reauth())
            app_state.clear_error()
        except Exception as exc:  # noqa: BLE001 - manual re-auth failures must not crash the tray
            logger.exception("Manual re-authentication failed: %s", exc)

    def on_setup() -> None:
        try:
            if run_wizard(settings):
                poller.set_client(_build_client(settings))
                app_state.clear_error()
        except Exception as exc:  # noqa: BLE001 - the wizard must never crash the tray
            logger.exception("Setup wizard failed: %s", exc)
            # Surface it on the tray icon too, the way auth expiry already is:
            # a silent failure here leaves the poller on a stale client and the
            # wallpaper just quietly stops updating.
            app_state.set_error(f"Setup failed: {exc}")

    def on_exit() -> None:
        logger.info("Exiting Spotify Wallpaper Engine")
        if smtc_watcher is not None:
            smtc_watcher.stop()

    def on_restart() -> None:
        logger.info("Restarting Spotify Wallpaper Engine")
        # Stop this instance's poller first (letting a render in progress
        # finish), so the two instances never write the wallpaper at once.
        app_state.stop_event.set()
        app_state.force_sync_event.set()
        poll_thread.join(timeout=15.0)
        on_exit()
        restart.relaunch()

    tray = TrayApp(
        app_state,
        settings,
        on_reauthenticate=on_reauthenticate,
        on_exit=on_exit,
        on_setup=on_setup,
        on_restart=on_restart,
        version=app_version.version_label(app_version.read_version(app_version.app_dir())),
        updater=Updater(app_version.app_dir()),
        on_open_settings=qt_host.open_settings,
        icon_factory=tray_icon_factory,
    )
    tray_thread = qt_host.run_tray_in_thread(tray.run)
    qt_host.exec()  # blocks until Exit or Restart ends the tray loop
    if tray_thread.is_alive():  # Qt ended on its own: take the tray icon down too
        tray.stop()
    tray_thread.join(timeout=5.0)

    return 0


if __name__ == "__main__":
    sys.exit(main())
