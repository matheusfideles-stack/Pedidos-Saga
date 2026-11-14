import { el } from "../dom.js";
import { icon } from "../icons.js";

const ICONES = { success: "check", error: "alert", info: "activity" };

/** Aviso flutuante. tipo: success | error | info. Erros usam role="alert" (lido imediatamente por leitores de tela). */
export function avisar(mensagem, tipo = "info", duracaoMs = 4500) {
  const host = document.getElementById("toasts");
  if (!host) return;

  const fechar = () => node.remove();
  const node = el(
    "div",
    { class: `toast toast--${tipo}`, role: tipo === "error" ? "alert" : "status" },
    icon(ICONES[tipo] || "activity", 18),
    el("span", { class: "toast__msg" }, mensagem),
    el("button", { class: "toast__close", type: "button", "aria-label": "Fechar aviso", onclick: fechar }, icon("x", 14)),
  );
  host.append(node);
  if (duracaoMs > 0) setTimeout(fechar, duracaoMs);
}
