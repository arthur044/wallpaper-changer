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

**Tablets e dobráveis:** com a tela a partir de 600dp, o desenho é um quadrado do lado
maior, visível nas duas orientações. Abrir ou fechar um dobrável redesenha a faixa atual
sem chamar a API; mudar o tamanho de exibição (zoom) também. Girar o celular não redesenha.
O redesenho do dobrável foi validado em aparelho real.

**Compartilhar letra** (só no Android) — com uma faixa na tela, o cartão de status da
tela principal mostra "Compartilhar letra". Toque nas linhas para escolher os versos; a
prévia usa a mesma arte e o mesmo fundo do wallpaper, com os versos num cartão de vidro
sobre a capa, em 9:16 (quadrada em tablet ou dobrável aberto). "Compartilhar" abre o menu
do Android. As letras vêm do [LRCLIB](https://lrclib.net), um serviço comunitário, então
podem faltar ou ter erros. A letra só é buscada com a tela principal aberta, fica apenas em
memória, e a imagem é um único arquivo temporário, apagado no próximo compartilhamento ou
quando o app abre.

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

## Baixar e atualizar

A CI publica os APKs em [GitHub Releases](https://github.com/arthur044/wallpaper-changer/releases):

- **Release:** a release marcada como *Latest*, gerada a cada push na `main`
  (`wallpaper-changer-<versionCode>.apk`).
- **Debug:** uma pré-release por branch (`debug-<branch>`, título = nome da branch), só
  com o build mais novo dela.

Baixe o `.apk` da release e abra no celular. Na primeira vez, o Android pede para permitir
a instalação de apps desconhecidos.

**Atualizações dentro do app:** a seção "Atualizações" na tela principal mostra a versão
instalada e segue o canal dela:

- no app de release, "Atualizar app" busca o build mais recente da `main`;
- no app de debug, escolha uma branch (a do build instalado já vem selecionada) e toque em
  "Baixar versão mais recente".

O app baixa o APK, confere o SHA-256 e passa para o instalador do Android, que pede
confirmação. Login e configurações são mantidos. Detalhes:

- é preciso permitir que o app instale apps (o botão "Permitir" leva ao ajuste);
- o GitHub aceita 60 verificações por hora sem login; passou disso, espere a hora virar;
- o Play Protect pode pedir para verificar os builds de debug: deixe verificar e instale;
- trocar para uma branch mais antiga (com menos commits) que a instalada exige
  desinstalar o app de debug antes, porque o Android não instala uma versão menor por cima.

## Build e instalação

```
cd android
./gradlew :app:installDebug        # instala no aparelho conectado
./gradlew :app:assembleRelease     # APK para sideload
```

O build de debug é instalado como `io.github.arthur044.wallpaperchanger.debug`
("Wallpaper Changer (debug)"), ao lado do de release, sem mexer nele: os dois são
assinados com chaves diferentes, e atualizar um pelo outro exigiria desinstalar o
release, perdendo login e configurações. Os dois usam o mesmo esquema de callback,
então um login feito pelo debug pode perguntar qual app abrir.

### Gerar o APK sozinho

Dê um duplo clique em `android/build-apk.cmd` (ou rode `.\build-apk.ps1` no PowerShell). Ele:

1. confere os pré-requisitos: `keystore.properties`, a chave de assinatura e o SDK;
2. gera o APK de release e confere que saiu assinado com a sua chave;
3. guarda uma cópia em `android/dist/`, com versão, data e commit no nome (e `-alterado` se havia mudanças não commitadas);
4. com o celular conectado por USB, pergunta se deve instalar por cima, mantendo login e configurações.

`-Install` instala sem perguntar; `-NoInstall` só gera o APK.

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
# Opcional: chave de debug compartilhada com a CI
debugStoreFile=C:/caminho/para/wallpaper-changer-debug.jks
```

Com `debugStoreFile`, o debug é assinado com a mesma chave que a CI usa. Assim, um APK de
debug gerado no PC atualiza o baixado do GitHub, e vice-versa. Sem ele, o debug usa a
chave de debug da própria máquina.

Na CI, a chave de release vem dos secrets do Environment `release`, que só libera a `main`
(`RELEASE_KEYSTORE_B64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_PASSWORD`). A de debug
vem do secret de repositório `DEBUG_KEYSTORE_B64`. O `ci/write-signing.sh` monta o
`keystore.properties` do runner, que é apagado junto com as chaves logo depois do build. A versão vem do git:
`versionCode` é o número de commits. `./gradlew -q :app:printVersion` mostra a versão que o
checkout atual recebe.

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
| `core` | Kotlin puro, sem Android: decisão de polling, backoff, configurações, cliente da API, layout, cor, chave de cache, o motor de sincronização, cliente do LRCLIB e layout da imagem de letra |
| `app` | Android: OAuth (AppAuth + Tink), renderização em Canvas, cache em disco, serviço, bloco das Configurações rápidas, telas, imagem e tela de compartilhar letra |

As telas de debug e a medição do MediaSession continuam no código, acessíveis **apenas em
builds de debug** (`BuildConfig.DEBUG`), pelo link no fim da tela principal.

## Privacidade

- A sessão do Spotify fica criptografada com uma chave do Android Keystore
  (`files/spotify_auth_state.bin`); o token nunca é registrado em log nem exibido.
- O backup automático do Android está desligado.
- O app fala só com `accounts.spotify.com`, `api.spotify.com`, o CDN de capas, `lrclib.net`
  (título, artista, álbum e duração da faixa, só com a tela principal aberta) e
  `api.github.com`/GitHub Releases (só ao verificar ou baixar uma atualização).
- A letra nunca é gravada no aparelho. A imagem compartilhada fica em `cache/share` até o
  próximo compartilhamento ou a próxima abertura do app, e só o app escolhido no menu de
  compartilhar recebe permissão para lê-la.
