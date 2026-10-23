import json
import subprocess

import pytest

from src.os_integration import hyprland

# As `hyprctl monitors -j` gave it on the dev machine (2026-10-01), trimmed.
_DP1 = {"id": 0, "name": "DP-1", "width": 1920, "height": 1080, "x": 1280, "y": 0, "scale": 1,
        "transform": 0, "focused": True, "disabled": False, "mirrorOf": "none"}


def _run(stdout="", returncode=0, stderr=""):
    def run(args, **kwargs):
        return subprocess.CompletedProcess(args, returncode, stdout, stderr)

    return run


def test_the_dev_machines_monitor():
    assert hyprland.primary_resolution(_run(json.dumps([_DP1]))) == (1920, 1080)


def test_the_lowest_id_is_primary_not_the_focused_one():
    # The canvas size is in the album base's cache key: it must not follow the focus.
    second = {**_DP1, "id": 1, "name": "HDMI-A-1", "width": 2560, "height": 1440, "focused": True}
    first = {**_DP1, "focused": False}

    assert hyprland.primary_monitor([second, first])["name"] == "DP-1"


def test_disabled_and_mirroring_monitors_do_not_count():
    disabled = {**_DP1, "id": 0, "disabled": True}
    mirror = {**_DP1, "id": 1, "mirrorOf": "DP-2"}
    real = {**_DP1, "id": 2, "name": "DP-2"}

    assert hyprland.primary_monitor([disabled, mirror, real])["name"] == "DP-2"
    assert hyprland.primary_monitor([disabled]) is None


def test_scale_does_not_shrink_the_canvas():
    # A 4K monitor at 1.5x: the shell draws the background at 3840x2160.
    assert hyprland.physical_size({**_DP1, "width": 3840, "height": 2160, "scale": 1.5}) == (3840, 2160)


@pytest.mark.parametrize("transform, expected", [(0, (1920, 1080)), (1, (1080, 1920)), (2, (1920, 1080)), (3, (1080, 1920)), (5, (1080, 1920))])
def test_a_rotated_monitor_swaps_width_and_height(transform, expected):
    assert hyprland.physical_size({**_DP1, "transform": transform}) == expected


@pytest.mark.parametrize(
    "run, error",
    [
        (_run(returncode=1, stderr="HYPRLAND_INSTANCE_SIGNATURE not set"), OSError),
        (_run("not json"), ValueError),
        (_run('{"not": "a list"}'), ValueError),
        (_run("[]"), ValueError),
        (_run('[{"id": 0, "width": 0, "height": 1080}]'), ValueError),
    ],
    ids=["hyprctl fails", "garbled", "not a list", "no monitors", "zero size"],
)
def test_anything_odd_raises_for_the_caller_to_fall_back(run, error):
    with pytest.raises(error):
        hyprland.primary_resolution(run)
