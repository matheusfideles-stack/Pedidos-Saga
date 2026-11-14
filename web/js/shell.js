// Esqueleto da aplicação: dock lateral, cabeçalho (navegação, atalho, avisos, estado da API) e área de conteúdo.
import { api, observarStatusDaApi } from "./api.js";
import { el } from "./dom.js";
import { icon } from "./icons.js";
import { inteiro } from "./format.js";
import { montarAvisos } from "./metrics.js";
import { configuracoes } from "./settings.js";
import { botao } from "./components/button.js";
import { menuSuspenso } from "./components/dropdown.js";
import { estadoVazio } from "./components/states.js";

const SECOES = [
  { chave: "inicio", rotulo: "Início", icone: "home", href: "#/inicio" },
  { chave: "novo", rotulo: "Novo pedido", icone: "plus", href: "#/novo" },
  { chave: "pedidos", rotulo: "Pedidos", icone: "list", href: "#/pedidos" },
  { chave: "estoque", rotulo: "Estoque", icone: "box", href: "#/estoque" },
  { chave: "catalogo", rotulo: "Catálogo", icone: "tag", href: "#/catalogo" },
  { chave: "configuracoes", rotulo: "Configurações", icone: "settings", href: "#/configuracoes" },
];

const INTERVALO_DE_AVISOS_MS = 20_000;

export function montarShell(raiz) {
  // Destaque que desliza entre os atalhos (um só elemento, movido por transform), em vez de trocar de item de repente.
  const indicadorDoDock = el("span", { class: "dock__indicador", "aria-hidden": "true" });
  const dock = el(
    "aside",
    { class: "dock" },
    el(
      "nav",
      { class: "dock__lista", "aria-label": "Atalhos" },
      indicadorDoDock,
      SECOES.map((s) => el("a", { class: "dock__item", href: s.href, "data-label": s.rotulo, "data-chave": s.chave, "aria-label": s.rotulo }, icon(s.icone, 20))),
    ),
  );

  const { sino, definirAvisos } = criarSino();
  const estadoApi = criarIndicadorDaApi();

  const topbar = el(
    "header",
    { class: "topbar" },
    el("div", { class: "topbar__marca" }, el("span", { class: "topbar__logo" }, icon("zap", 18)), el("span", {}, "Marketplace de Pedidos")),
    el("div", { class: "topbar__acoes" }, estadoApi, sino),
  );

  const tela = el("main", { class: "content", id: "tela", tabindex: "-1" });

  raiz.append(
    el("a", { class: "pular", href: "#tela" }, "Pular para o conteúdo"),
    el("div", { class: "fundo", "aria-hidden": "true" }, el("div", { class: "fundo__foto" }), el("div", { class: "fundo__veu" })),
    el("div", { class: "shell" }, dock, el("div", { class: "shell__main" }, topbar, tela)),
    el("div", { id: "toasts", class: "toasts" }),
  );

  atualizarAvisos();
  setInterval(() => !document.hidden && atualizarAvisos(), INTERVALO_DE_AVISOS_MS);

  async function atualizarAvisos() {
    try {
      const [niveis, pedidos] = await Promise.all([api.estoque(), api.listarPedidos(200)]);
      definirAvisos(montarAvisos(niveis, pedidos, Date.now(), configuracoes.obter().limiteEstoqueBaixo));
    } catch {
      // mantém os avisos anteriores; o indicador da API já mostra a falha de conexão
    }
  }

  let chaveAtiva = null;

  /** Move o destaque do dock até o item ativo. Na primeira vez ele aparece sem animar. */
  function posicionarIndicador(semAnimacao = false) {
    const item = indicadorDoDock.parentElement.querySelector(`.dock__item[data-chave="${chaveAtiva}"]`);
    if (!item) {
      indicadorDoDock.style.opacity = "0";
      return;
    }
    indicadorDoDock.style.width = `${item.offsetWidth}px`;
    indicadorDoDock.style.height = `${item.offsetHeight}px`;
    indicadorDoDock.style.transform = `translate(${item.offsetLeft}px, ${item.offsetTop}px)`;
    indicadorDoDock.style.opacity = "1";
    indicadorDoDock.classList.toggle("sem-transicao", semAnimacao);
  }

  function marcarAtivo(chave) {
    const primeiraVez = chaveAtiva === null;
    chaveAtiva = chave;
    raiz.querySelectorAll("[data-chave]").forEach((link) => {
      const ativo = link.dataset.chave === chave;
      link.classList.toggle("is-active", ativo);
      if (ativo) link.setAttribute("aria-current", "page");
      else link.removeAttribute("aria-current");
    });
    posicionarIndicador(primeiraVez);
  }

  // Ao redimensionar (dock vira coluna ou barra), o destaque recalcula sua posição.
  window.addEventListener("resize", () => posicionarIndicador(true));

  return { tela, marcarAtivo };
}

function criarIndicadorDaApi() {
  const texto = el("span", { class: "status-api__texto" }, "Conectando…");
  const no = el("span", { class: "status-api", role: "status" }, el("span", { class: "status-api__ponto" }), texto);
  observarStatusDaApi((online) => {
    no.classList.toggle("is-online", online);
    no.classList.toggle("is-offline", !online);
    texto.textContent = online ? "API online" : "API offline";
  });
  return no;
}

function criarSino() {
  let avisos = [];
  const contador = el("span", { class: "sino__contador", hidden: true });
  const gatilho = el("button", { class: "btn btn--ghost btn--icon sino", type: "button", "aria-label": "Avisos" }, icon("bell", 18), contador);

  const sino = menuSuspenso({
    gatilho,
    criarPainel: (fechar) => [
      el("div", { class: "dropdown__titulo" }, "Avisos"),
      avisos.length === 0
        ? estadoVazio({ icone: "check", titulo: "Tudo em ordem", texto: "Sem estoque baixo nem cancelamentos recentes." })
        : avisos.map((aviso) => itemDeAviso(aviso, fechar)),
    ],
  });

  function definirAvisos(novos) {
    avisos = novos;
    contador.hidden = avisos.length === 0;
    contador.textContent = String(avisos.length);
    gatilho.setAttribute("aria-label", avisos.length === 0 ? "Avisos" : `Avisos: ${avisos.length} novos`);
  }

  return { sino, contador, definirAvisos };
}

function itemDeAviso(aviso, fechar) {
  const estoque = aviso.tipo === "estoque";
  return el(
    "a",
    { class: "aviso-item", href: aviso.destino, role: "menuitem", onclick: fechar },
    el("span", { class: `aviso-item__icone aviso-item__icone--${estoque ? "warn" : "danger"}` }, icon(estoque ? "alert" : "x", 18)),
    el(
      "span",
      {},
      el("div", { class: "aviso-item__titulo" }, estoque ? `Estoque baixo: ${aviso.produto}` : `${inteiro(aviso.quantidade)} pedido(s) cancelado(s)`),
      el("small", {}, estoque ? `${inteiro(aviso.disponivel)} unidades disponíveis` : "nas últimas 24 horas"),
    ),
  );
}
