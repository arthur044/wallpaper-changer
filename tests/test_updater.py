import shutil
import subprocess
from pathlib import Path

import pytest

from src.os_integration.updater import (
    Action,
    RepoStatus,
    Updater,
    decide,
    touches_desktop,
)

_HEAD = "a" * 40
_REMOTE = "b" * 40


def _status(**overrides):
    fields = dict(
        branch="main",
        dirty=False,
        head=_HEAD,
        remote=_REMOTE,
        behind=2,
        ahead=0,
        changed=("src/poller.py",),
    )
    fields.update(overrides)
    return RepoStatus(**fields)


# --- decide(): pure ---------------------------------------------------------


def test_nothing_new_is_up_to_date():
    decision = decide(_status(behind=0, changed=()))

    assert decision.action == Action.UP_TO_DATE
    assert decision.target == _HEAD[:7]


def test_desktop_code_behind_is_an_update():
    decision = decide(_status())

    assert decision.action == Action.UPDATE
    assert (decision.target, decision.commits) == (_REMOTE[:7], 2)


@pytest.mark.parametrize("path", ["main.py", "requirements.txt", "src/graphics/mesh.py"])
def test_each_desktop_path_counts(path):
    assert decide(_status(changed=(path,))).action == Action.UPDATE


def test_only_android_and_docs_behind_needs_no_restart():
    decision = decide(_status(changed=("android/app/build.gradle.kts", "PLANNING.md", "README.md")))

    assert decision.action == Action.DOCS_ONLY


def test_dirty_tree_blocks():
    decision = decide(_status(dirty=True))

    assert decision.action == Action.BLOCKED
    assert "local changes" in decision.reason


def test_feature_branch_that_contains_main_is_not_up_to_date():
    # The dev folder on a feature branch: nothing behind, local commits ahead.
    decision = decide(_status(branch="feat/x", behind=0, ahead=5, dirty=True, changed=()))

    assert decision.action == Action.BLOCKED
    assert "feat/x" in decision.reason


def test_other_branch_blocks():
    decision = decide(_status(branch="feat/x"))

    assert decision.action == Action.BLOCKED
    assert "feat/x" in decision.reason


def test_local_commits_block_the_fast_forward():
    decision = decide(_status(ahead=1))

    assert decision.action == Action.BLOCKED
    assert "fast-forward" in decision.reason


def test_blockers_only_matter_when_there_is_something_to_apply():
    assert decide(_status(behind=0, dirty=True, changed=())).action == Action.UP_TO_DATE


def test_desktop_paths_are_matched_by_prefix_not_substring():
    assert touches_desktop(["android/src/main.py"]) is False
    assert touches_desktop(["srcx/a.py"]) is False
    assert touches_desktop(["src/a.py"]) is True


# --- Updater: real git in temporary repositories, fake pip -----------------

pytestmark_git = pytest.mark.skipif(shutil.which("git") is None, reason="git not on PATH")


def _git(repo: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True)
    return result.stdout.strip()


def _commit(repo: Path, path: str, content: str = "x") -> str:
    file = repo / path
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(content, encoding="utf-8")
    _git(repo, "add", path)
    _git(repo, "commit", "-q", "-m", f"change {path}")
    return _git(repo, "rev-parse", "HEAD")


@pytest.fixture
def repos(tmp_path):
    """An 'origin' with main and a clone of it, like GitHub and wallpaper-app."""
    origin = tmp_path / "origin"
    origin.mkdir()
    _git(origin, "init", "-q", "-b", "main")
    _git(origin, "config", "user.email", "t@example.com")
    _git(origin, "config", "user.name", "t")
    _commit(origin, "main.py", "v1")
    _commit(origin, "requirements.txt", "pillow\n")
    clone = tmp_path / "app"
    subprocess.run(["git", "clone", "-q", str(origin), str(clone)], check=True)
    _git(clone, "config", "user.email", "t@example.com")
    _git(clone, "config", "user.name", "t")
    return origin, clone


class _Pip:
    """Records what pip was asked to install and where HEAD was at the time."""

    def __init__(self, ok=True):
        self.ok = ok
        self.calls = 0
        self.requirements = None
        self.head_at_call = None

    def __call__(self, repo: Path, requirements: Path) -> bool:
        self.calls += 1
        self.requirements = requirements.read_text(encoding="utf-8")
        self.head_at_call = _git(repo, "rev-parse", "HEAD")
        return self.ok


@pytestmark_git
def test_check_fetches_but_changes_nothing(repos):
    origin, clone = repos
    before = _git(clone, "rev-parse", "HEAD")
    new = _commit(origin, "src/a.py")

    decision = Updater(clone, pip=_Pip()).check()

    assert decision.action == Action.UPDATE
    assert decision.target == new[:7] and decision.commits == 1
    assert _git(clone, "rev-parse", "HEAD") == before


