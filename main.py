import argparse
import logging
import sys
import threading

from src.config.paths import album_base_path
from src.config.settings import load_settings
from src.graphics.renderer import render_for_now_playing
from src.os_integration import lockscreen
from src.os_integration.autostart import install_autostart, uninstall_autostart
from src.os_integration.session_lock import is_workstation_locked
from src.os_integration.smtc import SmtcWatcher
from src.os_integration.tray import TrayApp
from src.os_integration.wallpaper import next_output_path, set_wallpaper
from src.spotify.auth import build_auth_manager, reauthenticate
from src.spotify.client import NowPlaying
from src.spotify.poller import Poller
from src.utils.app_state import AppState
from src.utils.logger import setup_logging

logger = logging.getLogger(__name__)


def _build_client(settings):
    from spotipy import Spotify

    auth_manager = build_auth_manager(settings.client_id, settings.redirect_uri, settings.scope)
    return Spotify(auth_manager=auth_manager)


def _make_render_fn(settings):
    def render(now_playing: NowPlaying) -> None:
        if not now_playing.art_url or not now_playing.album_id:
            logger.warning("No album art URL for track %s, skipping render", now_playing.track_id)
            return

        base_path = album_base_path(now_playing.album_id)
        output_path = next_output_path()
        render_for_now_playing(now_playing, settings, base_path, output_path)
        set_wallpaper(output_path)
        if settings.sync_lock_screen:
            lockscreen.request_update(output_path)

    return render


def main() -> int:
    parser = argparse.ArgumentParser(description="Spotify Dynamic Wallpaper Engine")
    parser.add_argument("--install-autostart", action="store_true")
    parser.add_argument("--uninstall-autostart", action="store_true")
    parser.add_argument("--apply-lockscreen", action="store_true")
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

    if not settings.client_id:
        print(
            "No Spotify client_id configured. Edit config.json "
            "(%APPDATA%\\SpotifyWallpaperEngine\\config.json) with your Spotify app's "
            "Client ID, then run again."
        )
        return 1

    app_state = AppState()

    smtc_watcher = None
    if settings.use_smtc:
        smtc_watcher = SmtcWatcher()
        smtc_watcher.start()

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
        render_fn=_make_render_fn(settings),
        reauth_fn=reauth,
        smtc_watcher=smtc_watcher,
        is_locked_fn=is_workstation_locked,
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

    def on_exit() -> None:
        logger.info("Exiting Spotify Wallpaper Engine")
        if smtc_watcher is not None:
            smtc_watcher.stop()

    tray = TrayApp(app_state, settings, on_reauthenticate=on_reauthenticate, on_exit=on_exit)
    tray.run()  # blocks until Exit is clicked

    return 0


if __name__ == "__main__":
    sys.exit(main())
