# PLANNING — Spotify Dynamic Wallpaper Engine

Documentação técnica do estado atual. Descreve **como o sistema funciona hoje**, não como
foi construído. Uso e instalação estão no [README](README.md) e no
[README do Android](android/README.md).

Última revisão: 2026-09-25 · base: `feat/share-lyrics` (PR #9, aberto) sobre `main` @ `011114b`

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
| Interface | Ícone na bandeja (pystray) + assistente Tkinter | Compose: onboarding, tela principal, bloco nas Configurações rápidas |
| Render | Pillow | `android.graphics.Canvas` |
| Testes | 217 (pytest) | 552: 403 JUnit 5 no `:core`; 20 unitários e 129 instrumentados no `:app` |

**Regra de paridade:** tudo o que é visual (cor de fundo, mesh, glow, blur, moldura, cartão)
é **portado** do desktop para o Android, não reimplementado. Os valores são conferidos contra
números gerados pelo código Python.

**Exceção (decisão do usuário):** "Compartilhar letra" (§2.5) existe só no Android e fica
fora da regra de paridade.

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
| principal | `TrayApp` (`os_integration/tray.py`) | Menu da bandeja; bloqueia até Exit |
| `smtc-watcher` | `SmtcWatcher` (`os_integration/smtc.py`) | Loop asyncio próprio (winsdk). Eventos SMTC mais uma consulta de segurança a cada 2 s. Expõe um snapshot protegido por lock |
| `poller` | `Poller` (`spotify/poller.py`) | Loop principal de sync e render |

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

1. Tira um `dataclasses.replace(settings)`, uma cópia, porque a bandeja altera as settings
   em outra thread.
2. `compute_layout` usa a resolução do monitor primário, com DPI-aware. A arte ocupa
   `art_size_pct` × altura, centralizada.
3. `base_cache_key` → `album_bases/<key>.png`. Se existe, reutiliza e atualiza o mtime. Senão
   baixa a arte, compõe a base, salva e poda o cache.
4. Copia a base e desenha título e artista por cima (com cartão de vidro opcional). A cor do
   texto vem da média dos pixels atrás dele.
5. Salva alternando entre `wallpaper_a.png` e `wallpaper_b.png`, para o Explorer nunca
   segurar lock no arquivo sendo escrito.
6. `set_wallpaper`: direto (`SystemParametersInfoW`) ou com fade (`IActiveDesktop`). O fade
   cai para o modo direto se falhar.
7. Se `sync_lock_screen` está ligado, chama `lockscreen.request_update`.

**Tela de bloqueio (desktop):** uma Scheduled Task `SpotifyWallpaperEngine_LockScreen` é
criada uma vez, com `/rl highest` (um prompt UAC). Cada atualização grava
`lockscreen_pending.json` e roda `schtasks /run`. A task executa `main.py --apply-lockscreen`
elevado e escreve em `HKLM\...\PersonalizationCSP`. Todo `schtasks` usa `CREATE_NO_WINDOW`.

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
status da tela principal, **só com `SyncStatus.Showing`**. A notificação não tem esse botão.

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

---

## 3. Esquema de dados

### 3.1 Desktop: arquivos locais

| Caminho | Conteúdo | Escrita |
|---|---|---|
| `%APPDATA%\SpotifyWallpaperEngine\config.json` | `Settings` (dataclass → JSON) | Criado com os padrões; bandeja e assistente regravam |
| `%LOCALAPPDATA%\...\track_index.json` | Índice faixa → álbum | Atômica (`.tmp` + `os.replace`), só quando muda |
| `%LOCALAPPDATA%\...\cache\album_bases\<key>.png` | Base por álbum (sem texto) | Uma vez por chave, PNG `compress_level=1` |
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
| `cacheDir/updates/` | APK de atualização baixado (só um: a pasta é esvaziada antes; `.part` até o SHA-256 conferir) |
| `cacheDir/share/letra-<ms>.jpg` | Imagem de compartilhar letra (só uma; nome novo a cada vez). Única pasta servida pelo `FileProvider` (`${applicationId}.share`, `res/xml/share_paths.xml`, não exportado, leitura concedida só ao app escolhido) |

Backup automático do Android desligado (`allowBackup=false`). Letras nunca vão para o disco.

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
| Snapshot das settings no render (desktop) | A bandeja muda settings em outra thread. Sem cópia, a chave e o desenho podiam usar estilos diferentes e envenenar o cache |
| Fade no Windows via `IActiveDesktop` + mensagem `0x052C` ao Progman | Único caminho que anima a troca. Sem `AD_APPLY_FORCE`, que falha no Windows 10. Depende das animações do sistema estarem ligadas |
| Fade no Android via live wallpaper próprio | `setBitmap` pisca preto a cada troca, comportamento do sistema. O quadro vai em pixels crus porque codificar PNG custa centenas de ms |
| Tela de bloqueio no Windows via Scheduled Task elevada | A chave `PersonalizationCSP` fica em HKLM. A task pede UAC uma vez só |
| Tokens: Credential Manager / Keystore + Tink | Nunca em texto puro no disco. OAuth PKCE, sem client secret |
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

---

## 5. Estado atual

### 5.1 Pronto (tudo em `main`)

| PR | Entrega |
|---|---|
| #1 | Desktop: híbrido SMTC/API, pré-busca de tracklist, tela de bloqueio, bandeja, assistente de 6 passos |
| #2 | Android M0–M16: OAuth, render, cache, FGS, retomada após reboot/update, tela principal, bloco nas Configurações rápidas, onboarding, MediaSession opcional, modo local, APK assinado |
| #3 | Nas duas plataformas: estilos `mesh`/`blur`, glow, moldura de vidro, cartão de vidro, intensidade do blur, transição suave, cache de 150 MB com LRU, álbum novo em ~5 s, índice faixa → álbum persistido. Desktop: menu Style e Restart na bandeja, Force Sync redesenha até com a música pausada, sem janela de console piscando. Android: `build-apk.ps1` gera e instala o release |
| #4 | Documentação técnica (`PLANNING.md`) |
| #5 | Android: redesenho ao abrir ou fechar dobráveis, um quadro do live wallpaper por forma de tela |
| #6 | CI: canais release/debug no GitHub Releases, `versionCode` = contagem de commits, atualização no app |
| #7 | Android: redesenho ao mudar o tamanho de exibição (zoom/DPI), sem chamada à API. Validado no build de release |

**Pronto, aguardando merge:** #9, "Compartilhar letra" (Android, §2.5). Testado no A71
(Instagram Stories, WhatsApp, rotação durante a seleção) e aprovado na revisão. O merge
**precisa ser merge commit, não squash**: o release da branch já está no celular, e uma
`main` "squashada" teria `versionCode` menor que o instalado, então a atualização no app a
recusaria.

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
- **Mudar o estilo baixa a arte de novo** (a chave muda e a arte original não fica em cache).
- **Compartilhar letra (#9):**
  - o botão some com a música pausada (`Idle`), embora o wallpaper ainda mostre a faixa.
    **Decisão pendente do usuário**;
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
- **Lacunas de teste:** `SpotifyAuth` e `SyncController` sem testes (precisariam de
  Robolectric). Serviço, receiver e bloco só foram verificados manualmente no aparelho.
  `DesktopColorParityTest` precisa de capas reais fora do repositório, então a paridade de cor
  com o PC é conferida à mão.

### 5.3 Próximas prioridades

1. **Merge do #9** (merge commit) e validação no release da `main`.
2. **Decidir** se "Compartilhar letra" aparece também com a música pausada.
3. **Cache em disco da arte original e das cores**, para mudar de estilo sem baixar a arte de
   novo nem recalcular a paleta.

Descartados por decisão do usuário (2026-09-24): limite de ampliação da arte no desktop,
wallpaper por monitor e testes com Robolectric para `SpotifyAuth` e `SyncController`.

---

## 6. Build e testes

| Alvo | Comando |
|---|---|
| Desktop: rodar | `.venv\Scripts\python main.py` (`--setup`, `--install-autostart`, `--uninstall-autostart`) |
| Desktop: testes | `.venv\Scripts\pytest` |
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
