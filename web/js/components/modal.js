import { el } from "../dom.js";
import { botao } from "./button.js";

const FOCAVEIS = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Modal acessível: role="dialog", foco preso dentro dele, fecha com ESC ou clique fora e devolve o foco ao elemento anterior.
 * `acoes`: [{ rotulo, variante, onClick }]. Se onClick retornar false, o modal permanece aberto (ex.: validação falhou).
 * Retorna { fechar }.
 */
export function abrirModal({ titulo, corpo, acoes = [] }) {
  const anterior = document.activeElement;
  const idTitulo = `modal-${Math.random().toString(36).slice(2)}`;

  const fechar = () => {
    document.removeEventListener("keydown", aoTeclar, true);
    sobreposicao.remove();
    if (anterior instanceof HTMLElement) anterior.focus();
  };

  const botoes = acoes.map((acao) =>
    botao({
      rotulo: acao.rotulo,
      variante: acao.variante || "ghost",
      onClick: async () => {
        const continuar = acao.onClick ? await acao.onClick() : true;
        if (continuar !== false) fechar();
      },
    }),
  );

  const dialogo = el(
    "div",
    { class: "modal", role: "dialog", "aria-modal": "true", "aria-labelledby": idTitulo },
    el("h2", { class: "modal__title", id: idTitulo }, titulo),
    el("div", { class: "modal__body" }, corpo),
    el("div", { class: "modal__acoes" }, botoes),
  );

  const sobreposicao = el("div", { class: "modal-overlay" }, dialogo);
  sobreposicao.addEventListener("mousedown", (evento) => {
    if (evento.target === sobreposicao) fechar();
  });

  function aoTeclar(evento) {
    if (evento.key === "Escape") {
      evento.stopPropagation();
      fechar();
      return;
    }
    if (evento.key !== "Tab") return;
    const itens = [...dialogo.querySelectorAll(FOCAVEIS)];
    if (itens.length === 0) return;
    const primeiro = itens[0];
    const ultimo = itens[itens.length - 1];
    if (evento.shiftKey && document.activeElement === primeiro) {
      evento.preventDefault();
      ultimo.focus();
    } else if (!evento.shiftKey && document.activeElement === ultimo) {
      evento.preventDefault();
      primeiro.focus();
    }
  }

  document.addEventListener("keydown", aoTeclar, true);
  document.body.append(sobreposicao);
  (dialogo.querySelector("input, select") || dialogo.querySelector(FOCAVEIS))?.focus();
  return { fechar };
}

/** Confirmação com Promise<boolean>. Substitui window.confirm. */
export function confirmar({ titulo, mensagem, rotuloConfirmar = "Confirmar", perigo = false }) {
  return new Promise((resolver) => {
    abrirModal({
      titulo,
      corpo: el("p", {}, mensagem),
      acoes: [
        { rotulo: "Voltar", variante: "ghost", onClick: () => resolver(false) },
        { rotulo: rotuloConfirmar, variante: perigo ? "danger" : "primary", onClick: () => resolver(true) },
      ],
    });
  });
}
