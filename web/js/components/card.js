import { el } from "../dom.js";
import { icon } from "../icons.js";

/** Card de vidro com cabeçalho opcional (título, subtítulo e ações à direita). */
export function card({ titulo, subtitulo, acoes, filhos = [], classe = "" } = {}) {
  const cabecalho =
    titulo || acoes
      ? el(
          "div",
          { class: "card__head" },
          el("div", {}, titulo ? el("h2", { class: "card__title" }, titulo) : null, subtitulo ? el("p", { class: "card__sub" }, subtitulo) : null),
          acoes || null,
        )
      : null;
  return el("section", { class: `card ${classe}`.trim() }, cabecalho, el("div", { class: "card__body" }, filhos));
}

/** Indicador (KPI): rótulo com ícone, valor grande, tendência opcional e rodapé com variação. */
export function indicador({ rotulo, icone, tom = "", valor, tendenciaSvg, variacaoNode, rodape }) {
  return el(
    "section",
    { class: "card card--interativo kpi" },
    el(
      "div",
      { class: "kpi__topo" },
      el("span", { class: "kpi__rotulo" }, el("span", { class: `kpi__icone${tom ? ` kpi__icone--${tom}` : ""}` }, icon(icone, 16)), rotulo),
    ),
    el("div", { class: "kpi__linha" }, el("span", { class: "kpi__valor" }, valor), tendenciaSvg || null),
    el("div", { class: "kpi__rodape" }, variacaoNode || null, rodape ? el("span", {}, rodape) : null),
  );
}

/** Selo de variação (+12% / -5% / estável). `fracao` null significa sem base de comparação. */
export function seloVariacao(fracao, rotuloSemBase = "sem base") {
  if (fracao === null) return el("span", { class: "delta delta--flat" }, rotuloSemBase);
  if (fracao === 0) return el("span", { class: "delta delta--flat" }, "estável");
  const subiu = fracao > 0;
  const texto = `${subiu ? "+" : ""}${Math.round(fracao * 100)}%`;
  return el("span", { class: `delta ${subiu ? "delta--up" : "delta--down"}` }, icon(subiu ? "trendingUp" : "trendingDown", 12), texto);
}

/** Cabeçalho de página: título, descrição e ações. */
export function cabecalhoDePagina({ titulo, descricao, acoes }) {
  return el(
    "div",
    { class: "page-head" },
    el("div", {}, el("h1", {}, titulo), descricao ? el("p", { class: "page-head__sub" }, descricao) : null),
    acoes ? el("div", { class: "page-head__acoes" }, acoes) : null,
  );
}
