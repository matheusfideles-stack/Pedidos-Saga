import { api } from "../api.js";
import { el } from "../dom.js";
import { inteiro } from "../format.js";
import { configuracoes } from "../settings.js";
import { selo } from "../components/badge.js";
import { botao } from "../components/button.js";
import { cabecalhoDePagina, card, indicador } from "../components/card.js";
import { esqueleto, estadoErro } from "../components/states.js";

/** Estoque de todos os produtos: disponível, reservado e nível. */
export async function telaEstoque(raiz, ctx) {
  const corpo = el("div", {}, card({ filhos: [esqueleto(6)] }));
  raiz.replaceChildren(
    cabecalhoDePagina({
      titulo: "Estoque",
      descricao: "Disponível é o que ainda pode ser vendido. Reservado está preso em pedidos em andamento.",
      acoes: botao({ icone: "refresh", variante: "ghost", titulo: "Atualizar agora", onClick: () => carregar() }),
    }),
    corpo,
  );

  let nomes = new Map();

  async function carregar() {
    try {
      const [niveis, produtos] = await Promise.all([api.estoque(), api.produtos().catch(() => [])]);
      nomes = new Map(produtos.map((p) => [p.id, p.name]));
      if (ctx.ativo()) corpo.replaceChildren(...desenhar(niveis));
    } catch (erro) {
      if (ctx.ativo()) corpo.replaceChildren(estadoErro({ mensagem: erro.message, aoTentarNovamente: carregar }));
    }
  }

  function desenhar(niveis) {
    const limite = configuracoes.obter().limiteEstoqueBaixo;
    const baixos = niveis.filter((n) => n.available < limite).length;
    const reservados = niveis.reduce((soma, n) => soma + n.reserved, 0);
    const maximo = Math.max(...niveis.map((n) => n.available), 1);

    return [
      el(
        "div",
        { class: "grid grid--tres", style: "margin-bottom:16px" },
        indicador({ rotulo: "Produtos", icone: "box", valor: inteiro(niveis.length), rodape: "com controle de estoque" }),
        indicador({ rotulo: "Estoque baixo", icone: "alert", tom: "warn", valor: inteiro(baixos), rodape: `abaixo de ${limite} unidades` }),
        indicador({ rotulo: "Unidades reservadas", icone: "layers", tom: "violet", valor: inteiro(reservados), rodape: "em pedidos em andamento" }),
      ),
      card({
        filhos: [
          el(
            "div",
            { class: "table-wrap" },
            el(
              "table",
              { class: "table table--cartoes" },
              el("thead", {}, el("tr", {}, el("th", {}, "Produto"), el("th", { class: "num" }, "Disponível"), el("th", { class: "num" }, "Reservado"), el("th", {}, "Nível"), el("th", {}, "Situação"))),
              el(
                "tbody",
                {},
                niveis.map((n) => {
                  const baixo = n.available < configuracoes.obter().limiteEstoqueBaixo;
                  const fracao = Math.min(n.available / maximo, 1);
                  return el(
                    "tr",
                    {},
                    el("td", { "data-label": "Produto" }, nomes.get(n.productId) || n.productId, " ", el("span", { class: "mono" }, n.productId)),
                    el("td", { "data-label": "Disponível", class: "num" }, inteiro(n.available)),
                    el("td", { "data-label": "Reservado", class: "num" }, inteiro(n.reserved)),
                    el(
                      "td",
                      { "data-label": "Nível", style: "min-width:120px" },
                      el("div", { class: `progress${baixo ? " progress--warn" : ""}`, role: "progressbar", "aria-valuenow": Math.round(fracao * 100), "aria-valuemin": 0, "aria-valuemax": 100 },
                        el("div", { class: "progress__fill", style: `width:${Math.max(fracao * 100, baixo ? 3 : 0)}%` })),
                    ),
                    el("td", { "data-label": "Situação" }, baixo ? selo("Baixo", "warn") : selo("Normal", "ok")),
                  );
                }),
              ),
            ),
          ),
        ],
      }),
    ];
  }

  await carregar();
  ctx.atualizarACada(5000, carregar);
}
