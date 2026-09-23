# Spotify Dynamic Wallpaper Engine

App de bandeja pro Windows. Define o wallpaper da área de trabalho (e opcionalmente a tela de bloqueio) como a capa do álbum que está tocando no Spotify.

Tem também uma **versão Android** em [`android/`](android/README.md), que faz o mesmo no celular. Uso pessoal nos dois casos.

## Como funciona

**Duas fontes, híbrido:**

- **SMTC (Windows Media Session)** — com o app desktop do Spotify aberto, lê identidade de faixa/álbum direto da sessão de mídia do Windows (`Windows.Media.Control`), zero chamada de rede, quase instantâneo. Só enxerga reprodução local no desktop, não celular/Connect/outros dispositivos.
- **API Web do Spotify** — fallback quando o Spotify desktop está fechado (cobre celular/Connect), e usada uma vez por álbum novo (detectado via SMTC) pra buscar o `album_id` real + arte em alta resolução. A arte é **sempre** a imagem oficial do Spotify — a thumbnail do próprio SMTC (300x300, baixa resolução) nunca é renderizada.

**Pré-busca da tracklist:** resolver um álbum também busca a lista completa de faixas dele numa chamada extra, indexada por artista+nome. Pular pra qualquer outra faixa desse álbum — mesmo uma nunca tocada antes — é reconhecido na hora, sem nova chamada.

**Ciente da tela bloqueada:** estação bloqueada + Spotify desktop fechado → polling para por completo, zero requisição desperdiçada. Bloqueada + Spotify ainda tocando → SMTC continua funcionando (é grátis de qualquer forma).

**Cache:** o fundo composto de cada álbum (arte + sombra + cantos arredondados) é renderizado uma vez e cacheado sob o `album_id` real do Spotify em `%LOCALAPPDATA%\SpotifyWallpaperEngine\cache\album_bases\`. Trocas de faixa dentro de um álbum já cacheado só redesenham o texto sobreposto.

## Requisitos

- Windows 10/11
- Python 3.11+
- Um app Spotify registrado em [developer.spotify.com](https://developer.spotify.com/dashboard) (fluxo PKCE, sem client secret) com redirect URI `http://127.0.0.1:8888/callback`

## Instalação

```
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
python main.py
```

Na primeira execução abre um assistente de configuração que conduz os 6 passos:

1. **Criar o app no Spotify** — abre o painel de desenvolvedor no navegador
2. **Cadastrar o Redirect URI** — mostra `http://127.0.0.1:8888/callback` com botão de copiar (sem esse passo o login falha; é o erro mais comum)
3. **Informar o Client ID** — cole os 32 caracteres do painel; é validado e gravado no `config.json`
4. **Entrar na conta** — abre o login OAuth (PKCE) no navegador; o token vai pro Windows Credential Manager, não pra disco
5. **Verificar** — uma consulta ao Spotify confirmando que está tudo funcionando
6. **Opções finais** — iniciar com o Windows e/ou sincronizar a tela de bloqueio

Pra rodar de novo depois (trocar de conta, token revogado): `python main.py --setup`, ou o item **Setup...** no menu da bandeja.

## Configuração (`config.json`)

