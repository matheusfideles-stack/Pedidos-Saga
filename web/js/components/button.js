import { el } from "../dom.js";
import { icon } from "../icons.js";

/** Executa `acao` com o botão em estado de carregamento (desabilitado, com spinner), sempre restaurando ao final. */
export async function comCarregamento(botao, acao) {
  if (botao.disabled) return undefined;
  botao.disabled = true;
  botao.classList.add("is-loading");
  botao.setAttribute("aria-busy", "true");
  try {
    return await acao();
  } finally {
    botao.disabled = false;
    botao.classList.remove("is-loading");
    botao.removeAttribute("aria-busy");
  }
}

/**
 * Botão padrão. `onClick` pode ser assíncrono: o botão mostra carregamento até terminar.
 * variantes: primary | ghost | danger
 */
export function botao({ rotulo, icone, variante = "primary", tipo = "button", onClick, titulo, pequeno = false } = {}) {
  const apenasIcone = !rotulo;
  const node = el(
    "button",
    {
      class: `btn btn--${variante}${pequeno ? " btn--sm" : ""}${apenasIcone ? " btn--icon" : ""}`,
      type: tipo,
      title: titulo,
      "aria-label": apenasIcone ? titulo : undefined,
    },
    icone ? icon(icone, 16) : null,
    rotulo ? el("span", { class: "btn__label" }, rotulo) : null,
  );
  if (onClick) node.addEventListener("click", (evento) => comCarregamento(node, () => onClick(evento)));
  return node;
}
