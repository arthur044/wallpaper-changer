import re
from enum import Enum, auto
from typing import Optional

# Spotify Client IDs are 32 hexadecimal characters.
_CLIENT_ID_PATTERN = re.compile(r"^[0-9a-f]{32}$", re.IGNORECASE)


class OnboardingStep(Enum):
    CREATE_APP = auto()
    REDIRECT_URI = auto()
    CLIENT_ID = auto()
    AUTHENTICATE = auto()
    VERIFY = auto()
    OPTIONS = auto()
    DONE = auto()


# Order the wizard walks through. Derived from the enum's declaration order
# rather than hand-maintained: a separate tuple is a second source of truth,
# and a member missing from it makes next_step()/previous_step() raise a bare
# ValueError from .index() instead of anything the wizard can explain.
STEP_ORDER = tuple(OnboardingStep)


def normalize_client_id(raw: Optional[str]) -> str:
    """Users paste from the dashboard, so surrounding whitespace is common."""
    return (raw or "").strip()


def is_valid_client_id(raw: Optional[str]) -> bool:
    return bool(_CLIENT_ID_PATTERN.match(normalize_client_id(raw)))


def next_step(current: OnboardingStep) -> OnboardingStep:
    """The step after `current`. DONE is terminal and returns itself."""
    index = STEP_ORDER.index(current)
    if index + 1 >= len(STEP_ORDER):
        return OnboardingStep.DONE
    return STEP_ORDER[index + 1]


def previous_step(current: OnboardingStep) -> OnboardingStep:
    """The step before `current`. The first step is terminal going backwards."""
    index = STEP_ORDER.index(current)
    if index == 0:
        return STEP_ORDER[0]
    return STEP_ORDER[index - 1]


def needs_onboarding(client_id: Optional[str]) -> bool:
    return not is_valid_client_id(client_id)


def should_abort_after_wizard(wizard_completed: bool, client_id: Optional[str]) -> bool:
    """Cancelling the wizard is only fatal when it leaves the app with no
    usable client_id. Re-running `--setup` on a working install and closing
    the window should just start the app normally, not exit."""
    if wizard_completed:
        return False
    return needs_onboarding(client_id)


def resume_step(client_id: Optional[str]) -> OnboardingStep:
    """Where to drop the user in when the wizard opens. A config that already
    carries a usable client_id skips straight to re-authenticating, since the
    dashboard steps are already done at that point."""
    if is_valid_client_id(client_id):
        return OnboardingStep.AUTHENTICATE
    return OnboardingStep.CREATE_APP