@pytestmark_git
def test_up_to_date_clone(repos):
    _, clone = repos

    assert Updater(clone, pip=_Pip()).check().action == Action.UP_TO_DATE


@pytestmark_git
def test_apply_fast_forwards_and_asks_for_a_restart(repos):
    origin, clone = repos
    new = _commit(origin, "src/a.py")
    pip = _Pip()

    result = Updater(clone, pip=pip).apply()

    assert result.error is None and result.restart is True
    assert _git(clone, "rev-parse", "HEAD") == new
    assert pip.calls == 0  # requirements.txt did not change


@pytestmark_git
def test_docs_only_fast_forwards_without_a_restart(repos):
    origin, clone = repos
    new = _commit(origin, "android/x.kt")

    result = Updater(clone, pip=_Pip()).apply()

    assert result.decision.action == Action.DOCS_ONLY
    assert result.error is None and result.restart is False
    assert _git(clone, "rev-parse", "HEAD") == new


@pytestmark_git
def test_changed_requirements_are_installed_before_the_code_moves(repos):
    origin, clone = repos
    old = _git(clone, "rev-parse", "HEAD")
    new = _commit(origin, "requirements.txt", "pillow\nrequests\n")
    pip = _Pip()

    result = Updater(clone, pip=pip).apply()

    assert pip.calls == 1 and result.restart is True
    # origin/main's requirements, installed while the clone was still on the old commit.
    assert "requests" in pip.requirements
    assert pip.head_at_call == old
    assert _git(clone, "rev-parse", "HEAD") == new


@pytestmark_git
def test_failed_pip_leaves_the_code_untouched(repos):
    origin, clone = repos
    old = _git(clone, "rev-parse", "HEAD")
    _commit(origin, "requirements.txt", "does-not-exist\n")

    result = Updater(clone, pip=_Pip(ok=False)).apply()

    assert result.restart is False
    assert "pip" in result.error
    assert _git(clone, "rev-parse", "HEAD") == old
    assert _git(clone, "status", "--porcelain") == ""


@pytestmark_git
def test_dirty_clone_is_left_alone(repos):
    origin, clone = repos
    old = _git(clone, "rev-parse", "HEAD")
    _commit(origin, "src/a.py")
    (clone / "main.py").write_text("edited", encoding="utf-8")

    result = Updater(clone, pip=_Pip()).apply()

    assert result.decision.action == Action.BLOCKED and result.error
    assert _git(clone, "rev-parse", "HEAD") == old
    assert (clone / "main.py").read_text(encoding="utf-8") == "edited"


@pytestmark_git
def test_untracked_files_do_not_block(repos):
    # The clone's .venv is ignored, but a stray log must not block updates either.
    origin, clone = repos
    new = _commit(origin, "src/a.py")
    (clone / "run.log").write_text("", encoding="utf-8")

    assert Updater(clone, pip=_Pip()).apply().error is None
    assert _git(clone, "rev-parse", "HEAD") == new


@pytestmark_git
def test_clone_on_another_branch_is_left_alone(repos):
    origin, clone = repos
    _git(clone, "checkout", "-q", "-b", "feat/x")
    _commit(origin, "src/a.py")

    result = Updater(clone, pip=_Pip()).apply()

    assert result.decision.action == Action.BLOCKED and "feat/x" in result.error


@pytestmark_git
def test_local_commit_blocks_the_fast_forward(repos):
    origin, clone = repos
    local = _commit(clone, "src/local.py")
    _commit(origin, "src/a.py")

    result = Updater(clone, pip=_Pip()).apply()

    assert result.decision.action == Action.BLOCKED
    assert _git(clone, "rev-parse", "HEAD") == local


def test_git_missing_is_an_error_not_a_crash(tmp_path):
    def run(cmd, **kwargs):
        raise FileNotFoundError("git")

    updater = Updater(tmp_path, run=run, pip=_Pip())

    assert updater.check().action == Action.BLOCKED
    result = updater.apply()
    assert result.error and "git" in result.error and result.restart is False


def test_git_is_run_without_a_console_or_a_password_prompt(tmp_path):
    seen = []

    def run(cmd, **kwargs):
        seen.append(kwargs)
        return subprocess.CompletedProcess(cmd, 1, "", "boom")

    Updater(tmp_path, run=run, pip=_Pip()).check()

    assert seen[0]["creationflags"] == subprocess.CREATE_NO_WINDOW
    assert seen[0]["env"]["GIT_TERMINAL_PROMPT"] == "0"


def test_requirements_have_no_relative_lines():
    # pip reads a copy in %TEMP% (see Updater._install_target_requirements),
    # where a relative -r/-c/-e line would point somewhere else.
    lines = (Path(__file__).resolve().parents[1] / "requirements.txt").read_text(encoding="utf-8").splitlines()

    assert not [line for line in lines if line.strip().startswith(("-r", "-c", "-e", "."))]
