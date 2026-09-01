from src.spotify.state_machine import next_backoff


def test_backoff_starts_at_base():
    assert next_backoff(0.0) == 5.0


def test_backoff_doubles():
    assert next_backoff(5.0) == 10.0
    assert next_backoff(10.0) == 20.0


def test_backoff_caps_at_max():
    assert next_backoff(250.0) == 300.0
    assert next_backoff(300.0) == 300.0
