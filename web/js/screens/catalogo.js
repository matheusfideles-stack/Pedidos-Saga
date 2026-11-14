import { api } from "../api.js";
import { el } from "../dom.js";
import { inteiro, moeda } from "../format.js";
import { selo } from "../components/badge.js";
import { botao } from "../components/button.js";
import { cabecalhoDePagina, card } from "../components/card.js";
import { anel } from "../components/charts.js";
import { campo, entrada } from "../components/form.js";
import { abrirModal } from "../components/modal.js";
import { esqueleto, estadoErro } from "../components/states.js";
import { avisar } from "../components/toast.js";

/** Catálogo: produtos (com edição) e a saúde do cache. Alterar um produto invalida o cache em todas as réplicas, via evento. */
export async function telaCatalogo(raiz, ctx) {
  const corpo = el("div", {}, card({ filhos: [esqueleto(6)] }));
  raiz.replaceChildren(
    cabecalhoDePagina({
      titulo: "Catálogo",
      descricao: "Produtos disponíveis para venda e desempenho do cache de leitura.",
      acoes: botao({ icone: "refresh", variante: "ghost", titulo: "Atualizar agora", onClick: () => carregar() }),
    }),
    corpo,
  );

  async function carregar() {
    try {
      const [produtos, cache] = await Promise.all([api.produtos(), api.estatisticasDoCache().catch(() => null)]);
      if (ctx.ativo()) corpo.replaceChildren(...desenhar(produtos, cache));
    } catch (erro) {
      if (ctx.ativo()) corpo.replaceChildren(estadoErro({ mensagem: erro.message, aoTentarNovamente: carregar }));
    }
  }

  function editar(produto) {
    const nome = campo({ rotulo: "Nome", controle: entrada({ value: produto.name, maxlength: "120" }) });
    const preco = campo({
      rotulo: "Preço (R$)",
      controle: entrada({ type: "number", min: "0", step: "0.01", value: String(produto.price), inputmode: "decimal" }),
      dica: "A alteração publica um evento e invalida o cache de todas as réplicas.",
    });
    const ativo = el("input", { type: "checkbox", ...(produto.active ? { checked: true } : {}) });

    abrirModal({
      titulo: `Editar ${produto.id}`,
      corpo: el("div", { class: "grid" }, nome.raiz, preco.raiz, el("label", { class: "checkbox" }, ativo, "Disponível para venda")),
      acoes: [
        { rotulo: "Cancelar", variante: "ghost" },
        {
          rotulo: "Salvar",
          variante: "primary",
          onClick: async () => {
            const valorPreco = Number(preco.controle.value);
            let valido = true;
            if (nome.controle.value.trim() === "") {
              nome.mostrarErro("Informe o nome.");
              valido = false;
            }
            if (preco.controle.value === "" || !Number.isFinite(valorPreco) || valorPreco < 0) {
              preco.mostrarErro("Informe um preço válido (zero ou mais).");
              valido = false;
            }
            if (!valido) return false;
            try {
              await api.atualizarProduto(produto.id, { name: nome.controle.value.trim(), price: valorPreco, active: ativo.checked });
              avisar("Produto atualizado. O cache será invalidado em instantes.", "success");
              await carregar();
              return true;
            } catch (erro) {
              avisar(erro.message, "error", 6000);
              return false;
            }
          },
        },
      ],
    });
  }

  function desenhar(produtos, cache) {
    const tabela = card({
      titulo: "Produtos",
      subtitulo: `${produtos.length} ativos`,
      filhos: [
        el(
          "div",
          { class: "table-wrap" },
          el(
            "table",
            { class: "table table--cartoes" },
            el("thead", {}, el("tr", {}, el("th", {}, "Código"), el("th", {}, "Produto"), el("th", { class: "num" }, "Preço"), el("th", { class: "num" }, "Versão"), el("th", {}, "Situação"), el("th", {}, ""))),
            el(
              "tbody",
              {},
              produtos.map((p) =>
                el(
                  "tr",
                  {},
                  el("td", { "data-label": "Código", class: "mono" }, p.id),
                  el("td", { "data-label": "Produto" }, p.name),
                  el("td", { "data-label": "Preço", class: "num" }, moeda(p.price)),
                  el("td", { "data-label": "Versão", class: "num" }, String(p.version)),
                  el("td", { "data-label": "Situação" }, p.active ? selo("Ativo", "ok") : selo("Inativo", "neutro")),
                  el("td", {}, botao({ rotulo: "Editar", icone: "edit", variante: "ghost", pequeno: true, onClick: () => editar(p) })),
                ),
              ),
            ),
          ),
        ),
      ],
    });

    const camada = (nome, fracao, acertos, erros, tom) =>
      el(
        "div",
        { class: "metricas" },
        anel(fracao, { tom, rotuloAcessivel: `${nome}: ${Math.round(fracao * 100)}% de acertos` }),
        el("div", {}, el("div", { class: "metricas__nome" }, nome), el("small", {}, `${inteiro(acertos)} acertos · ${inteiro(erros)} erros`)),
      );

    const painelCache = card({
      titulo: "Cache de leitura",
      subtitulo: "L1 em memória + L2 no Redis",
      filhos: cache
        ? [
            camada("L1 · memória", cache.l1HitRatio, cache.l1Hits, cache.l1Misses, "azul"),
            camada("L2 · Redis", cache.l2HitRatio, cache.l2Hits, cache.l2Misses, "verde"),
            el("div", { class: "lista-status", style: "margin-top:16px" },
              el("div", { class: "lista-status__linha" }, el("span", {}, "Leituras no PostgreSQL"), el("strong", {}, inteiro(cache.dbLoads))),
              el("div", { class: "lista-status__linha" }, el("span", {}, "Erros no Redis"), el("strong", {}, inteiro(cache.redisErrors)))),
          ]
        : [el("p", { class: "card__sub" }, "Estatísticas indisponíveis no momento.")],
    });

    return [el("div", { class: "grid grid--dash" }, tabela, painelCache)];
  }

  await carregar();
  ctx.atualizarACada(6000, carregar);
}
