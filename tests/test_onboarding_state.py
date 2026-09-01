from src.onboarding.state import (
    STEP_ORDER,
    OnboardingStep,
    is_valid_client_id,
    needs_onboarding,
    next_step,
    normalize_client_id,
    previous_step,
    resume_step,
    should_abort_after_wizard,
)

_VALID = "0123456789abcdef0123456789abcdef"


def test_valid_client_id_accepts_32_hex_chars():
    assert is_valid_client_id(_VALID) is True


def test_valid_client_id_is_case_insensitive():
    assert is_valid_client_id(_VALID.upper()) is True


def test_client_id_is_trimmed_before_validation():
    assert is_valid_client_id(f"  {_VALID}\n") is True
    assert normalize_client_id(f"  {_VALID}\n") == _VALID


def test_invalid_client_ids_are_rejected():
    assert is_valid_client_id(None) is False
    assert is_valid_client_id("") is False
    assert is_valid_client_id("abc") is False
    assert is_valid_client_id(_VALID + "ff") is False
    assert is_valid_client_id("z" * 32) is False


def test_needs_onboarding_mirrors_client_id_validity():
    assert needs_onboarding(None) is True
    assert needs_onboarding(_VALID) is False


def test_resume_step_skips_dashboard_when_client_id_already_set():
    assert resume_step(_VALID) is OnboardingStep.AUTHENTICATE


def test_resume_step_starts_from_the_top_without_a_client_id():
    assert resume_step("") is OnboardingStep.CREATE_APP


def test_next_step_walks_the_declared_order():
    assert next_step(OnboardingStep.CREATE_APP) is OnboardingStep.REDIRECT_URI
    assert next_step(OnboardingStep.OPTIONS) is OnboardingStep.DONE


def test_done_is_terminal():
    assert next_step(OnboardingStep.DONE) is OnboardingStep.DONE


def test_previous_step_stops_at_the_first_step():
    assert previous_step(OnboardingStep.REDIRECT_URI) is OnboardingStep.CREATE_APP
    assert previous_step(OnboardingStep.CREATE_APP) is OnboardingStep.CREATE_APP


def test_every_step_appears_in_the_order():
    assert set(STEP_ORDER) == set(OnboardingStep)


def test_completing_the_wizard_never_aborts():
    assert should_abort_after_wizard(True, _VALID) is False
    assert should_abort_after_wizard(True, "") is False


def test_cancelling_aborts_only_without_a_usable_client_id():
    # Fresh install: cancelling leaves nothing to run with.
    assert should_abort_after_wizard(False, None) is True
    assert should_abort_after_wizard(False, "not-a-client-id") is True


def test_cancelling_a_setup_rerun_keeps_a_working_install_running():
    # `--setup` on an already-configured app: closing the window must not
    # take the app down, since the existing client_id is still usable.
    assert should_abort_after_wizard(False, _VALID) is False
