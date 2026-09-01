from src.os_integration.session_lock import _desktop_name_indicates_locked


def test_default_desktop_is_not_locked():
    assert _desktop_name_indicates_locked("Default") is False


def test_winlogon_desktop_is_locked():
    assert _desktop_name_indicates_locked("Winlogon") is True


def test_unreadable_desktop_name_is_treated_as_locked():
    assert _desktop_name_indicates_locked(None) is True
