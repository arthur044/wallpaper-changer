import pytest

from src.config.settings import Settings

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
