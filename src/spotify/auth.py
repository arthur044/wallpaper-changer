import json
import logging
from typing import Optional

import keyring
import keyring.errors
from spotipy.cache_handler import CacheHandler

logger = logging.getLogger(__name__)

_SERVICE_NAME = "SpotifyWallpaperEngine"
_USERNAME = "default"


class KeyringCacheHandler(CacheHandler):
    """Persists the spotipy token info in the Windows Credential Manager instead of a plaintext file."""

    def get_cached_token(self) -> Optional[dict]:
        try:
            raw = keyring.get_password(_SERVICE_NAME, _USERNAME)
        except keyring.errors.KeyringError as exc:
            logger.error("Keyring read failed: %s", exc)
            return None
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            logger.error("Corrupt token cache in keyring, discarding")
            self.clear_cached_token()
            return None

    def save_token_to_cache(self, token_info: dict) -> None:
        try:
            keyring.set_password(_SERVICE_NAME, _USERNAME, json.dumps(token_info))
        except keyring.errors.KeyringError as exc:
            logger.error("Keyring write failed, token will not persist across restarts: %s", exc)

    def clear_cached_token(self) -> None:
        try:
            keyring.delete_password(_SERVICE_NAME, _USERNAME)
        except keyring.errors.PasswordDeleteError:
            pass
        except keyring.errors.KeyringError as exc:
            logger.error("Keyring delete failed: %s", exc)


def build_auth_manager(client_id: str, redirect_uri: str, scope: str):
    from spotipy.oauth2 import SpotifyPKCE

    return SpotifyPKCE(
        client_id=client_id,
        redirect_uri=redirect_uri,
        scope=scope,
        cache_handler=KeyringCacheHandler(),
        open_browser=True,
    )


def reauthenticate(client_id: str, redirect_uri: str, scope: str):
    """Clears any cached token and forces a fresh interactive login on the next API call."""
    KeyringCacheHandler().clear_cached_token()
    return build_auth_manager(client_id, redirect_uri, scope)
