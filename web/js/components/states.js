import { el } from "../dom.js";
import { icon } from "../icons.js";
import { botao } from "./button.js";

/** Esqueleto de carregamento: evita saltos de layout enquanto a API responde. */
export function esqueleto(linhas = 4) {
  return el(
    "div",
    { "aria-busy": "true", "aria-label": "Carregando" },
    Array.from({ length: linhas }, (_, i) => el("span", { class: "skeleton skeleton--linha", style: `width:${92 - i * 9}%` })),
  );
}

export function esqueletoBloco() {
  return el("span", { class: "skeleton skeleton--bloco", "aria-busy": "true", "aria-label": "Carregando" });
}

/** Estado vazio: explica o que falta e, se houver, oferece a próxima ação. */
export function estadoVazio({ icone = "inbox", titulo, texto, acao }) {
  return el(
    "div",
    { class: "estado" },
    el("div", { class: "estado__icone" }, icon(icone, 24)),
    el("div", { class: "estado__titulo" }, titulo),
    texto ? el("p", {}, texto) : null,
    acao || null,
  );
}

/** Estado de erro com tentativa de novo. */
export function estadoErro({ mensagem, aoTentarNovamente }) {
  return el(
    "div",
    { class: "estado estado--erro", role: "alert" },
    el("div", { class: "estado__icone" }, icon("alert", 24)),
    el("div", { class: "estado__titulo" }, "Não foi possível carregar"),
    el("p", {}, mensagem),
    aoTentarNovamente ? botao({ rotulo: "Tentar novamente", icone: "refresh", variante: "ghost", onClick: aoTentarNovamente }) : null,
  );
}
