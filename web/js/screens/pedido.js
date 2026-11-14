import { api } from "../api.js";
import { el } from "../dom.js";
import { icon } from "../icons.js";
import { ROTULOS_GATILHO, curto, dataHora, moeda } from "../format.js";
import { botao } from "../components/button.js";
import { seloPagamento, seloStatus } from "../components/badge.js";
import { card } from "../components/card.js";
import { confirmar } from "../components/modal.js";
import { esqueletoBloco, estadoErro, estadoVazio } from "../components/states.js";
import { avisar } from "../components/toast.js";

const ESTADOS_FINAIS = ["CONFIRMED", "CANCELLED"];

/** Detalhe de um pedido: resumo, itens, pagamento e linha do tempo da saga. Atualiza enquanto o pedido está aberto. */
export async function telaPedido(raiz, ctx) {
  const { id } = ctx.parametros;
  raiz.replaceChildren(card({ filhos: [esqueletoBloco()] }));

  let nomes = new Map();
  try {
    nomes = new Map((await api.produtos()).map((p) => [p.id, p.name]));
  } catch {
    // sem o catálogo, os itens aparecem pelo código do produto
  }

  async function carregar() {
    let dados;
    try {
      const [pedido, historico, pagamento] = await Promise.all([api.pedido(id), api.historico(id), api.pagamento(id)]);
      dados = { pedido, historico, pagamento };
    } catch (erro) {
      if (!ctx.ativo()) return;
      raiz.replaceChildren(
        voltar(),
        estadoErro({ mensagem: erro.status === 404 ? "Este pedido não existe." : erro.message, aoTentarNovamente: erro.status === 404 ? null : carregar }),
      );
      ctx.pararDeAtualizar();
      return;
    }
    if (!ctx.ativo()) return;

    desenhar(dados);
    if (ESTADOS_FINAIS.includes(dados.pedido.status)) ctx.pararDeAtualizar();
  }

  async function cancelar(pedido) {
    const confirmado = await confirmar({
      titulo: "Cancelar este pedido?",
      mensagem: "As reservas de estoque e a cobrança serão desfeitas. Essa ação não pode ser revertida.",
      rotuloConfirmar: "Cancelar pedido",
      perigo: true,
    });
    if (!confirmado) return;
    try {
      await api.cancelarPedido(pedido.id);
      avisar("Pedido cancelado.", "success");
      await carregar();
    } catch (erro) {
      avisar(erro.message, "error", 6000);
    }
  }

  function desenhar({ pedido, historico, pagamento }) {
    const aberto = !ESTADOS_FINAIS.includes(pedido.status);

    const resumo = card({
      titulo: "Resumo",
      filhos: [
        el(
          "dl",
          { class: "resumo" },
          item("Cliente", pedido.customerId),
          item("Status", seloStatus(pedido.status)),
          item("Total", el("span", { class: "valor-grande" }, moeda(pedido.total))),
          item("Criado em", dataHora(pedido.createdAt)),
          pedido.cancelReason ? item("Motivo do cancelamento", pedido.cancelReason) : null,
        ),
      ],
    });

    const itens = card({
      titulo: "Itens",
      filhos: [
        el(
          "div",
          { class: "table-wrap" },
          el(
            "table",
            { class: "table" },
            el("thead", {}, el("tr", {}, el("th", {}, "Produto"), el("th", { class: "num" }, "Qtd."), el("th", { class: "num" }, "Unitário"), el("th", { class: "num" }, "Subtotal"))),
            el(
              "tbody",
              {},
              pedido.items.map((i) =>
                el(
                  "tr",
                  {},
                  el("td", {}, nomes.get(i.productId) || i.productId, " ", el("span", { class: "mono" }, i.productId)),
                  el("td", { class: "num" }, String(i.quantity)),
                  el("td", { class: "num" }, moeda(i.unitPrice)),
                  el("td", { class: "num" }, moeda(Number(i.unitPrice) * i.quantity)),
                ),
              ),
            ),
          ),
        ),
      ],
    });

    const painelPagamento = card({
      titulo: "Pagamento",
      filhos: [
        pagamento
          ? el(
              "dl",
              { class: "resumo" },
              item("Situação", seloPagamento(pagamento.status)),
              item("Valor", moeda(pagamento.amount)),
              pagamento.failureReason ? item("Motivo", pagamento.failureReason) : null,
              pagamento.gatewayRef ? item("Referência no gateway", el("span", { class: "mono" }, pagamento.gatewayRef)) : null,
            )
          : estadoVazio({ icone: "dollar", titulo: "Ainda não há cobrança", texto: "A cobrança é feita depois que o estoque é reservado." }),
      ],
    });

    const linhaDoTempo = card({
      titulo: "Linha do tempo",
      subtitulo: aberto ? "Atualizando automaticamente" : "Saga concluída",
      filhos: [
        el(
          "ol",
          { class: "linha-tempo" },
          historico.map((passo) =>
            el(
              "li",
              { class: "linha-tempo__item" },
              el("div", { class: "linha-tempo__titulo" }, ROTULOS_GATILHO[passo.trigger] || passo.trigger),
              el("div", { class: "linha-tempo__meta" }, dataHora(passo.at)),
            ),
          ),
        ),
      ],
    });

    raiz.replaceChildren(
      voltar(),
      el(
        "div",
        { class: "page-head" },
        el("div", {}, el("h1", {}, `Pedido ${curto(pedido.id)}`), el("p", { class: "page-head__sub mono" }, pedido.id)),
        aberto ? el("div", { class: "page-head__acoes" }, botao({ rotulo: "Cancelar pedido", icone: "x", variante: "danger", onClick: () => cancelar(pedido) })) : null,
      ),
      el("div", { class: "grid grid--dash" }, el("div", { class: "grid" }, resumo, itens), el("div", { class: "grid" }, painelPagamento, linhaDoTempo)),
    );
  }

  await carregar();
  ctx.atualizarACada(2000, carregar);
}

function voltar() {
  return el("a", { class: "voltar", href: "#/pedidos" }, icon("arrowLeft", 16), "Voltar para pedidos");
}

function item(rotulo, valor) {
  return el("div", { class: "resumo__item" }, el("dt", {}, rotulo), el("dd", {}, valor));
}
