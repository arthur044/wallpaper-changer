from src.os_integration.update_menu import UpdateMenu
from src.os_integration.updater import Action, ApplyResult, Decision
from src.utils.app_state import AppState, AppStatus


class _FakeUpdater:
    def __init__(self, check=None, apply=None):
        self.next_check = check or Decision(Action.UP_TO_DATE, target="aaaaaaa")
        self.next_apply = apply
        self.checks = 0
        self.applies = 0

    def check(self):
        self.checks += 1
        return self.next_check

    def apply(self):
        self.applies += 1
        return self.next_apply


def _menu(updater, app_state=None):
    events = []
    menu = UpdateMenu(
        updater,
        app_state or AppState(),
        on_restart=lambda: events.append("restart"),
        refresh=lambda: events.append("refresh"),
        spawn=lambda fn: fn(),  # run inline: the tests don't need the thread
    )
    return menu, events


_UPDATE = Decision(Action.UPDATE, target="bbbbbbb", commits=3)
_DOCS = Decision(Action.DOCS_ONLY, target="ccccccc", commits=1)


def test_starts_as_check_for_updates():
    menu, _ = _menu(_FakeUpdater())

    assert menu.label() == "Check for updates"


def test_click_when_up_to_date_says_so():
    menu, events = _menu(_FakeUpdater())

    menu.click()

    assert menu.label() == "Up to date (aaaaaaa)"
    assert "refresh" in events and "restart" not in events


def test_click_that_finds_an_update_offers_it_without_applying():
    updater = _FakeUpdater(check=_UPDATE)
    menu, events = _menu(updater)

    menu.click()

    assert menu.label() == "Update to bbbbbbb (3 commits)"
    assert updater.applies == 0 and "restart" not in events


def test_one_commit_is_singular():
    menu, _ = _menu(_FakeUpdater(check=Decision(Action.UPDATE, target="bbbbbbb", commits=1)))

    menu.click()

    assert menu.label() == "Update to bbbbbbb (1 commit)"


def test_second_click_applies_and_restarts():
    updater = _FakeUpdater(check=_UPDATE, apply=ApplyResult(_UPDATE, restart=True))
    menu, events = _menu(updater)

    menu.click()
    menu.click()

    assert updater.applies == 1
    assert events[-1] == "restart"


def test_docs_only_is_pulled_in_on_click_without_a_restart():
    updater = _FakeUpdater(check=_DOCS, apply=ApplyResult(_DOCS))
    menu, events = _menu(updater)

    menu.click()

    assert updater.applies == 1
    assert menu.label() == "Up to date (ccccccc), no desktop changes"
    assert "restart" not in events


def test_blocked_check_shows_the_error_on_the_tray():
    app_state = AppState()
    menu, _ = _menu(_FakeUpdater(check=Decision(Action.BLOCKED, reason="the app folder has local changes")), app_state)

    menu.click()

    assert menu.label() == "Update failed: the app folder has local changes"
    snapshot = app_state.snapshot()
    assert snapshot.status == AppStatus.ERROR
    assert snapshot.last_error == "Update failed: the app folder has local changes"


def test_failed_apply_shows_the_error_and_does_not_restart():
    updater = _FakeUpdater(check=_UPDATE, apply=ApplyResult(_UPDATE, error="pip install failed; staying on the old version"))
    app_state = AppState()
    menu, events = _menu(updater, app_state)

    menu.click()
    menu.click()

    assert "restart" not in events
    assert app_state.snapshot().status == AppStatus.ERROR
    assert menu.label() == "Update failed: pip install failed; staying on the old version"


def test_after_a_failure_the_next_click_checks_again():
    updater = _FakeUpdater(check=Decision(Action.BLOCKED, reason="git fetch failed"))
    menu, _ = _menu(updater)
    menu.click()

    updater.next_check = Decision(Action.UP_TO_DATE, target="aaaaaaa")
    menu.click()

    assert updater.checks == 2 and menu.label() == "Up to date (aaaaaaa)"


def test_startup_check_only_changes_the_label():
    updater = _FakeUpdater(check=_UPDATE)
    menu, events = _menu(updater)

    menu.check_silently()

    assert menu.label() == "Update to bbbbbbb (3 commits)"
    assert updater.applies == 0 and "restart" not in events


def test_startup_check_never_pulls_docs_or_raises_errors():
    app_state = AppState()
    updater = _FakeUpdater(check=_DOCS)
    menu, _ = _menu(updater, app_state)

    menu.check_silently()
    assert updater.applies == 0 and menu.label() == "Check for updates"

    updater.next_check = Decision(Action.BLOCKED, reason="git fetch failed")
    menu.check_silently()
    assert app_state.snapshot().status != AppStatus.ERROR
    assert menu.label() == "Check for updates"


def test_clicks_while_busy_are_ignored():
    started = []
    menu = UpdateMenu(
        _FakeUpdater(),
        AppState(),
        on_restart=lambda: None,
        refresh=lambda: None,
        spawn=lambda fn: started.append(fn),  # never runs: stays busy
    )

    menu.click()
    menu.click()

    assert len(started) == 1
    assert menu.label() == "Checking for updates..."
