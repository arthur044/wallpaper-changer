import logging
import threading
from typing import Callable, Optional

from src.config.settings import Settings, save_settings
from src.onboarding import steps
from src.onboarding.state import (
    OnboardingStep,
    is_valid_client_id,
    next_step,
    previous_step,
    resume_step,
)

logger = logging.getLogger(__name__)

_WINDOW_TITLE = "Configuração - Spotify Wallpaper Engine"

_STEP_TITLES = {
    OnboardingStep.CREATE_APP: "1. Criar o app no Spotify",
    OnboardingStep.REDIRECT_URI: "2. Cadastrar o Redirect URI",
    OnboardingStep.CLIENT_ID: "3. Informar o Client ID",
    OnboardingStep.AUTHENTICATE: "4. Entrar na sua conta",
    OnboardingStep.VERIFY: "5. Verificar a conexão",
    OnboardingStep.OPTIONS: "6. Opções finais",
}

_STEP_BODIES = {
    OnboardingStep.CREATE_APP: (
        "Este app precisa de um app registrado no painel de desenvolvedor do Spotify.\n\n"
        "Clique abaixo para abrir o painel, faça login e crie um app "
        "(qualquer nome e descrição servem).\n\n"
        "Quando terminar, volte aqui e avance."
    ),
    OnboardingStep.REDIRECT_URI: (
        "Nas configurações do app que você acabou de criar, adicione exatamente "
        "este Redirect URI e salve:\n\n"
        "Sem esse passo o login falha - é o erro mais comum."
    ),
    OnboardingStep.CLIENT_ID: (
        "Copie o Client ID do painel (32 caracteres) e cole abaixo.\n\n"
        "Ele fica na página do app, logo abaixo do nome."
    ),
    OnboardingStep.AUTHENTICATE: (
        "Agora vamos autorizar o acesso à sua conta.\n\n"
        "Ao clicar, o navegador abre a página de login do Spotify. "
        "Autorize e volte para esta janela."
    ),
    OnboardingStep.VERIFY: (
        "Vamos confirmar que a conexão está funcionando fazendo uma consulta ao Spotify."
    ),
    OnboardingStep.OPTIONS: (
        "Tudo pronto. Estas opções são opcionais e podem ser mudadas depois "
        "pelo menu da bandeja."
    ),
}


def run_wizard(settings: Settings) -> bool:
    """Guided first-run setup. Returns True when the user finished and the app
    can start, False when they cancelled or closed the window. Falls back to a
    console wizard when tkinter isn't available."""
    try:
        import tkinter  # noqa: F401
    except ImportError:
        logger.warning("tkinter unavailable, falling back to console wizard")
        return run_console_wizard(settings)

    return _TkWizard(settings).run()


