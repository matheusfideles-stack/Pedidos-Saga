import { el } from "../dom.js";

let contador = 0;

/**
 * Campo de formulário com rótulo, dica e mensagem de erro.
 * Retorna { raiz, controle, mostrarErro, limparErro } para o formulário validar visualmente.
 */
export function campo({ rotulo, controle, dica }) {
  const id = `campo-${++contador}`;
  controle.id = id;

  const idErro = `${id}-erro`;
  const erro = el("span", { class: "field__erro", id: idErro, role: "alert" });
  controle.setAttribute("aria-describedby", idErro);

  const raiz = el(
    "div",
    { class: "field" },
    el("label", { class: "field__label", for: id }, rotulo),
    controle,
    dica ? el("span", { class: "field__hint" }, dica) : null,
    erro,
  );

  const mostrarErro = (mensagem) => {
    controle.setAttribute("aria-invalid", "true");
    erro.textContent = mensagem;
  };
  const limparErro = () => {
    controle.removeAttribute("aria-invalid");
    erro.textContent = "";
  };
  controle.addEventListener("input", limparErro);
  controle.addEventListener("change", limparErro);

  return { raiz, controle, mostrarErro, limparErro };
}

export const entrada = (props = {}) => el("input", { class: "input", ...props });

export const selecao = (opcoes, props = {}) =>
  el("select", { class: "select", ...props }, opcoes.map(({ valor, rotulo }) => el("option", { value: valor }, rotulo)));
