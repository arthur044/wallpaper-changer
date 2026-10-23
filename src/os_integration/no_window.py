import subprocess

# For console programs (git, pip, schtasks) run from the app. On Windows the
# app runs under pythonw, which has no console to share, so without
# CREATE_NO_WINDOW each call flashes a terminal of its own. Other systems have
# no such flag, and 0 is the only creationflags they accept.
NO_WINDOW: int = getattr(subprocess, "CREATE_NO_WINDOW", 0)