class _TkWizard:
    def __init__(self, settings: Settings) -> None:
        import tkinter as tk
        from tkinter import ttk

        self._tk = tk
        self._ttk = ttk
        self._settings = settings
        self._step = resume_step(settings.client_id)
        self._completed = False
        self._client = None
        self._busy = False

        self._root = tk.Tk()
        self._root.title(_WINDOW_TITLE)
        self._root.geometry("560x380")
        self._root.resizable(False, False)

        self._title_var = tk.StringVar()
        self._status_var = tk.StringVar()
        self._client_id_var = tk.StringVar(value=settings.client_id or "")
        self._autostart_var = tk.BooleanVar(value=False)
        self._lockscreen_var = tk.BooleanVar(value=settings.sync_lock_screen)

        self._build_layout()

    # ---- layout -------------------------------------------------------

    def _build_layout(self) -> None:
        ttk = self._ttk

        header = ttk.Label(self._root, textvariable=self._title_var, font=("Segoe UI", 13, "bold"))
        header.pack(anchor="w", padx=20, pady=(18, 8))

        self._body = ttk.Frame(self._root)
        self._body.pack(fill="both", expand=True, padx=20)

        self._status = ttk.Label(self._root, textvariable=self._status_var, wraplength=520, foreground="#b00020")
        self._status.pack(anchor="w", padx=20, pady=(4, 0))

        footer = ttk.Frame(self._root)
        footer.pack(fill="x", padx=20, pady=16)

        self._back_button = ttk.Button(footer, text="Voltar", command=self._on_back)
        self._back_button.pack(side="left")

        self._next_button = ttk.Button(footer, text="Avançar", command=self._on_next)
        self._next_button.pack(side="right")

        ttk.Button(footer, text="Cancelar", command=self._on_cancel).pack(side="right", padx=(0, 8))

        self._root.protocol("WM_DELETE_WINDOW", self._on_cancel)

    def _clear_body(self) -> None:
        for child in self._body.winfo_children():
            child.destroy()

    def _paragraph(self, text: str) -> None:
        self._ttk.Label(self._body, text=text, wraplength=520, justify="left").pack(anchor="w", pady=(0, 12))

    # ---- rendering ----------------------------------------------------

    def _render(self) -> None:
        self._clear_body()
        self._status_var.set("")
        self._title_var.set(_STEP_TITLES.get(self._step, ""))
        self._back_button.state(["!disabled"] if self._step != OnboardingStep.CREATE_APP else ["disabled"])
        self._next_button.config(text="Concluir" if self._step == OnboardingStep.OPTIONS else "Avançar")

        renderer = {
            OnboardingStep.CREATE_APP: self._render_create_app,
            OnboardingStep.REDIRECT_URI: self._render_redirect_uri,
            OnboardingStep.CLIENT_ID: self._render_client_id,
            OnboardingStep.AUTHENTICATE: self._render_authenticate,
            OnboardingStep.VERIFY: self._render_verify,
            OnboardingStep.OPTIONS: self._render_options,
        }.get(self._step)

        if renderer is not None:
            renderer()

    def _render_create_app(self) -> None:
        self._paragraph(_STEP_BODIES[OnboardingStep.CREATE_APP])
        self._ttk.Button(self._body, text="Abrir painel do Spotify", command=steps.open_dashboard).pack(anchor="w")

    def _render_redirect_uri(self) -> None:
        self._paragraph(_STEP_BODIES[OnboardingStep.REDIRECT_URI])

        row = self._ttk.Frame(self._body)
        row.pack(anchor="w", fill="x")

        entry = self._ttk.Entry(row, width=48)
        entry.insert(0, self._settings.redirect_uri)
        entry.config(state="readonly")
        entry.pack(side="left")

        self._ttk.Button(row, text="Copiar", command=self._copy_redirect_uri).pack(side="left", padx=8)

    def _render_client_id(self) -> None:
        self._paragraph(_STEP_BODIES[OnboardingStep.CLIENT_ID])
        entry = self._ttk.Entry(self._body, textvariable=self._client_id_var, width=48)
        entry.pack(anchor="w")
        entry.focus_set()

    def _render_authenticate(self) -> None:
        self._paragraph(_STEP_BODIES[OnboardingStep.AUTHENTICATE])
        self._auth_button = self._ttk.Button(self._body, text="Entrar no Spotify", command=self._on_authenticate)
        self._auth_button.pack(anchor="w")

    def _render_verify(self) -> None:
        self._paragraph(_STEP_BODIES[OnboardingStep.VERIFY])
        self._verify_button = self._ttk.Button(self._body, text="Verificar agora", command=self._on_verify)
        self._verify_button.pack(anchor="w")

    def _render_options(self) -> None:
        self._paragraph(_STEP_BODIES[OnboardingStep.OPTIONS])
        self._ttk.Checkbutton(
            self._body, text="Iniciar junto com o Windows", variable=self._autostart_var
        ).pack(anchor="w")
        self._ttk.Checkbutton(
            self._body,
            text="Sincronizar também a tela de bloqueio (pede permissão do Windows uma vez)",
            variable=self._lockscreen_var,
        ).pack(anchor="w", pady=(6, 0))

    # ---- actions ------------------------------------------------------

    def _copy_redirect_uri(self) -> None:
        self._root.clipboard_clear()
        self._root.clipboard_append(self._settings.redirect_uri)
        self._info("Copiado.", error=False)

    def _on_authenticate(self) -> None:
        self._run_in_background(
            action=lambda: steps.authenticate(self._settings),
            button=self._auth_button,
            busy_text="Aguardando o navegador...",
            on_success=self._authenticated,
        )

    def _authenticated(self, client) -> None:
        self._client = client
        self._info("Conectado. Avance para verificar.", error=False)

    def _on_verify(self) -> None:
        if self._client is None:
            self._info("Faça o login no passo anterior primeiro.")
            return

        self._run_in_background(
            action=lambda: steps.verify_connection(self._client),
            button=self._verify_button,
            busy_text="Consultando o Spotify...",
            on_success=self._verified,
        )

    def _verified(self, outcome: steps.VerifyOutcome) -> None:
        if outcome.result is steps.VerifyResult.PLAYING:
            self._info(f"Funcionando. Tocando agora: {outcome.track_name} - {outcome.artist_name}", error=False)
        else:
            self._info("Conexão OK. Nada tocando no momento.", error=False)

    def _apply_options(self) -> bool:
        from src.os_integration import lockscreen
        from src.os_integration.autostart import install_autostart

        if self._autostart_var.get():
            try:
                install_autostart()
            except OSError as exc:
                logger.error("Autostart install failed: %s", exc)
                self._info(f"Não consegui configurar o início automático: {exc}")
                return False

        wants_lockscreen = self._lockscreen_var.get()
        if wants_lockscreen and not self._settings.sync_lock_screen:
            if not (lockscreen.is_task_installed() or lockscreen.install_task()):
                self._info("Sincronização da tela de bloqueio não foi autorizada. O resto está configurado.")
                return True

        self._settings.sync_lock_screen = wants_lockscreen
        save_settings(self._settings)
        return True

    # ---- navigation ---------------------------------------------------

    def _on_next(self) -> None:
        if self._busy:
            return

        if self._step is OnboardingStep.CLIENT_ID:
            if not is_valid_client_id(self._client_id_var.get()):
                self._info("Client ID inválido. Deve ter 32 caracteres, copiados do painel.")
                return
            steps.save_client_id(self._settings, self._client_id_var.get())

        if self._step is OnboardingStep.AUTHENTICATE and self._client is None:
            self._info("Clique em 'Entrar no Spotify' antes de avançar.")
            return

        if self._step is OnboardingStep.OPTIONS:
            if self._apply_options():
                self._completed = True
                self._root.destroy()
            return

        self._step = next_step(self._step)
        self._render()

    def _on_back(self) -> None:
        if self._busy:
            return
        self._step = previous_step(self._step)
        self._render()

    def _on_cancel(self) -> None:
        self._completed = False
        self._root.destroy()

    # ---- helpers ------------------------------------------------------

    def _info(self, message: str, error: bool = True) -> None:
        self._status.config(foreground="#b00020" if error else "#1b7a34")
        self._status_var.set(message)

    def _run_in_background(
        self,
        action: Callable[[], object],
        button,
        busy_text: str,
        on_success: Callable[[object], None],
    ) -> None:
        """Blocking network/browser work can't run on the Tk thread or the
        window freezes. Run it on a worker and hand the result back via
        after(), which is the only thread-safe way into Tk."""
        if self._busy:
            return

        self._busy = True
        button.state(["disabled"])
        self._next_button.state(["disabled"])
        self._info(busy_text, error=False)

        def worker() -> None:
            try:
                result = action()
            except steps.OnboardingError as exc:
                message = str(exc)
                self._root.after(0, lambda: self._finish_background(button, error=message))
            except Exception as exc:  # noqa: BLE001 - the wizard must never die on an unexpected error
                logger.exception("Onboarding step failed: %s", exc)
                message = str(exc)
                self._root.after(0, lambda: self._finish_background(button, error=message))
            else:
                self._root.after(0, lambda: self._finish_background(button, result=result, on_success=on_success))

        threading.Thread(target=worker, daemon=True, name="onboarding").start()

    def _finish_background(
        self,
        button,
        result: object = None,
        on_success: Optional[Callable[[object], None]] = None,
        error: Optional[str] = None,
    ) -> None:
        self._busy = False
        button.state(["!disabled"])
        self._next_button.state(["!disabled"])

        if error is not None:
            self._info(error)
            return

        if on_success is not None:
            on_success(result)

    def run(self) -> bool:
        self._render()
        self._root.mainloop()
        return self._completed


