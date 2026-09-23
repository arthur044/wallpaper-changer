# Spotify Dynamic Wallpaper — Android

Port Android do app de bandeja do Windows. Define o wallpaper (e opcionalmente a tela de
bloqueio) como a capa do álbum que está tocando no Spotify.

## Como funciona

**Duas fontes, igual ao desktop:**

- **API Web do Spotify** — o caminho base. Um serviço em primeiro plano consulta a cada
  25 s, **só com a tela ligada**, e cobre reprodução em qualquer aparelho (celular, PC,
  Connect).
- **MediaSession local** (opcional, em "Reagir na hora") — lê a sessão do próprio Spotify
  neste celular. Detecta a troca de faixa na hora e sem rede, mas só enxerga o que toca
  **neste** aparelho; em qualquer outro caso o app volta para a API. Exige acesso a
  notificações. A arte é **sempre** a imagem oficial do Spotify: a miniatura local nunca
  é desenhada.

**Pré-busca da tracklist:** resolver um álbum também busca a lista de faixas dele,
indexada por artista+nome. Pular para outra faixa do mesmo álbum é reconhecido na hora,
sem nova chamada.

**Modo local** ("Sem notificação fixa") — com o caminho instantâneo ativo, a
sincronização roda dentro do serviço de notificações: sem serviço em primeiro plano, sem
notificação permanente e sem consulta nenhuma quando não há nada tocando aqui.

**Cache:** a base composta de cada álbum (arte + sombra + cantos) é renderizada uma vez e
guardada em `cacheDir/album_bases/`, com limite de 150 MB. Trocar de faixa dentro do
mesmo álbum só redesenha o texto.

**Cor do fundo:** porte fiel do ColorThief do desktop (`core/render/ColorThief.kt`), para
a mesma capa dar a mesma cor nas duas plataformas. Não troque por outro algoritmo sem
comparar com valores gerados pelo código do desktop.

## Requisitos

- Android 8.0 (API 26) ou mais novo
- JDK 17 e o Android SDK (compileSdk 37; targetSdk 36 é deliberado, veja `app/lint.xml`)
- Um app Spotify registrado em [developer.spotify.com](https://developer.spotify.com/dashboard)
  (PKCE, sem client secret) com a redirect URI **exata**
  `io.github.arthur044.wallpaperchanger://callback`

O guia de primeira execução dentro do app mostra esse passo a passo e valida o Client ID.

## Build e instalação

```
cd android
./gradlew :app:installDebug        # instala no aparelho conectado
./gradlew :app:assembleRelease     # APK para sideload
```

`local.properties` precisa de `sdk.dir`, com o dois-pontos escapado no Windows:
`sdk.dir=C\:/Users/<voce>/AppData/Local/Android/Sdk`.

### Assinatura do APK

O release é assinado com uma chave própria, descrita em `android/keystore.properties`.
Esse arquivo e a chave **não estão no repositório** (veja o `.gitignore`); sem eles o
projeto compila normalmente, e só o APK sai sem assinatura.

```properties
storeFile=C:/caminho/para/wallpaper-changer.jks
keyAlias=wallpaper-changer
storePassword=...
keyPassword=...
```

Para criar uma chave nova:

```
keytool -genkeypair -v -keystore wallpaper-changer.jks -alias wallpaper-changer \
  -keyalg RSA -keysize 4096 -validity 10000
```

**Guarde a chave e a senha.** O Android só aceita atualizar um app instalado se a
assinatura for a mesma; com a chave perdida, a única saída é desinstalar e instalar de
novo, perdendo login e configurações. Assinatura v2 e v3; o v3 permite trocar de chave no
futuro sem quebrar atualizações. R8 continua desligado, porque AppAuth, Tink e a
serialização precisariam de regras próprias e a build testada no aparelho é esta.

## Testes

```
./gradlew :core:test                    # lógica pura (JUnit 5, tempo virtual)
./gradlew :core:jacocoTestReport        # cobertura -> core/build/reports/jacoco
./gradlew :app:testDebugUnitTest        # lógica do app sem Android
./gradlew :app:connectedDebugAndroidTest # render, cache, wallpaper e telas, no aparelho
./gradlew :app:lintDebug
```

Os testes de interface precisam do **celular desbloqueado e com a tela ligada**; caso
contrário todos falham com "No compose hierarchies found". Ajuda:
`adb shell svc power stayon usb`.

Os testes instrumentados não trocam o wallpaper de verdade: usam uma implementação falsa
do `WallpaperManager`.

**`DesktopColorParityTest` não roda sozinho.** Ele compara capas reais com as cores
calculadas pelo desktop e precisa de arquivos que não estão no repositório, porque as
capas são protegidas por direito autoral. Sem eles o teste se pula, então **a paridade de
cor com o PC é uma conferência manual**; o que roda sempre é o gabarito sintético do
`ColorThiefTest`. Para rodar a versão real, gere as cores com o `colorthief` do desktop e
empurre para `cache/color_parity/` no aparelho, com um `expected.txt` de linhas
`<nome> <rrggbb>`.

## Estrutura

| Módulo | Conteúdo |
|---|---|
| `core` | Kotlin puro, sem Android: decisão de polling, backoff, configurações, cliente da API, layout, cor, chave de cache e o motor de sincronização |
| `app` | Android: OAuth (AppAuth + Tink), renderização em Canvas, cache em disco, serviço, bloco das Configurações rápidas, telas |

As telas de debug e a medição do MediaSession continuam no código, acessíveis **apenas em
builds de debug** (`BuildConfig.DEBUG`), pelo link no fim da tela principal.

## Privacidade

- A sessão do Spotify fica criptografada com uma chave do Android Keystore
  (`files/spotify_auth_state.bin`); o token nunca é registrado em log nem exibido.
- O backup automático do Android está desligado.
- O app fala só com `accounts.spotify.com`, `api.spotify.com` e o CDN de capas.