| Campo | Padrão | Significado |
|---|---|---|
| `client_id` | `""` | Client ID do app Spotify (obrigatório) |
| `redirect_uri` | `http://127.0.0.1:8888/callback` | Precisa bater com o redirect URI registrado no app Spotify |
| `poll_interval_seconds` | `4.0` | Tick local barato: com que frequência o poller reavalia estado de bloqueio / cache do SMTC. Sem custo de rede. |
| `use_smtc` | `true` | Detecta tocando agora via SMTC do Windows em vez de polling puro na API. Defina `false` pra forçar polling puro via API Web. |
| `fallback_poll_interval_seconds` | `25.0` | Com que frequência chamar a API Web quando o SMTC não tem sessão (reprodução via Connect/celular, ou Spotify desktop fechado). Mantenha bem acima de alguns segundos — valor baixo demais arrisca o rate limit (429) do Spotify. |
| `art_size_pct` | `0.68` | Tamanho da arte como fração da altura da tela |
| `corner_radius` | `16` | Arredondamento dos cantos da arte, px |
| `shadow_blur_radius` | `24` | Blur da sombra, px |
| `show_track_info` | `true` | Desenha texto de faixa/artista embaixo da arte |
| `background_style` | `"solid"` | Fundo: `"solid"` (cor predominante da capa), `"mesh"` (gradiente suave com 2 a 4 cores da capa) ou `"blur"` (a própria capa cobrindo a tela, desfocada e escurecida nas bordas) |
| `art_glow` | `false` | Troca a sombra preta da arte por um brilho na cor mais vibrante da capa |
| `blur_strength` | `26` | Intensidade do desfoque do fundo `"blur"`, de 0 (quase nítido) a 100 (uma nuvem de cor); o escurecimento das bordas é fixo |
| `art_frame` | `"none"` | Moldura de vidro em volta da capa: `"none"`, `"single"` (uma borda) ou `"double"` (duas); a capa encolhe para caber nela, sem mover o texto |
| `smooth_transition` | `false` | Troca o wallpaper com o fade do próprio Windows (precisa das animações do sistema ligadas); se falhar, troca na hora |
| `text_card` | `"none"` | Cartão atrás do título/artista: `"none"` ou `"glass"` (vidro fosco sobre o fundo). Só com `show_track_info` |
| `sync_lock_screen` | `false` | Também aplica o wallpaper na tela de bloqueio real do Windows (exige uma Scheduled Task elevada via UAC, uma vez só) |
| `log_level` | `"INFO"` | Nível de log |

## Flags de CLI

```
python main.py --setup                  # reabre o assistente de configuração
python main.py --install-autostart      # registra no HKCU Run
python main.py --uninstall-autostart
python main.py --apply-lockscreen       # interno: chamado pela scheduled task, não usar manualmente
```

## Menu da bandeja

Pause/Resume, Force Sync, alternar Sync Lock Screen, Re-authenticate (aparece em erro de auth), Setup..., Exit.

## Testes

```
.venv\Scripts\pytest
```

## Versão Android

Port do mesmo app para Android, em `android/`. Mesma ideia e mesmo visual: a capa vira o
wallpaper, com a **mesma cor de fundo** do desktop (o algoritmo do ColorThief foi portado
para Kotlin, não substituído — trocá-lo por outro muda as cores).

**O que muda em relação ao Windows:**

| | Windows | Android |
|---|---|---|
| Fonte principal | SMTC (Spotify desktop aberto) | API Web, a cada 25 s, só com a tela ligada |
| Fonte instantânea | SMTC, sempre | MediaSession local, **opcional** (pede acesso a notificações) |
| Tela de bloqueio | Tarefa agendada com elevação | Direto, pelo `WallpaperManager` |
| Controles | Menu da bandeja | Tela do app e bloco nas Configurações rápidas |
| Ajuste do visual | Editar o `config.json` | Sliders na tela, com redesenho na hora |

**Além disso, no Android:** guia de primeiro uso que valida o Client ID e explica a
Redirect URI; volta a sincronizar sozinho depois de reiniciar o celular, atualizar o app
ou ter o processo morto; e um modo "sem notificação fixa", que dispensa o serviço em
primeiro plano quando a detecção instantânea está ligada.

**Requisitos do Spotify, que valem para quem for instalar:**

- Criar um app próprio no [painel do Spotify](https://developer.spotify.com/dashboard) e
  usar o **seu** Client ID. Não dá para embutir um Client ID compartilhado: desde
  15/05/2025 o Spotify só concede cota estendida a empresas com 250 mil usuários ativos
  por mês, então cada pessoa usa o app dela, em modo de desenvolvimento.
- O dono do app precisa de **Spotify Premium**, e o modo de desenvolvimento aceita até
  **5 contas** por app — de sobra para uso pessoal.

Build, testes, estrutura e detalhes de assinatura do APK estão no
[README do Android](android/README.md).

## Estrutura do projeto

```
main.py                    entrypoint, wiring
src/config/                dataclass Settings, paths (%APPDATA%/%LOCALAPPDATA%)
src/onboarding/            assistente de configuração (estado puro, ações, UI Tkinter)
src/spotify/               client da API Web, poller (gate híbrido SMTC/API), auth
src/os_integration/        watcher SMTC, detecção de bloqueio, wallpaper/lockscreen/autostart, tray
src/graphics/              renderização da arte pro wallpaper (Pillow)
tests/
android/                   port Android (Kotlin): :core puro + :app, veja android/README.md
```