def run_console_wizard(settings: Settings) -> bool:
    """Fallback for Python installs without tkinter. Only usable when a
    console is attached (i.e. not when launched via pythonw)."""
    print("\n=== Configuração do Spotify Wallpaper Engine ===\n")

    print("1. Abrindo o painel de desenvolvedor do Spotify. Crie um app por lá.")
    steps.open_dashboard()
    input("   Pressione Enter quando terminar...")

    print(f"\n2. Adicione este Redirect URI nas configurações do app:\n   {settings.redirect_uri}")
    input("   Pressione Enter quando tiver salvo...")

    client_id = input("\n3. Cole o Client ID (32 caracteres): ")
    while not is_valid_client_id(client_id):
        client_id = input("   Client ID inválido. Tente de novo: ")
    steps.save_client_id(settings, client_id)

    print("\n4. Abrindo o login do Spotify no navegador...")
    try:
        client = steps.authenticate(settings)
    except steps.OnboardingError as exc:
        print(f"   Falhou: {exc}")
        return False

    print("\n5. Verificando a conexão...")
    try:
        outcome = steps.verify_connection(client)
    except steps.OnboardingError as exc:
        print(f"   Falhou: {exc}")
        return False

    if outcome.result is steps.VerifyResult.PLAYING:
        print(f"   Funcionando. Tocando agora: {outcome.track_name} - {outcome.artist_name}")
    else:
        print("   Conexão OK. Nada tocando no momento.")

    print("\nPronto. Iniciando o app.\n")
    return True
