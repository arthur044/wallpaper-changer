import pytest

from src.config.settings import Settings
from src.onboarding import steps
from src.onboarding.state import OnboardingStep

tkinter = pytest.importorskip("tkinter")

from src.onboarding.wizard import _TkWizard  # noqa: E402 - must come after the tkinter guard

_VALID = "0123456789abcdef0123456789abcdef"


@pytest.fixture
def wizard():
    w = _TkWizard(Settings(client_id=_VALID))
    yield w
    if not w._closed:
        try:
            w._root.destroy()
        except tkinter.TclError:
            pass


def test_cancel_marks_the_wizard_closed(wizard):
    wizard._on_cancel()

    assert wizard._closed is True
    assert wizard._completed is False


def test_post_after_cancel_is_dropped_instead_of_raising(wizard):
    # Regression: a worker finishing after the user closed the window called
    # after() on a destroyed root, raising in a daemon thread where nothing
    # would surface it (no console handler, app runs under pythonw).
    called = []
    wizard._on_cancel()

    wizard._post(lambda: called.append(1))

    assert called == []


def test_finish_background_is_a_noop_once_closed(wizard):
    button = wizard._next_button
    wizard._on_cancel()

    # Must not raise even though every widget it would touch is gone.
    wizard._finish_background(button, error="boom")


def test_post_delivers_while_the_window_is_open(wizard):
    called = []

    wizard._post(lambda: called.append(1))
    wizard._root.update()

    assert called == [1]


def test_invalid_client_id_does_not_advance(wizard, monkeypatch):
    saved = []
    monkeypatch.setattr(steps, "save_settings", lambda settings: saved.append(settings))
    wizard._step = OnboardingStep.CLIENT_ID
    wizard._render()
    wizard._client_id_var.set("nope")

    wizard._on_next()

    assert wizard._step is OnboardingStep.CLIENT_ID
    assert saved == []
    assert "inválido" in wizard._status_var.get().lower()


def test_valid_client_id_saves_and_advances(wizard, monkeypatch):
    saved = []
    monkeypatch.setattr(steps, "save_settings", lambda settings: saved.append(settings))
    wizard._step = OnboardingStep.CLIENT_ID
    wizard._render()
    wizard._client_id_var.set(f"  {_VALID}  ")

    wizard._on_next()

    assert wizard._step is OnboardingStep.AUTHENTICATE
    assert wizard._settings.client_id == _VALID
    assert len(saved) == 1


def test_cannot_advance_past_login_without_a_client(wizard):
    wizard._step = OnboardingStep.AUTHENTICATE
    wizard._render()
    wizard._client = None

    wizard._on_next()

    assert wizard._step is OnboardingStep.AUTHENTICATE
    assert wizard._status_var.get() != ""


def test_advances_past_login_once_authenticated(wizard):
    wizard._step = OnboardingStep.AUTHENTICATE
    wizard._render()
    wizard._client = object()

    wizard._on_next()

    assert wizard._step is OnboardingStep.VERIFY


def test_navigation_is_ignored_while_a_step_is_running(wizard):
    wizard._step = OnboardingStep.CLIENT_ID
    wizard._render()
    wizard._busy = True

    wizard._on_next()
    wizard._on_back()

    assert wizard._step is OnboardingStep.CLIENT_ID
