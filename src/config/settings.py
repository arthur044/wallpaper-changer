import dataclasses
import json
import logging
from dataclasses import dataclass, field
from typing import List

from src.config.paths import config_file

logger = logging.getLogger(__name__)

DEFAULT_REDIRECT_URI = "http://127.0.0.1:8888/callback"
DEFAULT_SCOPE = "user-read-currently-playing user-read-playback-state"


@dataclass
class Settings:
    client_id: str = ""
    redirect_uri: str = DEFAULT_REDIRECT_URI
    scope: str = DEFAULT_SCOPE
    # Tick local barato: com que frequência o poller reavalia o estado de
    # bloqueio da tela e o snapshot em cache do SMTC. Não custa nenhuma
    # chamada de rede, então é seguro manter baixo.
    poll_interval_seconds: float = 4.0
    # Detecta troca de faixa/álbum via sessão SMTC do Windows do app desktop
    # do Spotify (src/os_integration/smtc.py) em vez de fazer polling na API
    # Web do Spotify - custo de rede zero e detecção quase instantânea, mas
    # só enxerga reprodução local no desktop, não celular/Connect/outros
    # dispositivos. A arte renderizada NUNCA vem da thumbnail local do SMTC
    # (ela é baixa resolução, 300x300) - é sempre a imagem oficial do
    # Spotify: ao ver um álbum novo, o Poller faz UMA chamada throttled à
    # API Web para pegar o album_id real e a arte em alta resolução (ver
    # Poller._smtc_album_resolution); enquanto isso não resolve, o wallpaper
    # atual é mantido em vez de mostrar uma imagem que não seja do Spotify.
    # Trocas de faixa dentro do mesmo álbum já resolvido continuam 100% via
    # SMTC, sem nova chamada. Defina False para forçar polling puro via API
    # Web (ex.: se o SMTC se mostrar instável numa máquina).
    use_smtc: bool = True
    # Com que frequência chamar a API Web do Spotify quando o SMTC não tem
    # sessão ativa do Spotify (app desktop fechado, ou use_smtc=False) - o
    # caminho de fallback que cobre reprodução via celular/Connect/outros
    # dispositivos. Mantido bem acima de poll_interval_seconds porque essa
    # é a chamada que pode disparar o rate limiting (429) do Spotify se
    # feita com frequência demais. Também é pulada por completo enquanto a
    # estação está bloqueada e não há sessão SMTC ativa, já que não há nada
    # para mostrar nem motivo para gastar requisições.
    fallback_poll_interval_seconds: float = 25.0
    art_size_pct: float = 0.68
    corner_radius: int = 16
    shadow_blur_radius: int = 24
    show_track_info: bool = True
    fallback_resolution: List[int] = field(default_factory=lambda: [1920, 1080])
    log_level: str = "INFO"
    sync_lock_screen: bool = False

    @classmethod
    def from_dict(cls, data: dict) -> "Settings":
        known_fields = {f.name for f in dataclasses.fields(cls)}
        filtered = {k: v for k, v in data.items() if k in known_fields}
        return cls(**filtered)


def load_settings() -> Settings:
    path = config_file()
    if not path.exists():
        settings = Settings()
        save_settings(settings)
        logger.info("No config found, created default at %s", path)
        return settings

    try:
        with path.open("r", encoding="utf-8") as fh:
            data = json.load(fh)
        return Settings.from_dict(data)
    except (json.JSONDecodeError, OSError) as exc:
        logger.error("Failed to read config at %s (%s), falling back to defaults", path, exc)
        return Settings()


def save_settings(settings: Settings) -> None:
    path = config_file()
    try:
        with path.open("w", encoding="utf-8") as fh:
            json.dump(dataclasses.asdict(settings), fh, indent=2)
    except OSError as exc:
        logger.error("Failed to write config at %s (%s)", path, exc)
