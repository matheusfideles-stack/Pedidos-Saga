import { el } from "../dom.js";

/**
 * Menu suspenso ancorado em um gatilho. O painel é recriado a cada abertura (dados sempre atuais),
 * fecha com ESC, clique fora ou novo clique no gatilho, e expõe aria-expanded ao gatilho.
 */
export function menuSuspenso({ gatilho, criarPainel }) {
  const raiz = el("div", { class: "dropdown" }, gatilho);
  let painel = null;

  const fechar = () => {
    if (!painel) return;
    painel.remove();
    painel = null;
    gatilho.setAttribute("aria-expanded", "false");
    document.removeEventListener("mousedown", aoClicarFora, true);
    document.removeEventListener("keydown", aoTeclar, true);
  };

  function aoClicarFora(evento) {
    if (!raiz.contains(evento.target)) fechar();
  }

  function aoTeclar(evento) {
    if (evento.key === "Escape") {
      fechar();
      gatilho.focus();
    }
  }

  const abrir = () => {
    painel = el("div", { class: "dropdown__painel", role: "menu" }, criarPainel(fechar));
    raiz.append(painel);
    gatilho.setAttribute("aria-expanded", "true");
    document.addEventListener("mousedown", aoClicarFora, true);
    document.addEventListener("keydown", aoTeclar, true);
  };

  gatilho.setAttribute("aria-haspopup", "true");
  gatilho.setAttribute("aria-expanded", "false");
  gatilho.addEventListener("click", () => (painel ? fechar() : abrir()));

  return raiz;
}
