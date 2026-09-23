import dataclasses
import json
import logging
from dataclasses import dataclass, field
from typing import List

from src.config.paths import config_file

logger = logging.getLogger(__name__)

DEFAULT_REDIRECT_URI = "http://127.0.0.1:8888/callback"
DEFAULT_SCOPE = "user-read-currently-playing user-read-playback-state"

BACKGROUND_STYLES = ("solid", "mesh")
TEXT_CARDS = ("none", "glass")


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
    # Spotify: ao ver uma faixa nova, o Poller faz uma chamada throttled à
    # API Web pra pegar o album_id real + arte em alta resolução, e mais uma
    # pra buscar a tracklist inteira do álbum, indexando cada faixa por
    # "artista::nome" (ver Poller._track_to_album/_resolve_and_cache_album).
    # Assim, pular direto pra qualquer outra faixa desse álbum - mesmo sem
    # ter passado pelas anteriores - é reconhecido na hora, sem nova chamada.
    # Enquanto uma faixa não resolve, o wallpaper atual é mantido em vez de
    # mostrar imagem que não seja do Spotify. Defina False para forçar polling
    # puro via API
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
    # Fundo atrás da arte: "solid" (cor predominante, o visual original) ou
    # "mesh" (gradiente suave com 2 a 4 cores da capa).
    background_style: str = "solid"
    # Sombra da arte na cor mais vibrante da capa, em vez de preta.
    art_glow: bool = False
    # Cartão atrás do título/artista: "none" ou "glass" (vidro fosco).
    text_card: str = "none"
    fallback_resolution: List[int] = field(default_factory=lambda: [1920, 1080])
    log_level: str = "INFO"
    sync_lock_screen: bool = False

    @classmethod
    def from_dict(cls, data: dict) -> "Settings":
        known_fields = {f.name for f in dataclasses.fields(cls)}
        filtered = {k: v for k, v in data.items() if k in known_fields}
        return cls(**_drop_invalid_choices(filtered))


# Campos de escolha fechada: um valor fora da lista é descartado (volta ao
# padrão) em vez de chegar ao renderer. Só o campo inválido é afetado.
_CHOICES = {
    "background_style": BACKGROUND_STYLES,
    "text_card": TEXT_CARDS,
    "art_glow": (True, False),
}


def _drop_invalid_choices(data: dict) -> dict:
    valid = {}
    for key, value in data.items():
        allowed = _CHOICES.get(key)
        # type() e não "in": 1 == True, e "1" não é um bool válido.
        if allowed is not None and not any(type(value) is type(a) and value == a for a in allowed):
            logger.warning("Invalid %s=%r in config, using default", key, value)
            continue
        valid[key] = value
    return valid


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
