import { el, svgEl } from "../dom.js";

let contadorDeGradientes = 0;

/**
 * Linha de tendência com área suave. `valores` são números reais; com menos de 2 pontos desenha só uma linha plana.
 */
export function tendencia(valores, { tom = "azul", rotulo = "Tendência" } = {}) {
  const largura = 96;
  const altura = 36;
  const margem = 3;
  const serie = valores.length >= 2 ? valores : [0, 0];
  const maximo = Math.max(...serie, 1);
  const passo = (largura - margem * 2) / (serie.length - 1);
  const pontos = serie.map((v, i) => [margem + i * passo, altura - margem - (v / maximo) * (altura - margem * 2)]);
  const linha = pontos.map(([x, y], i) => `${i === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`).join(" ");
  const area = `${linha} L${pontos[pontos.length - 1][0].toFixed(1)},${altura} L${pontos[0][0].toFixed(1)},${altura} Z`;

  const idGradiente = `tendencia-${contadorDeGradientes++}`;
  const cor = tom === "verde" ? "#4ade80" : "#60a5fa";

  return svgEl(
    "svg",
    { class: "spark", viewBox: `0 0 ${largura} ${altura}`, role: "img", "aria-label": rotulo, preserveAspectRatio: "none" },
    svgEl(
      "defs",
      {},
      svgEl(
        "linearGradient",
        { id: idGradiente, x1: 0, y1: 0, x2: 0, y2: 1 },
        svgEl("stop", { offset: "0%", "stop-color": cor, "stop-opacity": 0.55 }),
        svgEl("stop", { offset: "100%", "stop-color": cor, "stop-opacity": 0 }),
      ),
    ),
    svgEl("path", { d: area, fill: `url(#${idGradiente})` }),
    svgEl("path", { d: linha, fill: "none", stroke: cor, "stroke-width": 2, "stroke-linecap": "round", "stroke-linejoin": "round" }),
  );
}

/**
 * Gráfico de barras. `dados`: [{ rotulo, valor, dica }]. A maior barra recebe o destaque azul (como na referência);
 * as demais ficam em vidro. Cada coluna é focável por teclado e mostra a dica no foco ou no hover.
 */
export function barras(dados, { rotuloAcessivel = "Gráfico de barras" } = {}) {
  const maximo = Math.max(...dados.map((d) => d.valor), 0);
  const indiceDestaque = maximo > 0 ? dados.findIndex((d) => d.valor === maximo) : -1;

  return el(
    "div",
    { class: "barras", role: "img", "aria-label": rotuloAcessivel },
    dados.map((dado, indice) => {
      const altura = maximo === 0 ? 0 : (dado.valor / maximo) * 100;
      return el(
        "div",
        { class: "barras__col", tabindex: "0", "aria-label": dado.dica },
        el(
          "div",
          { class: "barras__trilho" },
          el("div", { class: "barras__dica", role: "tooltip" }, dado.dica),
          el("div", {
            class: `barras__barra${indice === indiceDestaque ? " barras__barra--destaque" : ""}`,
            style: `height:${altura}%;animation-delay:${indice * 45}ms`,
          }),
        ),
        el("span", { class: "barras__rotulo" }, dado.rotulo),
      );
    }),
  );
}

/** Anel de percentual (0 a 1). */
export function anel(fracao, { tom = "azul", rotuloAcessivel } = {}) {
  const raio = 38;
  const circunferencia = 2 * Math.PI * raio;
  const valor = Math.min(Math.max(fracao, 0), 1);
  return el(
    "div",
    { class: "anel", role: "img", "aria-label": rotuloAcessivel || `${Math.round(valor * 100)}%` },
    svgEl(
      "svg",
      { viewBox: "0 0 96 96", width: 96, height: 96 },
      svgEl("circle", { class: "anel__trilho", cx: 48, cy: 48, r: raio }),
      svgEl("circle", {
        class: `anel__valor${tom === "verde" ? " anel__valor--ok" : ""}`,
        cx: 48,
        cy: 48,
        r: raio,
        "stroke-dasharray": circunferencia.toFixed(2),
        "stroke-dashoffset": (circunferencia * (1 - valor)).toFixed(2),
      }),
    ),
    el("span", { class: "anel__texto" }, `${Math.round(valor * 100)}%`),
  );
}
