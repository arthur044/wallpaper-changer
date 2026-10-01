import pytest

from src.config.settings import Settings
from src.onboarding import steps
from src.os_integration import autostart


@pytest.fixture(autouse=True)
def _linux(monkeypatch):
    monkeypatch.setattr(autostart.sys, "platform", "linux")


def test_installing_or_removing_is_a_clean_error_not_a_name_error():
    for call in (autostart.install_autostart, autostart.uninstall_autostart):
        with pytest.raises(OSError, match="not supported on this platform"):
            call()


def test_nothing_is_installed():
    assert autostart.is_autostart_installed() is False


def test_the_wizard_shows_its_own_message_instead_of_stalling(monkeypatch):
    # Regression (#28 review): the NameError on winreg went past apply_options,
    # which only catches OSError, and the wizard stayed on its last step.
    monkeypatch.setattr(steps, "save_settings", lambda settings: None)

    result = steps.apply_options(Settings(), autostart=True, sync_lock_screen=False)

    assert result.autostart_error is not None and "not supported" in result.autostart_error
