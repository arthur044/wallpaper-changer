import logging
import os
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from enum import Enum, auto
from pathlib import Path
from typing import Callable, Iterable, Optional, Tuple

from src.os_integration.no_window import NO_WINDOW

logger = logging.getLogger(__name__)

MAIN_BRANCH = "main"
REMOTE_REF = "origin/main"
# Anything else (android/, docs) can be pulled in without touching the running app.
_DESKTOP_FILES = ("main.py", "requirements.txt")
_DESKTOP_DIRS = ("src/",)

_GIT_TIMEOUT_S = 60.0
_PIP_TIMEOUT_S = 600.0


class Action(Enum):
    UP_TO_DATE = auto()
    UPDATE = auto()  # desktop code changed: fast-forward, then restart
    DOCS_ONLY = auto()  # fast-forward, nothing to restart
    BLOCKED = auto()


@dataclass(frozen=True)
class RepoStatus:
    branch: str
    dirty: bool  # tracked files changed; untracked ones don't matter
    head: str
    remote: str
    behind: int  # commits in origin/main missing here
    ahead: int  # local commits missing in origin/main: no fast-forward
    changed: Tuple[str, ...]  # files that differ between HEAD and origin/main


@dataclass(frozen=True)
class Decision:
    action: Action
    target: str = ""  # short sha: HEAD when up to date, origin/main otherwise
    commits: int = 0
    reason: str = ""  # why it's blocked


@dataclass(frozen=True)
class ApplyResult:
    decision: Decision
    restart: bool = False
    error: Optional[str] = None


def touches_desktop(paths: Iterable[str]) -> bool:
    return any(p in _DESKTOP_FILES or p.startswith(_DESKTOP_DIRS) for p in paths)


def decide(status: RepoStatus) -> Decision:
    # Branch first: a feature branch that already contains origin/main is not
    # behind, but it isn't "up to date" either, it isn't the app's code at all.
    if status.branch != MAIN_BRANCH:
        return Decision(Action.BLOCKED, reason=f"the app folder is on {status.branch}, not {MAIN_BRANCH}")
    if status.behind == 0:
        return Decision(Action.UP_TO_DATE, target=status.head[:7])
    if status.dirty:
        return Decision(Action.BLOCKED, reason="the app folder has local changes")
    if status.ahead > 0:
        return Decision(Action.BLOCKED, reason=f"can't fast-forward: {status.ahead} local commit(s)")
    action = Action.UPDATE if touches_desktop(status.changed) else Action.DOCS_ONLY
    return Decision(action, target=status.remote[:7], commits=status.behind)


class GitError(Exception):
    pass


def _default_python(repo: Path) -> str:
    if sys.platform != "win32":
        # The clone's own venv; outside one, whatever runs the app.
        venv_python = repo / ".venv" / "bin" / "python"
        return str(venv_python) if venv_python.exists() else sys.executable
    venv_python = repo / ".venv" / "Scripts" / "python.exe"
    if venv_python.exists():
        return str(venv_python)
    # pythonw would work too, but python.exe is what pip is usually run with.
    return str(Path(sys.executable).with_name("python.exe"))


def run_pip(repo: Path, requirements: Path, run: Callable = subprocess.run) -> bool:
    command = [_default_python(repo), "-m", "pip", "install", "-r", str(requirements)]
    logger.info("Installing requirements: %s", command)
    try:
        result = run(
            command,
            cwd=str(repo),
            capture_output=True,
            text=True,
            timeout=_PIP_TIMEOUT_S,
            creationflags=NO_WINDOW,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        logger.error("pip failed to run: %s", exc)
        return False
    if result.returncode != 0:
        logger.error("pip failed (%s): %s", result.returncode, result.stderr[-2000:])
        return False
    return True


class Updater:
    """Brings the app's own clone up to origin/main. Never pushes, never
    merges: only a fast-forward on a clean main, or nothing at all."""

    def __init__(
        self,
        repo: Path,
        run: Callable = subprocess.run,
        pip: Optional[Callable[[Path, Path], bool]] = None,
    ):
        self._repo = repo
        self._run = run
        self._pip = pip if pip is not None else run_pip
        # A prompt for credentials would hang forever under pythonw.
        self._env = {**os.environ, "GIT_TERMINAL_PROMPT": "0"}

    def _git(self, *args: str) -> str:
        try:
            result = self._run(
                ["git", "-C", str(self._repo), *args],
                capture_output=True,
                text=True,
                timeout=_GIT_TIMEOUT_S,
                env=self._env,
                creationflags=NO_WINDOW,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise GitError(f"git {args[0]} could not run: {exc}") from exc
        if result.returncode != 0:
            raise GitError(f"git {args[0]} failed: {result.stderr.strip()[:200]}")
        return result.stdout.strip()

    def _status(self) -> RepoStatus:
        self._git("fetch", "--quiet", "origin")
        changed = self._git("diff", "--name-only", "HEAD", REMOTE_REF)
        return RepoStatus(
            branch=self._git("rev-parse", "--abbrev-ref", "HEAD"),
            dirty=bool(self._git("status", "--porcelain", "--untracked-files=no")),
            head=self._git("rev-parse", "HEAD"),
            remote=self._git("rev-parse", REMOTE_REF),
            behind=int(self._git("rev-list", "--count", f"HEAD..{REMOTE_REF}")),
            ahead=int(self._git("rev-list", "--count", f"{REMOTE_REF}..HEAD")),
            changed=tuple(line for line in changed.splitlines() if line),
        )

    def check(self) -> Decision:
        """Fetches and decides. Changes nothing in the working tree."""
        try:
            return decide(self._status())
        except GitError as exc:
            logger.warning("Update check failed: %s", exc)
            return Decision(Action.BLOCKED, reason=str(exc))

    def apply(self) -> ApplyResult:
        """Checks again (the tree may have changed since the menu was built),
        installs the new requirements, then fast-forwards. pip goes first so a
        failure leaves the code as it was, with nothing to roll back."""
        try:
            status = self._status()
        except GitError as exc:
            logger.warning("Update failed: %s", exc)
            return ApplyResult(Decision(Action.BLOCKED, reason=str(exc)), error=str(exc))
        decision = decide(status)
        if decision.action == Action.BLOCKED:
            return ApplyResult(decision, error=decision.reason)
        if decision.action == Action.UP_TO_DATE:
            return ApplyResult(decision)

        try:
            if "requirements.txt" in status.changed and not self._install_target_requirements():
                # Packages pip already upgraded stay upgraded: requirements use
                # >=, so the old code keeps running on them.
                return ApplyResult(decision, error="pip install failed; the app code was not updated")
            self._git("merge", "--ff-only", "--quiet", REMOTE_REF)
        except GitError as exc:
            logger.warning("Update failed: %s", exc)
            return ApplyResult(decision, error=str(exc))
        logger.info("Updated %s -> %s", status.head[:7], status.remote[:7])
        return ApplyResult(decision, restart=decision.action == Action.UPDATE)

    def _install_target_requirements(self) -> bool:
        """pip on origin/main's requirements.txt, read from git, not the tree.
        The copy lives in %TEMP%: a relative line in it (-r other.txt, -e .)
        would resolve there, so requirements.txt must stay free of them."""
        content = self._git("show", f"{REMOTE_REF}:requirements.txt")
        handle, name = tempfile.mkstemp(prefix="wallpaper-requirements-", suffix=".txt")
        path = Path(name)
        try:
            with os.fdopen(handle, "w", encoding="utf-8") as file:
                file.write(content + "\n")
            return self._pip(self._repo, path)
        finally:
            path.unlink(missing_ok=True)
