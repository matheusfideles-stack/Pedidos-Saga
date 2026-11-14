import { api } from "../api.js";
import { el } from "../dom.js";
import { icon } from "../icons.js";
import { curto, dataHora, haQuantoTempo, moeda } from "../format.js";
import { STATUS_ABERTOS } from "../metrics.js";
import { botao } from "../components/button.js";
import { seloStatus } from "../components/badge.js";
import { cabecalhoDePagina, card } from "../components/card.js";
import { esqueleto, estadoErro, estadoVazio } from "../components/states.js";

const FILTROS = [
  { chave: "todos", rotulo: "Todos", aceita: () => true },
  { chave: "abertos", rotulo: "Em andamento", aceita: (p) => STATUS_ABERTOS.includes(p.status) },
  { chave: "confirmados", rotulo: "Confirmados", aceita: (p) => p.status === "CONFIRMED" },
  { chave: "cancelados", rotulo: "Cancelados", aceita: (p) => p.status === "CANCELLED" },
];

/** Lista de pedidos com busca e filtro por status. A barra de ferramentas é criada uma vez; só a tabela é redesenhada. */
export async function telaPedidos(raiz, ctx) {
  let pedidos = [];
  let filtro = "todos";
  let busca = "";

  const caixaTabela = el("div", {}, esqueleto(6));
  const entradaBusca = el("input", {
    class: "input",
    type: "search",
    placeholder: "Buscar por cliente ou código",
    "aria-label": "Buscar pedidos",
    oninput: (evento) => {
      busca = evento.target.value.trim().toLowerCase();
      desenharTabela();
    },
  });
  const chips = el("div", { class: "chips", role: "group", "aria-label": "Filtrar por status" });

  function desenharChips() {
    chips.replaceChildren(
      ...FILTROS.map((f) =>
        el(
          "button",
          {
            type: "button",
            class: `chip${f.chave === filtro ? " is-active" : ""}`,
            "aria-pressed": String(f.chave === filtro),
            onclick: () => {
              filtro = f.chave;
              desenharChips();
              desenharTabela();
            },
          },
          f.rotulo,
          el("span", { class: "chip__n" }, String(pedidos.filter(f.aceita).length)),
        ),
      ),
    );
  }

  function visiveis() {
    const regra = FILTROS.find((f) => f.chave === filtro).aceita;
    return pedidos.filter(
      (p) => regra(p) && (busca === "" || p.customerId.toLowerCase().includes(busca) || p.id.toLowerCase().includes(busca)),
    );
  }

  function desenharTabela() {
    const lista = visiveis();
    if (lista.length === 0) {
      caixaTabela.replaceChildren(
        estadoVazio({
          titulo: pedidos.length === 0 ? "Nenhum pedido ainda" : "Nenhum pedido encontrado",
          texto: pedidos.length === 0 ? "Crie o primeiro pedido para ver a saga acontecer." : "Ajuste a busca ou o filtro.",
          acao: pedidos.length === 0 ? botao({ rotulo: "Novo pedido", icone: "plus", onClick: () => ctx.ir("#/novo") }) : null,
        }),
      );
      return;
    }
    caixaTabela.replaceChildren(
      el(
        "div",
        { class: "table-wrap" },
        el(
          "table",
          { class: "table table--cartoes" },
          el("thead", {}, el("tr", {}, ...["Pedido", "Cliente", "Status", "Total", "Criado"].map((t, i) => el("th", { class: i === 3 ? "num" : "" }, t)))),
          el("tbody", {}, lista.map(linha)),
        ),
      ),
    );
  }

  function linha(pedido) {
    const abrir = () => ctx.ir(`#/pedidos/${pedido.id}`);
    return el(
      "tr",
      {
        class: "is-clicavel",
        tabindex: "0",
        onclick: abrir,
        onkeydown: (evento) => {
          if (evento.key === "Enter") abrir();
        },
      },
      el("td", { "data-label": "Pedido", class: "mono" }, curto(pedido.id)),
      el("td", { "data-label": "Cliente" }, pedido.customerId),
      el("td", { "data-label": "Status" }, seloStatus(pedido.status)),
      el("td", { "data-label": "Total", class: "num" }, moeda(pedido.total)),
      el("td", { "data-label": "Criado", title: dataHora(pedido.createdAt) }, haQuantoTempo(pedido.createdAt)),
    );
  }

  async function carregar() {
    try {
      pedidos = await api.listarPedidos(200);
    } catch (erro) {
      if (!ctx.ativo()) return;
      caixaTabela.replaceChildren(estadoErro({ mensagem: erro.message, aoTentarNovamente: carregar }));
      return;
    }
    if (!ctx.ativo()) return;
    desenharChips();
    desenharTabela();
  }

  raiz.replaceChildren(
    cabecalhoDePagina({
      titulo: "Pedidos",
      descricao: "Os 200 pedidos mais recentes, atualizados automaticamente.",
      acoes: botao({ rotulo: "Novo pedido", icone: "plus", onClick: () => ctx.ir("#/novo") }),
    }),
    card({
      filhos: [
        el("div", { style: "display:flex;gap:12px;flex-wrap:wrap;align-items:center;justify-content:space-between;margin-bottom:16px" },
          chips,
          el("div", { class: "search" }, icon("search", 16), entradaBusca)),
        caixaTabela,
      ],
    }),
  );
  desenharChips();
  await carregar();
  ctx.atualizarACada(4000, carregar);
}
