# PLANNING — Spotify Dynamic Wallpaper Engine

Documentação técnica do estado atual. Descreve **como o sistema funciona hoje**, não como
foi construído. Uso e instalação estão no [README](README.md) e no
[README do Android](android/README.md).

Última revisão: 2026-10-01 · base: `main` @ `60bc314` (PRs #1–#20 mergeados)

---

## 1. Visão geral

Troca o wallpaper (e opcionalmente a tela de bloqueio) pela capa do álbum que está tocando
no Spotify, com o nome da faixa e do artista embaixo. Duas implementações independentes, com
o mesmo comportamento e o mesmo visual:

| | Desktop | Android |
|---|---|---|
| Linguagem | Python 3.11+ | Kotlin 2.4 (JVM 17) |
| Plataforma | Windows 10/11 | Android 8.0+ (minSdk 26, targetSdk 36, compileSdk 37) |
| Código | `main.py`, `src/` | `android/` (módulos `:core` e `:app`) |
| Interface | Ícone na bandeja (pystray) + assistente Tkinter + widget de letra (PySide6/Qt) | Compose: onboarding, tela principal, bloco nas Configurações rápidas |
| Render | Pillow | `android.graphics.Canvas` |
| Testes | 465 (pytest) | 552: 403 JUnit 5 no `:core`; 20 unitários e 129 instrumentados no `:app` |

**Regra de paridade:** tudo o que é visual (cor de fundo, mesh, glow, blur, moldura, cartão)
é **portado** do desktop para o Android, não reimplementado. Os valores são conferidos contra
números gerados pelo código Python.

**Exceções (decisões do usuário):** ficam fora da regra de paridade "Compartilhar letra"
(§2.5), que existe só no Android, e o widget de letra sincronizada (§2.6), que existe só no
desktop (confirmado em 2026-09-28).

---

## 2. Arquitetura geral

### 2.1 Pipeline comum

```
fonte local (grátis, instantânea)       API Web do Spotify (cara, sujeita a 429)
  SMTC (Win) / MediaSession (Android)     currently-playing + album tracks
              │                                        │
              └──────────► motor de sync ◄─────────────┘
                   (Poller / SyncEngine)
                   ├─ índice faixa→álbum (LRU, persistido)
                   ├─ decide(): RENDER | NOOP | IDLE
                   ▼
             render: base do álbum (cache em disco) + texto da faixa
                   ▼
             aplicação: wallpaper do sistema / live wallpaper / tela de bloqueio
```

Invariantes nas duas plataformas:

- **A arte é sempre a imagem oficial do Spotify.** A miniatura da sessão local (SMTC e
  MediaSession) nunca é desenhada. Uma faixa sem álbum resolvido deixa o wallpaper como está.
- A fonte local só diz **o que** toca (identidade: `artista::título`). A API Web só é chamada
  para resolver um álbum ainda desconhecido.
- Resolver um álbum agenda a **tracklist** inteira, buscada *depois* do render (uma chamada
  por álbum). Assim, pular para outra faixa do mesmo álbum não custa chamada.
- Um 429 bloqueia **todas** as chamadas à API até o `Retry-After`, inclusive as forçadas.
- Falhas de rede usam backoff exponencial de 5 s, dobrando, com teto de 300 s.
- Se a arte salva falha ao desenhar, a faixa é esquecida do índice **uma vez**, para o link
  ser buscado de novo.

### 2.2 Desktop: componentes e threads

| Thread | Componente | Função |
|---|---|---|
| principal | `QtHost` (`lyrics_widget/qt_host.py`) | `QApplication` e o widget de letra (§2.6); bloqueia em `exec()` até Exit ou Restart |
| `tray` (daemon) | `TrayApp` (`os_integration/tray.py`) | Menu da bandeja (enxuto, ver abaixo). O pystray processa as mensagens na thread que chama `run()`, então ele ganhou uma thread própria. Quando o loop termina, pede ao Qt para sair |
| `smtc-watcher` | `SmtcWatcher` (`os_integration/smtc.py`) | Loop asyncio próprio (winsdk). Eventos SMTC (inclusive da timeline) mais uma consulta de segurança a cada 2 s. Um evento que chega com o loop já fechando é descartado (`notify_loop`). Expõe um snapshot e a última amostra de posição (`get_timeline`), cada um sob lock. Escolhe a sessão cujo id contém `spotify` **ou** `spotifast` (#16: o `spotifast.exe` é um cliente cujo id não contém "spotify") |
| `poller` | `Poller` (`spotify/poller.py`) | Loop principal de sync e render |
| `lyrics` (daemon, uma por busca) | `QtHost._look_up` | Uma busca no LRCLIB, só com o widget ligado |

O `QtHost` é criado **antes** do Poller: o Qt precisa definir o DPI awareness (per-monitor
v2) antes do primeiro render, que chama `SetProcessDpiAwareness`. Se o loop do Qt termina
sozinho, `main` derruba o ícone da bandeja e espera a thread `tray` por até 5 s.

Comunicação entre threads: `AppState` (`utils/app_state.py`), que guarda o status sob lock e
três `threading.Event`: `pause_event`, `force_sync_event` e `stop_event`. O Poller espera no
`force_sync_event` com timeout igual ao intervalo, então Force Sync acorda o loop na hora.

**Ciclo do Poller** (`_poll_once`):

1. Pausado → espera `poll_interval_seconds` (4 s).
2. Há snapshot SMTC → **caminho híbrido** (`_handle_smtc_snapshot`):
   - procura `artista::título` no `TrackAlbumStore` e nos álbuns não verificados;
   - não achou e está tocando → uma chamada à API, espaçada em pelo menos 5 s;
   - se a API ainda devolve outra faixa (ela atrasa em relação ao app), tenta de novo, até
     3 vezes. Depois disso usa o álbum mesmo assim, marcado como **não verificado** (fica só
     em memória);
   - desenha e em seguida busca a tracklist pendente.
3. Sem SMTC e com a estação bloqueada → não chama a API.
4. Sem SMTC → consulta a API a cada `fallback_poll_interval_seconds` (25 s). Esse caminho
   cobre celular e Connect.
5. Ao fim de cada ciclo, `TrackAlbumStore.flush()` (grava só se algo mudou).

`decide()` (`spotify/state_machine.py`) é pura: nada tocando → IDLE, mesma faixa → NOOP,
senão RENDER. Se é álbum novo ou faixa nova de um álbum em cache, quem decide é a existência
do arquivo da base.

**Render** (`main._make_render_fn`):

1. Tira um `dataclasses.replace(settings)`, uma cópia, porque a bandeja e a janela de
   configurações alteram as settings em outra thread.
2. `compute_layout` usa a resolução do monitor primário, com DPI-aware. A arte ocupa
   `art_size_pct` × altura, centralizada.
3. `base_cache_key` → `album_bases/<key>.png`. Se existe, reutiliza e atualiza o mtime. Senão
   obtém a arte, compõe a base, salva e poda o cache. A arte vem do `AlbumArtCache`
   (`graphics/art_cache.py`, #19): a capa original e as cores do álbum ficam em
   `cache/album_art`, então mudar o estilo (chave nova) não baixa a capa nem roda o
   ColorThief de novo (ver "Cache da arte original" abaixo).
4. Copia a base e desenha título e artista por cima (com cartão de vidro opcional). A cor do
   texto vem da média dos pixels atrás dele. Antes disso, `sample_widget_tint` tira a cor do
   quarto inferior direito da base já em memória (2–4 ms a 1080p, sem segunda passada do
   ColorThief). `render_for_now_playing` devolve essa cor, que vai para o `TintHolder` do
   widget. `base_cache_key` e o desenho não mudaram.
5. Salva alternando entre `wallpaper_a.png` e `wallpaper_b.png`, para o Explorer nunca
   segurar lock no arquivo sendo escrito.
6. `set_wallpaper`: direto (`SystemParametersInfoW`) ou com fade (`IActiveDesktop`). O fade
   cai para o modo direto se falhar.
7. Se `sync_lock_screen` está ligado, chama `lockscreen.request_update`.

**Tela de bloqueio (desktop):** uma Scheduled Task `SpotifyWallpaperEngine_LockScreen` é
criada uma vez, com `/rl highest` (um prompt UAC). Cada atualização grava
`lockscreen_pending.json` e roda `schtasks /run`. A task executa `main.py --apply-lockscreen`
elevado e escreve em `HKLM\...\PersonalizationCSP`. Todo `schtasks` usa `CREATE_NO_WINDOW`.

- `ensure_task` compara o comando registrado (`schtasks /query /xml`, lido na página de
  código OEM) com o desta pasta. Se a task aponta para outra pasta, ela é **recriada** (um
  UAC): senão rodaria, com direitos de administrador, o código de outra cópia do app.
- `uninstall_task` apaga a task e, se ela sobrou (criada elevada, apagar costuma exigir UAC),
  tenta de novo elevado. Devolve `False` se ela continua lá.
- Desligar a tela de bloqueio (janela de configurações ou assistente) só grava
  `sync_lock_screen=False` depois que a task sumiu. Com o UAC recusado a opção continua
  ligada, e o assistente avisa.
- O toggle da janela ignora um segundo clique enquanto o UAC está aberto (`lock_sync_busy`).
  Ao terminar, o `update_menu` do ícone é chamado de novo (o menu é refeito quando o clique
  retorna, muito antes de o UAC ser respondido), mas nunca no lugar do erro do próprio toggle.

**Janela de configurações (#17; `src/settings_window/`):** os controles saíram do menu
da bandeja (um menu fecha a cada clique) para uma janela Qt que fica aberta. Só desktop:
exceção de paridade, porque o Android não tem bandeja.

| Peça | Papel |
|---|---|
| Menu da bandeja (`TrayApp`) | Só `Settings...` (também o clique esquerdo no ícone, `default=True`), `Pause`/`Resume`, `Re-authenticate` (visível só com `AppStatus.ERROR`) e `Exit` (desabilitado durante uma atualização) |
| `SettingsWindow` (`window.py`) | Grupos: reprodução (Pause, Sync now), estilo do wallpaper, tela de bloqueio, widget de letra e app (versão, Check for updates, Re-authenticate, Setup..., Restart, Exit). Só a thread do Qt. Os controles escrevem por `StyleActions`, `LyricsWidgetControls` e `AppCommands`; um `QTimer` de 0,5 s, **só com a janela visível**, chama `refresh()`, que lê o estado vivo de volta, então mudanças feitas pela bandeja, pelo widget ou pelo updater aparecem na janela |
| `StyleActions` (`style_actions.py`) | Lógica de estilo (fundo, blur, glow, moldura, cartão de vidro, transição). Sem Qt: altera a mesma `Settings` que o render lê, grava e pede o redesenho. Só age se o valor mudou |
| `AppCommands` (`commands.py`) | Dataclass congelada de callbacks que a bandeja entrega à janela (`TrayApp.commands()`); as ações lentas (UAC, git, assistente, parada do Poller) rodam em thread própria e voltam na hora |
| `QtHost.open_settings()` | Abre a janela na thread do Qt, por sinal do `_Bridge` |

**Atualização do desktop (`os_integration/updater.py`, `update_menu.py`):** o app roda de um
clone próprio do repositório, na `main`. O botão "Check for updates" da janela faz
`git fetch` e decide (`decide`, pura): `UP_TO_DATE`, `UPDATE` (mudou `main.py`,
`requirements.txt` ou `src/`), `DOCS_ONLY` (só fast-forward) ou `BLOCKED` (não está na
`main`, há mudança local ou commit local). Um segundo clique aplica: `pip install` dos
requisitos de `origin/main` **antes**, depois `merge --ff-only`, depois reinicia. Nada é
aplicado sem clique; falhas aparecem no rótulo do item, não no status de erro do Spotify.
Restart e Exit ficam desabilitados enquanto o pip ou o fast-forward rodam. A versão
(`abc1234 (AAAA-MM-DD)`, de `git log -1`) aparece na janela e na dica do ícone.

**Instância única (`os_integration/single_instance.py`):** mutex `Local\SpotifyWallpaperEngine`,
adquirido depois do assistente. Uma segunda instância espera até 20 s (o Restart sobe a nova
antes de a antiga sair) e, se o mutex continua ocupado, encerra. Se o mutex não puder ser
criado ou esperado, o app roda sem trava em vez de nunca iniciar.

**Composição da base** (`graphics/renderer._build_base_canvas`):

| Camada | Origem |
|---|---|
| Fundo `solid` | Cor dominante (ColorThief, `quality=4`) |
| Fundo `mesh` | `mesh.py`: 3 ou 4 cores (dominante + paleta de acentos), manchas gaussianas mescladas em OKLab numa grade de 64 px, ampliada em float e arredondada com dither Bayer 8×8 |
| Fundo `blur` | A própria capa cobrindo a tela, desfocada numa cópia 1/4 (sigma = lado menor × `blur_strength` × 0,001), com vinheta radial |
| Halo | Sombra preta (`shadow_blur_radius`) ou glow na cor mais vívida da paleta, afastada da cor de fundo até contrastar |
| Moldura | `single` (1 borda, 5%) ou `double` (7,5% + 3,5%). A arte encolhe para o conjunto ocupar a caixa original |
| Arte | Redimensionada com LANCZOS e cantos arredondados (`corner_radius`) |

A paleta de acentos é uma segunda quantização do ColorThief (cerca de 200 ms) e só é calculada
com `mesh` ou `art_glow` ligados.

### 2.3 Android: módulos e componentes

| Módulo | Conteúdo | Dependência de Android |
|---|---|---|
| `:core` | Settings, `SyncEngine`, `decide`, backoff, `ApiThrottle`, `TrackAlbumIndex`, cliente da API (OkHttp), port do ColorThief, layout, mesh, blur, moldura, vidro, chave de cache, eviction, quadros por forma de tela (`ScreenFrames`), atualização do app (`update/`), letras (`lyrics/`: cliente LRCLIB, resposta em memória, pré-busca) e layout do compartilhamento (`share/`) | Nenhuma (JVM pura, testável sem aparelho) |
| `:app` | OAuth (AppAuth + Tink), render em Canvas, cache de bases, aplicação do wallpaper, serviços, telas, instalador de atualizações, imagem e tela de compartilhar letra (`share/`, `ui/LyricsShareScreen.kt`) | Sim |

DI manual: um `AppContainer` por processo (`WallpaperApp.kt`) com uma instância de cada
dependência longa: `SettingsRepository`, `SpotifyAuth`, `SpotifyApi`, `WallpaperComposer`,
`WallpaperUpdater`, `SyncEngine`, `SyncController`, `LiveWallpaperFrames`, `UpdateController`,
`LyricsPrefetch` e `LyricsShare`.

**Quem roda o `SyncEngine`** (nunca os dois ao mesmo tempo: `run()` usa um `Mutex`):

| Dono | Quando | Comportamento |
|---|---|---|
| `SyncService` (FGS `specialUse`) | Modo padrão | Roda o engine **só com a tela ligada** (`screenOnFlow` + `collectLatest`). A volta da rede acorda um backoff. Alimenta o engine com o MediaSession se a opção estiver ligada |
| `SpotifyMediaListener` (NotificationListenerService) | Modo local (`useMediaSession` + `localOnly`) | Sem serviço em primeiro plano e sem notificação fixa. Sem nada tocando no aparelho, não faz nenhuma consulta |

Componentes de apoio:

- `SyncController`: o único ponto que liga e desliga o sync. Grava `syncEnabled` e escolhe
  entre o serviço e o listener.
- `SyncResumeReceiver`: `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` → `resumeIfEnabled()`.
- `SyncTileService`: bloco nas Configurações rápidas.

**`SyncEngine.pollOnce`** (`:core/sync/SyncEngine.kt`), equivalente ao Poller:

1. Na primeira vez, carrega o `lastRenderedTrackId` (`FileRenderMemory`) e restaura o índice.
2. Pausado → `Paused`.
3. `redraw()` pendente (mudança de visual ou do tamanho da tela) → redesenha o que está na tela, sem API.
4. MediaSession tocando e opção ligada → `fromLocalSession`, o caminho híbrido. Uma faixa
   desconhecida faz uma chamada. Se a API ainda descreve outra faixa, a tracklist é buscada na
   hora para tentar achar esta. Se não achar, `Unknown` → backoff curto limitado ao intervalo.
5. Modo local sem nada tocando → `Idle`, sem chamada.
6. Senão, consulta a API pelo `ApiThrottle` (espaçamento mínimo de 5 s) → `decide` → render.
7. Ao fim do ciclo, grava o índice se mudou (fora do caminho crítico do wallpaper).

Estados publicados (`SyncStatus`, um `StateFlow`): `Starting`, `Paused`, `Idle`, `Showing`,
`RenderFailed`, `Retrying`, `Failing`, `SignedOut`, `Blocked`. `SignedOut` e `Blocked` encerram
o loop, e o serviço mostra o motivo numa notificação.

**Render e aplicação:**

1. `WallpaperComposer` calcula `baseCacheKey` e consulta o `AlbumBaseCache`
   (`cacheDir/album_bases`). Se a base não existe, baixa a arte e desenha a base.
   `drawFinal` desenha o texto.
2. `WallpaperUpdater` mede a tela a cada chamada (rotação, outro display). Celular (< 600dp)
   ganha canvas em retrato; tablet e dobrável aberto, um quadrado do lado maior com o conteúdo
   no quadrado central, visível nas duas orientações (`canvasSpec`):
   - `smoothTransition` desligado → `WallpaperApplier` → `WallpaperManager.setBitmap`, na tela
     inicial e opcionalmente na de bloqueio;
   - ligado → publica o bitmap em `LiveWallpaperFrames`, que o `LiveWallpaperService` mostra
     com crossfade de 300 ms. Guarda um quadro por forma de tela (`ScreenFrames`, até 2, todos
     do mesmo conteúdo: faixa + settings visuais, `frameContent`). O serviço mostra o de
     proporção mais próxima da superfície, então fechar o dobrável troca na hora, sem esticar
     o desenho da outra tela. Conteúdo novo descarta os outros quadros. A tela de bloqueio
     continua estática. Enquanto o live wallpaper não é escolhido, a imagem também é aplicada
     como estática.
3. **Troca de tela (dobrável aberto/fechado, tamanho de exibição/zoom):** o `AppContainer` coleta
   `canvasSpecs().resizes()` (`DeviceScreen.kt`). Gatilhos: `ComponentCallbacks` na window
   context e, **só abaixo do Android 12**, um `DisplayListener` do display principal (antes
   do 12 a window context não recebe configuração; do 12 em diante o listener dispararia a
   cada troca de taxa de atualização). No Android 11 o `sw` sai dos bounds da janela, não
   dos resources, que ficam presos à tela da criação. `resizes()` (`CanvasResizes.kt`) compara
   `(canvasWidth, canvasHeight, density)`; se algum mudou, chama `SyncEngine.redraw()`:
   redesenha a faixa na tela sem chamada à API. A densidade conta porque o zoom muda a escala
   dp sem mudar os pixels, e o texto tem piso em dp (`WallpaperLayout`:
   `maxOf(short × fração, MIN_*_DP × density)`). Quando o piso não vence, o redesenho repete a
   mesma imagem (custo aceito). Rotação de celular não conta (canvas e densidade iguais). Com o sync parado, o redesenho fica pendente até
   ele voltar. Após a morte do processo, a faixa na tela é esquecida e só a próxima faixa
   redesenha.
4. `WallpaperBlockedException` (aparelho sem wallpaper ou política proibindo) para o sync.
   `TrackNotDrawableException` (álbum sem imagem) marca a faixa como impossível de desenhar,
   para não tentar de novo a cada consulta.

### 2.4 Android: CI e atualização no app

```
push em main   → release.yml → APK release assinado → GitHub Release r<versionCode> (latest)
push em branch → debug.yml   → APK debug assinado   → pré-release debug-<slug> (substituído a cada push)
                                   cada release leva o APK + update.json
app: seção "Atualizações" → UpdateClient (API do GitHub, sem token) → update.json
     → versionCode maior? → baixa → confere SHA-256 → ApkInstaller (sessão do PackageInstaller)
```

| Componente | Papel |
|---|---|
| `UpdateController` (`:core`) | Lógica da seção: uma ação por vez. Fases `Checking` → `Downloading` → `Installing`, ou `UpToDate`, `Older`, `NoBuild`, `WrongPackage`, `NeedsPermission`, `Failed`. `Installing` não trava o botão: o sistema pode nunca responder (confirmação bloqueada em segundo plano ou fechada com Home). A lista de branches é buscada uma vez por processo; só "Atualizar lista" busca de novo, então reabrir ou girar a tela não gasta chamada |
| `UpdateClient` (`:core`) | Release: `releases/latest`. Debug: lista as pré-releases `debug-*` (páginas de 100, até 5; cada página é uma chamada). Ao verificar, relê o release da branch pela tag (`releases/tags/{tag}`) antes do `update.json`, porque a CI substitui o release a cada push e o da lista em cache pode apontar para o APK do build anterior. Baixa para `cacheDir/updates` |
| `UpdateInfo` / `GithubReleases` (`:core`) | Parse e validação do `update.json` (SHA-256 com 64 hex, `versionCode` > 0, `apk` como nome de arquivo simples, porque vira caminho na pasta de download) e da resposta do GitHub |
| `ApkInstaller` (`:app`) | Abandona as sessões anteriores do app (e a cópia do APK delas), abre uma nova no `PackageInstaller`, grava o APK e faz o commit. Pede "instalar apps desconhecidos" |
| `InstallResultReceiver` (`:app`, não exportado) | Abre a confirmação do sistema e guarda o intent em `AppContainer.pendingInstallConfirmation`; a seção mostra "Confirmar instalação" para reabri-la (sessão expirada → erro na tela). Limpa o intent e devolve o resultado ao controller |

O canal é o do build instalado: o release só olha o release, e o debug escolhe uma branch.
Ele vem pré-selecionado com a própria branch (`BuildConfig.GIT_BRANCH`) enquanto ela tiver
build. Um `update.json` de outro pacote é recusado (`WrongPackage`).

### 2.5 Android: compartilhar letra

Só no Android (fora da regra de paridade). O botão "Compartilhar letra" fica no cartão de
status da tela principal. A notificação não tem esse botão.

**Quando o botão aparece (#18):** `shareTarget(status, onScreen)` (`:core`,
`lyrics/ShareTarget.kt`) devolve a faixa a que o botão se refere, ou `null` (sem botão).
`MainScreen` usa esse valor para mostrar o botão e para abrir a tela.

| Status | Faixa do botão |
|---|---|
| `Showing` | A que está tocando |
| `Idle` (música pausada) | A que o wallpaper ainda mostra: `SyncEngine.onScreen` |
| `Starting`, sync pausado, falha de desenho, deslogado, bloqueado | Nenhuma: o wallpaper não está sendo mantido |
| `Retrying`, `Failing` | Nenhuma: o wallpaper existe, mas o botão some durante o problema de rede, como antes |

`shareTarget` devolve `null` para todo status que não seja `Showing` ou `Idle`.
`SyncEngine.onScreen` é a última faixa em `Showing` (inclusive quando a faixa já estava no
wallpaper), só em memória. Nada novo vai para o disco.

```
tela principal visível ─► LyricsPrefetch.follow ─► LyricsSlot (1 resposta, em memória)
                                                      │  LrclibClient (lrclib.net/api)
botão ─► LyricsShareScreen ─► ShareScreenModel ─► LyricsPrefetch.watch(faixa)
             escolha de versos ─► ShareLayout (:core) ─► ShareRenderer ─► prévia
             "Compartilhar"   ─► ShareFiles (1 JPEG em cache/share) ─► FileProvider ─► share sheet
```

| Componente | Papel |
|---|---|
| `LrclibClient` (`:core`) | `/get` quando álbum **e** duração são conhecidos; senão (ou sem resultado) `/search` ranqueado como o spotifast. Pede pelo **primeiro artista** da lista da API Web; no modo local (MediaSession) só existe o texto já juntado. 404/400 = sem letra; outras falhas = "sem rede". Não passa pelo `ApiThrottle` (429 e backoff são do Spotify). `callTimeout` de 20 s |
| Ranking do `/search` | Título precisa bater (comparação solta). Artista certo +1000. Duração com diferença > 30 s descarta; senão +(30 − diferença) × 10. Sincronizada +200, só texto +50. Empate: vence a primeira |
| `LyricsSlot` (`:core`) | Uma resposta em memória, "não encontrada" incluída. Descartada na troca de faixa. Nada vai para o disco |
| `LyricsPrefetch` (`:core`) | `follow`: roda em `repeatOnLifecycle(STARTED)` na `MainScreen`, busca ao abrir e a cada troca de faixa. O serviço de sync **nunca** pede letra. `watch(faixa)`: a tela de compartilhar pede a da própria faixa, sem custo se já está guardada ou em andamento |
| `ShareLayout`, `VerseSelection` (`:core`) | Geometria da imagem e escolha dos versos |
| `WallpaperComposer.obtainBase` | Entrega a base do wallpaper **do cache** (normalmente sem download). Depois de mudar zoom ou estilo, desenha e guarda a base igual ao wallpaper |
| `ShareRenderer` (`:app`) | Cartão de vidro exatamente sobre a arte desenhada (sem a moldura), miniatura recortada da base, cabeçalho, versos e o texto inferior do próprio wallpaper |
| `ShareFiles` (`:app`) | No máximo um arquivo, JPEG q95. Apagado quando o próximo é gravado e quando o app abre. Nunca usa o cache de álbuns |
| `LyricsShareViewModel` / `ShareScreenModel` (`:app`) | Estado no escopo da activity: rotação, dobra e zoom mantêm letra, seleção e imagem. No modo paisagem, as partes ficam lado a lado |

**Enquadramento da imagem:** recorte 9:16 centrado no bloco arte → texto
(`art_offset_y_pct` nunca corta o cartão). Uma tela já 9:16 não é recortada. Canvas
quadrado (tablet, dobrável aberto) gera imagem quadrada. O texto do cartão escala com o
cartão, não com a densidade; os pisos são frações da largura da imagem.

**Isolamento:** compartilhar nunca toca no sync, no `ScreenFrames`, no `frameContent` nem no
`baseCacheKey`. Os campos novos de `NowPlaying` ficam fora do `frameContent`, e o
`BASE_RENDER_VERSION` não mudou.

**Medido no A71 (release):** base do cache 75–119 ms (sem cache, desenhada: ~1,3 s);
desenho 41–51 ms com a base já pronta; JPEG de 161–198 KB em 31–42 ms; do botão à primeira
prévia ~370–540 ms com a letra já buscada (inclui um debounce de 250 ms).

### 2.6 Desktop: widget de letra sincronizada

Só no desktop (fora da regra de paridade, confirmado em 2026-09-28). É uma janela sem borda
e sem botão na barra de tarefas, com a letra da faixa tocando: a linha atual fica destacada
e a coluna rola até ela em 350 ms. Fica **desligado por padrão** e é ligado na janela de
configurações (*Lyrics widget › Show*, §2.2).

```
smtc-watcher ── get_snapshot() + get_timeline() ──┐
                                                  ▼   QTimer 100 ms (só com o widget ligado)
Qt (principal): QtHost._tick ─► LyricsWidgetController.tick ─► View ─► LyricsWindow
                                    │ FetchRequest                        ▲ set_background
                                    ▼                                     │
               thread "lyrics" ─► LyricsSlot ─► LrclibClient ─► lyrics_ready / lyrics_failed (sinais)
poller: render ─► TintHolder.set(cor do álbum) ──────────────────────────┘
janela de configurações: Show / Lock / On top / Reset ─► QtHost (thread do Qt)
```

| Componente | Papel |
|---|---|
| `QtHost` (`lyrics_widget/qt_host.py`) | Dono do `QApplication`. Só a thread do Qt toca em widgets: a bandeja e a busca falam com ela por sinais do `_Bridge` (conexão enfileirada). Implementa `LyricsWidgetControls`, o protocolo que a janela de configurações usa (§2.2); `open_settings()` cria e mostra a janela. Grava as settings do widget pela thread do Qt |
| `LyricsWindow` | Fundo translúcido arredondado (alfa 200). A fonte cresce com a largura (13–34 px). Destravado, arrastar move a janela e arrastar a 8 px da borda redimensiona; a posição é gravada 600 ms depois de soltar |
| `LyricsWidgetController` (`lyrics_widget/controller.py`) | Puro (sem Qt, sem rede). A cada tick recebe o snapshot e a timeline e devolve um `View` e, se preciso, um `FetchRequest`. Espera até 2 s pela duração da faixa antes de buscar (ela permite o `/get` exato). Falha de rede → tenta de novo a cada 30 s na mesma faixa. Resposta de outra faixa é descartada |
| `SongClock` (`lyrics/song_clock.py`) | Posição atual a partir da última `TimelineSample`, extrapolada pelo `time.monotonic()` × `rate` enquanto toca e congelada com a música pausada. Cada amostra substitui a anterior. Um seek corrige na hora: o Spotify o avisa pelo SMTC na mesma hora (`timeline_properties_changed`), sem esperar a atualização periódica (verificado pelo usuário em 2026-09-29) |
| `TimelineSample` (`os_integration/smtc.py`) | Posição do SMTC levada ao instante da leitura (`position + idade × rate`) e presa ao relógio monotônico. Um carimbo com mais de 12 h (DateTime não preenchido = ano 1601) ou do futuro não soma nada |
| `LyricsSlot` (`lyrics/slot.py`) | Port do `LyricsSlot` do Android. Uma resposta em memória, com "não encontrada" e "instrumental" incluídas, descartada na troca de faixa. Uma busca por vez; um pedido da mesma faixa espera a busca em andamento |
| `LrclibClient` (`lyrics/lrclib.py`) | Port do cliente do Android (§2.5): `/get` com álbum e duração, senão `/search` com o mesmo ranking. Letra sincronizada (LRC `[mm:ss.xx]`) primeiro; sem ela, texto simples. **Diferente do Android:** se o `/get` acha só texto simples, roda também o `/search`, e uma versão sincronizada da mesma gravação (mesmo ranking, diferença ≤ 30 s) vence. Se nada sincronizado serve, ou se o `/search` falha na rede, fica o texto do `/get`. `/get` sincronizado ou instrumental encerra na hora, sem busca. Timeout de 10 s para conectar e 10 s para ler. Fora do `ApiThrottle` do Spotify |
| `widget_background` (`lyrics_widget/colors.py`) | Cor do álbum (`TintHolder`, vinda do render, §2.2) escurecida em passos de 5% até o texto branco ter contraste de 4,5:1. Sem cor ainda: `(18, 18, 18)` |
| `geometry.py`, `window_layer.py`, `fullscreen.py` | Posição padrão (canto inferior direito da área útil do monitor principal, 24 px das bordas, 420×190), restauração da posição salva, camada da janela (`SetWindowPos`) e detecção de tela cheia (`SHQueryUserNotificationState`) |

**Estados (`Phase`):**

| Estado | Quando | Janela |
|---|---|---|
| `HIDDEN` | Nada tocando (sem snapshot ou sem título) | Escondida |
| `LOADING` | Faixa nova, busca pendente | "Looking for lyrics…" |
| `SYNCED` | Letra com tempos | Linha atual destacada (alfa 255, as outras 110), rolagem animada; antes da primeira linha, ela espera na âncora (40% da altura) |
| `TEXT` | Só texto (o LRCLIB não tem versão sincronizada; comum em música brasileira) | Rola junto com a faixa (posição ÷ duração), com um selo "Not synced" no canto superior direito (`View.badge`) |
| `NO_LYRICS` / `INSTRUMENTAL` | LRCLIB não tem / marcada como instrumental | Mensagem |
| `UNAVAILABLE` | LRCLIB inacessível | Mensagem; nova tentativa em 30 s |
| `NO_SOURCE` | `use_smtc` desligado | "Turn on use_smtc to follow the song" |

Com a música pausada, a posição congela e a letra fica parada na linha atual.

**Camada e travamento:**

- Padrão: **atrás das janelas** (`WindowStaysOnBottomHint` + `SetWindowPos(HWND_BOTTOM)`).
  Some com Win+D, como os ícones da área de trabalho.
- *Always on top*: acima de tudo (`HWND_TOPMOST`). Nesse modo, a janela se esconde enquanto
  um app em tela cheia, um jogo ou uma apresentação ocupa a tela (verificado uma vez por
  segundo).
- *Lock position*: `WindowTransparentForInput`, os cliques passam pela janela.
- A posição é gravada **por monitor** (`screen_key` = nome + dados do EDID). Ela é descartada,
  e a janela volta ao canto padrão, se o monitor sumir ou se menos da metade dela ficar
  dentro dele. Quando é mantida, é puxada para dentro do monitor.

**Invariantes:**

- A letra nunca vai para o disco, o log, fixtures ou commits. O log registra só o tipo do
  resultado (`SyncedLyrics`, `NotFound`...).
- Buscas só acontecem com o widget ligado: desligado, o timer para e não há tick.
- O LRCLIB fica fora do `ApiThrottle` do Spotify. Nenhuma chamada nova à API Web do Spotify.
- O Poller, `base_cache_key` e o desenho do wallpaper não mudaram.

---

## 3. Esquema de dados

### 3.1 Desktop: arquivos locais

| Caminho | Conteúdo | Escrita |
|---|---|---|
| `%APPDATA%\SpotifyWallpaperEngine\config.json` | `Settings` (dataclass → JSON) | Criado com os padrões. Bandeja, assistente, janela de configurações e widget de letra (thread do Qt) regravam; `save_settings` grava um de cada vez (`_SAVE_LOCK`) |
| `%LOCALAPPDATA%\...\track_index.json` | Índice faixa → álbum | Atômica (`.tmp` + `os.replace`), só quando muda |
| `%LOCALAPPDATA%\...\cache\album_bases\<key>.png` | Base por álbum (sem texto) | Uma vez por chave, PNG `compress_level=1` |
| `%LOCALAPPDATA%\...\cache\album_art\<id>.art`, `<id>.colors.json` | Capa original do álbum e suas cores (`{"version": 1, "dominant": [r,g,b], "accents": [[r,g,b],...]}`) (#19) | A capa, na primeira vez que o álbum é desenhado; as cores, quando calculadas. Teto de 60 MB, LRU por mtime, nunca apaga o álbum recém-gravado |
| `%LOCALAPPDATA%\...\cache\wallpaper_{a,b}.png` | Imagem final aplicada | Alternando, a cada faixa |
| `%LOCALAPPDATA%\...\lockscreen_pending.json` | `{"path": ...}` para a task elevada | A cada render com `sync_lock_screen` |
| Windows Credential Manager (`SpotifyWallpaperEngine` / `default`) | Token OAuth do spotipy (JSON) | `KeyringCacheHandler` |
| `HKCU\...\Run\SpotifyWallpaperEngine` | Autostart (`pythonw main.py`) | `--install-autostart` ou assistente |
| `HKCU\Control Panel\Desktop` | `WallpaperStyle=10`, `TileWallpaper=0` | A cada troca |

### 3.2 Android: arquivos locais

| Caminho | Conteúdo |
|---|---|
| `files/datastore/settings.json` | `Settings` (kotlinx.serialization, via DataStore) |
| `files/spotify_auth_state.bin` | Estado do AppAuth, criptografado com AES-256-GCM (Tink, chave presa ao Keystore) |
| `files/sync_state/last_track.txt` | Última faixa desenhada (sobrevive à morte do processo) |
| `files/sync_state/track_index.json` | Índice faixa → álbum (DataStore) |
| `files/live_wallpaper/frame.bin` | Último quadro em pixels crus (sem PNG, por custo). Só o mais novo; o quadro da outra tela do dobrável fica só em memória |
| `cacheDir/album_bases/` | Bases por álbum, LRU com teto de 150 MB |
| `cacheDir/album_art/<id>.art`, `<id>.colors` | Capa original do álbum e suas cores (#19). LRU por mtime com teto de 60 MB, nunca apaga o que acabou de gravar. Trocar a capa de um álbum apaga as cores antigas |
| `cacheDir/updates/` | APK de atualização baixado (só um: a pasta é esvaziada antes; `.part` até o SHA-256 conferir) |
| `cacheDir/share/letra-<ms>.jpg` | Imagem de compartilhar letra (só uma; nome novo a cada vez). Única pasta servida pelo `FileProvider` (`${applicationId}.share`, `res/xml/share_paths.xml`, não exportado, leitura concedida só ao app escolhido) |

Backup automático do Android desligado (`allowBackup=false`). Letras nunca vão para o disco.

**Cache da arte original (#19; desktop `AlbumArtCache`, Android `AlbumArtStore` em `:core`):**

- Um arquivo de capa e um de cores por álbum, nomeados pelo id do álbum (a capa de um álbum
  não muda). Vale para a capa original, antes de qualquer composição; a chave da base
  (§3.4) não mudou.
- `AlbumColors` calcula a cor dominante e os acentos **uma vez cada**, na primeira vez que
  são pedidos; os acentos só quando `mesh` ou `glow` precisam deles. Cada valor novo é
  regravado na hora. Cores com formato inválido são ignoradas e recalculadas.
- Melhor esforço: arquivo ausente, ilegível ou danificado = baixar de novo. Uma capa guardada
  que não decodifica como imagem é descartada (com as cores) e baixada **uma** vez; se a nova
  também falha, o erro segue o caminho normal de falha de desenho.
- Uma cor dominante de "arte ilegível" (fallback `(30, 30, 30)`) nunca é guardada.

**Chaves de assinatura (fora do repositório):**

| Arquivo | Uso |
|---|---|
| `~/.keystores/wallpaper-changer.jks` | Chave de release. Cópia mestre; a CI recebe uma cópia pelos secrets do Environment `release` |
| `~/.keystores/wallpaper-changer-debug.jks` | Chave de debug compartilhada entre o PC e a CI, via `debugStoreFile` |
| `android/keystore.properties` | `storeFile`, `storePassword`, `keyAlias`, `keyPassword` e `debugStoreFile` (opcional). Sem `debugStoreFile`, o debug usa o `~/.android/debug.keystore` da máquina |

**`update.json`** (anexado a cada release pela CI): `channel`, `package`, `versionCode`,
`versionName`, `commit`, `branch` (nome exato), `apk` (nome do asset), `sha256`, `notes`,
`builtAt`. Os campos de versão são lidos do próprio APK (`aapt2`).

### 3.3 Configuração

As chaves são **as mesmas nas duas plataformas** (nomes do desktop em `snake_case`). Cada lado
ignora as chaves que não usa. Valor inválido volta ao padrão só naquele campo.

| Chave | Padrão | Plataforma | Afeta a base? |
|---|---|---|---|
| `client_id` | `""` | ambas | — |
| `redirect_uri`, `scope` | loopback `127.0.0.1:8888` | desktop | — |
| `poll_interval_seconds` | 4.0 | desktop (tick local) | — |
| `use_smtc` / `use_media_session` | true / false | desktop / Android | — |
| `fallback_poll_interval_seconds` | 25.0 (Android: 10–300) | ambas | — |
| `art_size_pct` | 0.68 | ambas | sim |
| `corner_radius`, `shadow_blur_radius` | 16, 24 | ambas | sim |
| `show_track_info` | true | ambas | só no Android (reserva espaço no layout) |
| `background_style` | `solid` \| `mesh` \| `blur` | ambas | sim |
| `art_glow` | false | ambas | sim |
| `art_frame` | `none` \| `single` \| `double` (legado `true` → `double`) | ambas | sim |
| `blur_strength` | 26 (0–100) | ambas | sim, só com `blur` |
| `text_card` | `none` \| `glass` | ambas | não (desenhado por faixa) |
| `smooth_transition` | false | ambas (mecanismos diferentes) | não |
| `sync_lock_screen` | false | ambas | não |
| `art_offset_y_pct` | 0.0 | Android | sim |
| `paused`, `sync_enabled`, `onboarding_done`, `local_only` | false | Android | não |
| `fallback_resolution`, `log_level` | `[1920,1080]`, `INFO` | desktop | — |
| `lyrics_widget_enabled`, `lyrics_widget_locked`, `lyrics_widget_on_top` | false | desktop | — |
| `lyrics_widget_geometry` | `null` (canto padrão) \| `{"monitor": str, "rect": [x, y, w, h]}` (inteiros, w e h > 0) | desktop | — |

As chaves `lyrics_widget_*` são só do desktop. O Android as ignora (`ignoreUnknownKeys = true`).

### 3.4 Chave do cache de bases

```
<album_id>_<W>x<H>_<sha256(inputs)[:12]>         (desktop)
inputs = BASE_RENDER_VERSION | W | H | art_size_pct | corner_radius | shadow_blur_radius
         | background_style | art_glow | art_frame | blur_strength (só com blur)
```

- O Android inclui também a safe area, a densidade, `art_offset_y_pct` e `show_track_info`.
- `BASE_RENDER_VERSION`: desktop **2**, Android **3** (numerações independentes). Incrementar
  quando o desenho da base mudar.
- Setting nova que muda o desenho (Android) precisa entrar em `baseCacheKey` (se muda a base)
  **e** em `frameContent`; senão, imagens velhas são reaproveitadas. O KDoc de `Settings`
  avisa.
- Um id que não seja base62 é trocado por `h` + hash, para não formar caminho.
- Eviction: LRU por mtime até 150 MB, sem nunca apagar a base em uso. No desktop, arquivos no
  formato antigo `<album_id>.png` são apagados.

### 3.5 Índice faixa → álbum

- Chave: `artista.strip().lower() + "::" + título.strip().lower()`.
- Valor: `(album_id, art_url)`.
- LRU com 5000 entradas (algumas centenas de álbuns, poucas centenas de KB).
- Arquivo: `{"version": 1, "tracks": [[key, album_id, art_url], ...]}`, do menos para o mais
  recente.
- Versão diferente ou arquivo ilegível → começa vazio. Perder o índice custa só uma chamada
  por álbum.
- Álbuns **não verificados** (título divergente depois de 3 tentativas) nunca são gravados.

### 3.6 Modelos em memória

| Tipo | Campos |
|---|---|
| `NowPlaying` | `is_playing`, `track_id`, `album_id`, `art_url`, `track_name`, `artist_name`. No Android também `albumName`, `durationMs` e `artists` (nomes um a um), que só alimentam a busca de letra: podem faltar e ficam fora do `frameContent` |
| `LyricsQuery` / `Lyrics` (Android) | Título, artista(s), álbum, duração → `Text(linhas)`, `Instrumental` ou `NotFound` |
| `LyricsQuery` / `Lyrics` (desktop) | Os mesmos campos → `SyncedLyrics(TimedLine(time_ms, text)...)`, `TextLyrics(linhas)`, `Instrumental` ou `NotFound`. Falha de rede = `LyricsUnavailableError` |
| `TimelineSample` (desktop) | `track_key`, `position_ms`, `observed_at` (monotônico), `duration_ms`, `is_playing`, `rate`, `stamp` (último update do SMTC em epoch, ou `None`) |
| `View` (widget) | `phase`, `lines`, `current` (índice da linha atual, `SYNCED`), `progress` (0..1, `TEXT`) |
| `SmtcNowPlaying` / `LocalTrack` | `title`, `artist`, (`album_*`), `is_playing` |
| `AppStatus` (desktop) | `IDLE`, `RUNNING`, `PAUSED`, `ERROR` |

---

## 4. Decisões técnicas

| Decisão | Motivo |
|---|---|
| Fonte híbrida: local para detectar, API para resolver | A fonte local é grátis e instantânea mas não tem id de catálogo nem arte em alta. A API tem as duas coisas mas atrasa e sofre rate limit |
| Nunca desenhar a miniatura local | 300×300 fica ruim em tela cheia. Melhor manter o wallpaper antigo que mostrar arte errada |
| Tracklist depois do render | O wallpaper não espera por uma chamada que só acelera as outras faixas |
| 5 s entre consultas de álbum novo (SMTC) | Rápido o bastante para pular entre álbuns e bem abaixo do limite de 429 |
| Até 3 tentativas com título divergente | A API atrasa em relação ao app local. Depois disso, nunca desenhar seria pior que arriscar |
| Nenhuma chamada com a tela bloqueada/desligada | Ninguém vê o wallpaper; poupa cota e bateria |
| Android: FGS `specialUse` | Nenhum tipo padrão cobre "seguir a reprodução de outro app". `mediaPlayback` exigiria que o app tocasse mídia |
| Android: MediaSession opcional, nunca a única fonte | Medido no A71: com a reprodução em outro aparelho, a sessão local mostra faixa velha. Além disso, exige acesso a notificações |
| Port fiel do ColorThief em Kotlin | A `Palette` do Android dava cores diferentes das do PC. Paridade visual é requisito |
| Mesh em OKLab, grade pequena, dither Bayer | Mistura sem cinza; custo de miniatura em tela cheia. Arredondar antes de ampliar cria faixas, e ruído aleatório custa 10× mais e incha o PNG |
| Blur e halos numa cópia reduzida | Mesmo resultado visual depois do desfoque, com cerca de metade do custo |
| Cache da base por álbum, texto por faixa | Trocar de faixa custa cerca de 95 ms (desktop 1080p, `solid`) e 7 ms (A71), contra 0,5–2,4 s de um álbum novo |
| Chave de cache pelas *entradas* do layout | O layout depende do tamanho da arte, que só se conhece depois do download que o cache existe para evitar |
| PNG `compress_level=1` | A saída é regravada a cada faixa. Com o dither do mesh, o nível 6 levava 0,2–0,7 s |
| Dois arquivos de saída alternados | O Explorer/DWM não segura lock no arquivo que está sendo escrito |
| Snapshot das settings no render (desktop) | A bandeja e a janela de configurações mudam settings em outra thread. Sem cópia, a chave e o desenho podiam usar estilos diferentes e envenenar o cache |
| Fade no Windows via `IActiveDesktop` + mensagem `0x052C` ao Progman | Único caminho que anima a troca. Sem `AD_APPLY_FORCE`, que falha no Windows 10. Depende das animações do sistema estarem ligadas |
| Fade no Android via live wallpaper próprio | `setBitmap` pisca preto a cada troca, comportamento do sistema. O quadro vai em pixels crus porque codificar PNG custa centenas de ms |
| Tela de bloqueio no Windows via Scheduled Task elevada | A chave `PersonalizationCSP` fica em HKLM. A task pede UAC uma vez só |
| Task apagada ao desligar a opção, e recriada se aponta para outra pasta | Uma task esquecida roda elevada no logon, e uma apontando para outro clone executaria aquele código como administrador |
| Desktop atualiza por `git` no clone do app, só fast-forward na `main` | Sem instalador nem release no PC: o código que roda é o da `main`. Nunca faz merge nem push, e não mexe em mudança local |
| `pip` antes do fast-forward, com o `requirements.txt` lido do git | Se o pip falha, o código fica como estava e não há o que desfazer. A cópia vai para `%TEMP%`, então o arquivo não pode ter linhas relativas (`-r`, `-e .`) |
| Só reinicia se mudou `main.py`, `requirements.txt` ou `src/` | Mudança só em `android/` ou docs entra sem derrubar o app |
| Instância única por mutex nomeado (`Local\`) | Duas instâncias brigariam pelo wallpaper e pelo `config.json`. `Local\` mantém um motor por sessão de logon |
| Tokens: Credential Manager / Keystore + Tink | Nunca em texto puro no disco. OAuth PKCE, sem client secret |
| Arte só de `https` em `scdn.co`/`spotifycdn.com`, conferido também depois de redirects | Os bytes vão direto ao decodificador de imagem. Uma resposta da API forjada não pode apontá-lo para outro host. Nas 114 capas do índice do desktop, todas vinham de `i.scdn.co`. URL recusada é tratada como falha de download comum |
| Client ID por usuário | Desde 15/05/2025 o Spotify só dá cota estendida a empresas grandes. Cada pessoa usa o próprio app em modo desenvolvimento (Premium, até 5 contas) |
| Android: `targetSdk 36` com `compileSdk 37` | O AndroidX exige compileSdk 37. O target ficou em 36 de propósito (ver `app/lint.xml`) |
| R8 desligado | AppAuth, Tink e a serialização precisariam de regras próprias. A build testada no aparelho é a sem R8 |
| JaCoCo em vez de Kover | Kover 0.9.1 falha com Kotlin 2.4.20 |
| Redesenhar ao mudar o tamanho da tela ou a densidade, não ao rotacionar | O wallpaper só é desenhado na troca de faixa; sem isso, abrir o dobrável mostrava o desenho da tela externa cortado, e fechar mostrava o quadrado da interna cortado nas laterais. Mudar o zoom deixava o texto antigo até a próxima faixa (a chave do cache já incluía a densidade, então só a próxima saía certa) |
| Debug com sufixo `.debug` | Instala ao lado do release sem apagar login e configurações (as chaves de assinatura diferem) |
| `versionCode` = número de commits até o HEAD; clone raso é recusado | O mesmo commit tem o mesmo número no PC e na CI, e todo commit novo na `main` atualiza o app. Num clone raso a contagem voltaria para trás, então o build falha em vez de chutar. Sem git, vale 1 |
| Chave de release só no Environment `release` (deployment branch = `main`) | Código de outra branch (build script, `ci/*.sh`) nunca roda com a chave, diga o workflow o que disser. A chave vira arquivo no `$RUNNER_TEMP` e é apagada logo depois do assemble, mesmo com falha. `verify-apk-cert.sh` confere o SHA-256 do certificado antes de publicar: um APK com outra chave nunca atualizaria o instalado |
| Actions fixadas pelo SHA do commit (tag no comentário) | Uma tag movida não pode alcançar a chave de assinatura |
| Release não publica se já existe um `r<N>` maior | Reexecutar uma execução antiga não pode tornar um build mais velho o "latest" |
| Chave de debug compartilhada entre o PC e a CI | O APK de debug de um atualiza o do outro, sem desinstalar |
| Canais: release em `releases/latest`, debug com uma pré-release por branch | A `main` publica `r<versionCode>`, marcada como latest. Cada branch tem só o build mais novo em `debug-<slug>`. O slug perde a `/`, então o nome exato da branch vai no título e no `update.json` |
| Consultas ao GitHub sem token | Repositório público, e um token no app seria um segredo em todo celular. O limite de 60 por hora basta para um botão manual |
| SHA-256 conferido antes de instalar | O APK precisa ser o que o `update.json` descreve. Se não bater, o arquivo é apagado e nunca vai para o instalador |
| Instalação por sessão do `PackageInstaller` | `ACTION_INSTALL_PACKAGE` está obsoleto desde o Android 10. A sessão devolve um status por código, que a tela traduz |
| Letras do LRCLIB, com o ranking do spotifast | Sem conta nem chave. O ranking já é conhecido; artista e duração tornam improvável um acerto errado |
| Buscar letra só com a tela principal visível | Ninguém compartilha sem abrir o app. O sync em segundo plano não gasta rede nem bateria com letras, e a letra já está pronta quando o botão é tocado |
| Uma resposta em memória, nada em disco | Uma faixa por vez basta para compartilhar. Não guarda texto protegido no aparelho |
| Imagem feita da base em cache do wallpaper | Sem download novo no caso normal, e a imagem tem o mesmo visual do wallpaper. O compartilhamento não mexe em nada do sync |
| JPEG q95 em vez de PNG | 31–42 ms e ~180 KB no A71; o PNG custaria centenas de ms sem ganho visível no Stories/WhatsApp |
| `FileProvider` restrito a `cache/share`, um arquivo | O app escolhido só consegue ler aquela imagem. Não acumula arquivos |
| Estado num ViewModel no escopo da activity | Rotação, dobra e zoom recriam a tela sem perder letra, seleção nem imagem |
| Widget de letra em PySide6-Essentials (Qt) | Janela sem borda, translúcida por pixel, com DPI por monitor e animação. O Tkinter não faz isso bem. O pacote Essentials (QtCore/QtGui/QtWidgets, ~211 MB instalado) evita os Addons (WebEngine, 3D) que o widget não usa |
| Qt na thread principal, pystray numa thread própria | O Qt exige o `QApplication` na thread principal. No Windows o pystray processa as mensagens na thread que o chama, então pode sair dela. Os dois conversam só por sinais enfileirados |
| Camada da janela via `SetWindowPos` | O `WindowStaysOnTopHint` do Qt não chegava ao Windows numa janela já criada (PySide6 6.11.2, visto em 2026-09-28). Para sair do topo é preciso `HWND_NOTOPMOST` antes de `HWND_BOTTOM` |
| Atrás das janelas por padrão | Fica como parte da área de trabalho, sem cobrir o trabalho. "Always on top" é opcional e se esconde em tela cheia |
| Relógio da faixa pela timeline do SMTC, extrapolado localmente | O Spotify atualiza a timeline a cada ~4,5 s (medido em 2026-09-28), e na hora num seek. Entre as amostras, a posição anda pelo `time.monotonic()`, que ignora mudanças no relógio do sistema |
| Timeline de outra faixa conta a partir de 0 | Se a faixa muda e o carimbo da timeline não, a posição é da faixa anterior. Não medido se o Spotify faz isso; a guarda é barata |
| Controller e layout puros, sem Qt | Estados, pedidos de busca, posição e rolagem são testados sem janela |
| Cor do widget tirada da base do wallpaper já em memória | Custa 2–4 ms e não faz uma segunda quantização do ColorThief. O quarto inferior direito é onde o widget começa. Escurecer até 4,5:1 (WCAG AA) mantém o texto branco legível |
| Widget: `/get` só com texto ainda consulta o `/search` atrás de uma versão sincronizada | Texto sem tempos não acompanha a música, então vale uma chamada a mais. O compartilhar do Android continua com o texto do `/get`, porque não precisa de tempos. O selo "Not synced" deixa claro que rolar sem acompanhar é esperado, não um defeito |
| Buscar letra só com o widget ligado; uma resposta em memória | Mesma regra do Android: sem rede gasta à toa e sem texto protegido no disco |
| `_SAVE_LOCK` em `save_settings` | A bandeja, a janela de configurações e a thread do Qt gravam o `config.json`. Sem o lock, duas escritas podiam se misturar no arquivo |
| Janela de configurações no lugar do menu da bandeja (#17) | Um menu fecha a cada clique, então ajustar estilo, blur e glow em sequência era penoso. A janela fica aberta e o Qt já é dono da thread principal |
| `StyleActions` e `AppCommands` sem Qt | A lógica de estilo e as ações do app são testadas sem janela; a `SettingsWindow` só liga botões a elas |
| A janela relê o estado a cada 0,5 s, só visível | A bandeja, o widget de letra e o updater também mudam as settings e o estado. Ler de volta mostra o que realmente aconteceu (UAC recusado volta a desmarcado) em vez do que foi clicado, sem custo com a janela fechada |
| Bandeja só com Settings, Pause, Re-authenticate (em erro) e Exit | Ficam na bandeja o que se quer sem abrir janela e o que salva o usuário quando o app está em erro |
| SMTC aceita `spotify` e `spotifast` no id da sessão (#16) | O `spotifast.exe` é um cliente do Spotify cujo id não contém "spotify"; sem isso o widget de letra não via a faixa |
| Guardar a capa original e as cores por álbum, em disco (#19) | Mudar o estilo muda a chave da base e antes baixava a capa e rodava o ColorThief de novo. Desktop, capa sintética 640×640: 358 ms da dominante + 235 ms dos acentos poupados por troca de estilo, fora o download. **Android não medido no A71** |
| Acentos só quando `mesh`/`glow` pedem | O estilo `solid` só usa a dominante; a paleta de acentos custa ~235 ms (desktop) e não é usada |
| `BASE_RENDER_VERSION` e a chave de cache não mudam com o #19 | Os pixels da base são os mesmos: só muda de onde vêm a capa e as cores |
| "Compartilhar letra" também com a música pausada, pela faixa do wallpaper (#18) | Pausar vira `Idle`, mas o wallpaper continua mostrando a última faixa; a letra é a dessa faixa. Sync pausado, falha de desenho, deslogado e bloqueado ficam sem botão: o wallpaper não está sendo mantido |

---

## 5. Estado atual

### 5.1 Pronto (em `main`)

| PR | Entrega |
|---|---|
| #1 | Desktop: híbrido SMTC/API, pré-busca de tracklist, tela de bloqueio, bandeja, assistente de 6 passos |
| #2 | Android M0–M16: OAuth, render, cache, FGS, retomada após reboot/update, tela principal, bloco nas Configurações rápidas, onboarding, MediaSession opcional, modo local, APK assinado |
| #3 | Nas duas plataformas: estilos `mesh`/`blur`, glow, moldura de vidro, cartão de vidro, intensidade do blur, transição suave, cache de 150 MB com LRU, álbum novo em ~5 s, índice faixa → álbum persistido. Desktop: menu Style e Restart na bandeja, Force Sync redesenha até com a música pausada, sem janela de console piscando. Android: `build-apk.ps1` gera e instala o release |
| #4 | Documentação técnica (`PLANNING.md`) |
| #5 | Android: redesenho ao abrir ou fechar dobráveis, um quadro do live wallpaper por forma de tela |
| #6 | CI: canais release/debug no GitHub Releases, `versionCode` = contagem de commits, atualização no app |
| #7 | Android: redesenho ao mudar o tamanho de exibição (zoom/DPI), sem chamada à API. Validado no build de release |
| #9 | Android: "Compartilhar letra" (§2.5). Testado no A71 (Instagram Stories, WhatsApp, rotação durante a seleção) |
| #11 | Desktop: widget de letra sincronizada (§2.6). Seek, resolução, escala e redimensionamento testados pelo usuário em 2026-09-29 |
| #12 | Desktop: atualização pelo clone próprio na `main`, instância única e "Check for updates" na bandeja (§2.2) |
| #13 | Desktop: o toggle da tela de bloqueio apaga a task `SpotifyWallpaperEngine_LockScreen` ao desligar e a reaponta para esta pasta ao ligar; o assistente avisa quando a task continua ligada (§2.2) |
| #14 | Documentação: `PLANNING.md` reflete #11–#13 |
| #15 | Desktop: o menu da tela de bloqueio se atualiza quando o toggle termina (o menu era refeito antes de o UAC ser respondido) |
| #16 | Desktop: o SMTC reconhece o `spotifast.exe` além de `spotify`, então o widget de letra vê a faixa |
| — | Nas duas plataformas: arte baixada só de `https` em `scdn.co`/`spotifycdn.com` (§4) |
| #17 | Desktop: janela de configurações e bandeja enxuta (§2.2). Testes automatizados passam; o teste manual da janela pelo usuário não foi registrado |
| #18 | Android: "Compartilhar letra" também com a música pausada (§2.5). Testado no A71 (debug) |
| #19 | Desktop + Android: cache em disco da capa original e das cores por álbum (§3.1, §3.2). Desktop medido; Android testado à mão no A71 pelo usuário (troca de estilo sem esperar o download) e não medido |
| #20 | Documentação: `PLANNING.md` reflete #15–#19 |

### 5.2 Limitações conhecidas

- **Primeira faixa de um álbum novo no Android sem MediaSession:** até ~25 s. O atraso é da
  propagação do próprio Spotify para a API Web (medido: ~12 s em média, 24 s no pior caso).
- **Tela de bloqueio no Android** continua estática e pisca na troca, mesmo com a transição
  suave ligada.
- **Fade no Windows** só aparece com as animações do sistema ligadas. No PC do usuário elas
  estão desligadas. A opção fica em *Configurações › Facilidade de Acesso › Exibição ›
  Mostrar animações no Windows* (no Windows 11: *Acessibilidade › Efeitos visuais*), não em
  *Personalização*.
- **Tracklist:** só a primeira página (50 faixas). Box sets maiores resolvem o resto faixa a
  faixa.
- **Desktop:** só o monitor primário. Não há limite de ampliação da arte (a 4K, 640 px viram
  cerca de 1470 px).
- **Dobráveis (validado em aparelho real):** a primeira abertura com um álbum novo baixa
  a arte de novo (a base do outro tamanho ainda não existe); até o redesenho chegar, o live
  wallpaper mostra o quadro da outra tela cortado. Com a tela de bloqueio sincronizada, ela é
  reaplicada a cada abrir/fechar e pisca. Não se sabe se a One UI guarda wallpapers
  separados por tela nem se aceita `setBitmap` de terceiros nas duas.
- **Atualização no app (debug):** trocar para uma branch com `versionCode` menor que o
  instalado (menos commits) exige desinstalar: o Android recusa o downgrade, e a tela mostra
  `Older`. A lista de branches cobre as 500 releases mais novas (5 páginas), e cada push na
  `main` cria uma release. O Play Protect pede
  para verificar os builds de debug antes de instalar.
- **`versionCode` por contagem de commits** só cresce sempre enquanto a `main` recebe merge
  commits. Um squash ou rebase-merge de uma branch longa pode deixar o release da CI com
  `versionCode` menor que o de um build local daquela branch.
- **Rotação da pré-release de debug** (apaga e recria): a branch fica sem build por alguns
  segundos e, se a criação falhar, até o próximo push. Limitação aceita.
- **`debug-cleanup.yml`** (apaga as pré-releases de branches removidas) só dispara depois de
  estar na `main`: eventos `delete` e `schedule` rodam o workflow da branch padrão.
- **Celular deitado:** o sistema mostra o retrato cortado e o texto some. Decisão do usuário:
  manter.
- **DPI muito baixo** (densidade ≤ ~1,8 numa tela de 1080 px) deixa `sw` ≥ 600dp: o celular
  vira "tablet" e ganha canvas quadrado. Não medido; medir no A71.
- **Zoom no Android < 12:** a densidade vem dos resources da window context, que podem ficar
  presos à configuração da criação. Não tratado.
- **Cache da arte original (#19):** o custo no Android não foi medido no A71. Os 5 testes
  instrumentados novos de `WallpaperComposerTest` ainda não foram executados no aparelho
  (só compilam); precisam do celular desbloqueado e de `leaveApksInstalledAfterRun`. O cache só evita download e cálculo de cor; a base do estilo novo ainda
  é composta (0,5–2,4 s).
- **Compartilhar letra (#9):**
  - (#18) o botão aparece com a música pausada, pela faixa do wallpaper. **Limite:**
    a faixa vem da memória; depois de reiniciar o app com a música já pausada não há
    `onScreen`, e o botão volta quando uma faixa voltar a tocar (`Showing`);
  - o LRCLIB é mantido pela comunidade: a letra pode faltar ou estar errada. A comparação
    solta aceita trechos contidos ("love" em "lovesong"), mantida pela paridade com o
    spotifast;
  - com a arte pequena, o cartão também fica pequeno: o piso da fonte e a seleção ficam
    limitados ao que cabe (a tela abre no primeiro verso que cabe sozinho);
  - a busca da própria tela de compartilhar roda no `viewModelScope`, não sob `STARTED`: se
    o usuário sair, ela pode continuar até o `callTimeout` de 20 s;
  - bitmaps de prévias antigas ficam para o GC de propósito (reciclar um que o Compose
    ainda pode estar desenhando é mais arriscado);
  - `LyricsPrefetch.state` não tem mais leitor em produção (a tela usa `watch()`). Mantido.
- **Atualização do desktop (#12):**
  - exige o app rodando de um clone git na `main`, sem mudança local nem commits locais; fora
    disso o item mostra o motivo e não faz nada;
  - a busca usa o `origin` do clone, sem token: sem rede ou sem acesso ao repositório, a falha
    aparece no rótulo do item;
  - pacotes que o pip já atualizou ficam atualizados se o fast-forward falhar depois. Os
    requisitos usam `>=`, então o código antigo segue funcionando com eles.
- **Tela de bloqueio no desktop (#13):** desligar a opção com o UAC recusado deixa a task e a
  opção ligadas. O assistente avisa; a janela de configurações mostra o checkbox como ficou
  (o estado real, não o clique) e o motivo vai para o log. Mudar o app de pasta
  recria a task na próxima vez que a opção for ligada, com um UAC.
- **Widget de letra (#11):**
  - não testado com um segundo monitor de DPI diferente (o usuário tem um monitor só). Trocar
    resolução e escala e redimensionar o widget foram testados pelo usuário em 2026-09-29, sem
    problemas;
  - não medido se o Spotify troca a faixa antes da timeline (a guarda conta a partir de 0);
  - só segue o Spotify desktop, via SMTC. Com `use_smtc` desligado ou reprodução em outro
    aparelho, não há posição para seguir;
  - a dependência nova pesa ~211 MB instalada.
- **Teste instável (já existia, também na `main` limpa):** `test_onboarding_wizard.py` falha
  nesta máquina com o erro Tk "can't read text.tcl".
- **Lacunas de teste:** `SpotifyAuth` e `SyncController` sem testes (precisariam de
  Robolectric). Serviço, receiver e bloco só foram verificados manualmente no aparelho.
  `DesktopColorParityTest` precisa de capas reais fora do repositório, então a paridade de cor
  com o PC é conferida à mão.

### 5.3 Próximas prioridades

1. **Medir o cache da arte original no A71** (#19) e rodar no aparelho os 5 testes
   instrumentados novos de `WallpaperComposerTest` (§5.2).

Entregues em #18 e #19 (mergeados em 2026-09-30): "Compartilhar letra" com a música pausada (#18) e o cache
em disco da arte original e das cores (#19).

Descartados por decisão do usuário (2026-09-24): limite de ampliação da arte no desktop,
wallpaper por monitor e testes com Robolectric para `SpotifyAuth` e `SyncController`.

---

## 6. Build e testes

| Alvo | Comando |
|---|---|
| Desktop: rodar | `.venv\Scripts\python main.py` (`--setup`, `--install-autostart`, `--uninstall-autostart`) |
| Desktop: testes | `.venv\Scripts\pytest` (os testes do widget criam um `QApplication`: precisam do PySide6 instalado) |
| Android: lógica pura | `cd android && ./gradlew :core:test` (cobertura: `:core:jacocoTestReport`) |
| Android: app | `./gradlew :app:testDebugUnitTest`, `:app:connectedDebugAndroidTest` (celular desbloqueado e com a tela ligada), `:app:lintDebug` |
| Android: release | `android/build-apk.cmd` (gera, confere a assinatura, copia para `android/dist/`, instala por USB se pedido) |
| Android: versão | `./gradlew -q :app:printVersion` → `versionCode=<commits> sha=<7> branch=<nome>` |

A chave de assinatura e o `keystore.properties` ficam fora do repositório. **Sem a chave não
é possível atualizar o app instalado.**

`versionName` = `0.1.0` + sufixo: ` (<sha>)` no release e ` (<sha>, <branch>)` no debug.

**CI (GitHub Actions, `.github/workflows/`):**

| Workflow | Gatilho | O que faz |
|---|---|---|
| `release.yml` | push na `main`; manual (o job só roda na `main`, com `environment: release`) | Testes + lint → assina com a chave de release → apaga a chave → confere o certificado → publica `r<versionCode>` como latest, se não houver um `r<N>` maior. Um release por vez, nunca cancelado no meio |
| `debug.yml` | push em qualquer branch menos a `main`; manual | Testes + lint → assina com a chave de debug compartilhada → apaga a chave → confere → apaga e recria a pré-release `debug-<slug>`, com título = nome da branch. Um push novo cancela o build anterior da mesma branch |
| `debug-cleanup.yml` | branch apagada, toda segunda às 04:17 UTC, manual | Apaga as pré-releases `debug-*` cuja branch (pelo título) não existe mais |

Os dois de build usam `fetch-depth: 0` (o `versionCode` precisa do histórico completo), JDK 17
e actions fixadas pelo SHA do commit. Um passo com `if: always()` apaga `$RUNNER_TEMP/*.jks` e
o `keystore.properties` logo depois do assemble.

Scripts em `android/ci/`:

| Script | Função |
|---|---|
| `write-signing.sh` | Recria as chaves no `$RUNNER_TEMP` a partir dos secrets e escreve o `keystore.properties`. Os secrets de release são tudo ou nada |
| `verify-apk-cert.sh <release\|debug> <apk>` | Falha se o SHA-256 do certificado não for o esperado para o canal (valores públicos, fixos no script) |
| `write-update-json.sh <canal> <apk> <dir>` | Copia o APK como `wallpaper-changer-<versionCode>.apk` e gera o `update.json` ao lado |
| `debug-tag.sh <branch>` | Nome da tag: `debug-` + slug (minúsculas, dígitos, `.` e `-`) |

Secrets (os `.jks` em base64):

| Onde | Secrets |
|---|---|
| Environment `release` (deployment branch = `main`) | `RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_PASSWORD` |
| Repositório | `DEBUG_KEYSTORE_B64` |

O alias
(`wallpaper-changer`) vai como texto no `release.yml`: como secret, o GitHub mascararia o
nome do repositório em todos os logs.
