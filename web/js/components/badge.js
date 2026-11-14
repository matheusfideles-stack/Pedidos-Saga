import { el } from "../dom.js";
import { ROTULOS_PAGAMENTO, ROTULOS_STATUS } from "../format.js";

/** Selo de status do pedido (cor por estado da saga). */
export function seloStatus(status) {
  return el("span", { class: `badge badge--${status}` }, ROTULOS_STATUS[status] || status);
}

/** Selo de situação do pagamento. */
export function seloPagamento(status) {
  return el("span", { class: `badge badge--${status}` }, ROTULOS_PAGAMENTO[status] || status);
}

/** Selo genérico. tom: ok | warn | danger | neutro */
export function selo(texto, tom = "neutro") {
  return el("span", { class: `badge badge--${tom}` }, texto);
}
