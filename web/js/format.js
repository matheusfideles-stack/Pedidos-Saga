// Formatação para exibição (pt-BR). Funções puras: não tocam no DOM.

const moedaBRL = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });
const numero = new Intl.NumberFormat("pt-BR");

export const moeda = (valor) => moedaBRL.format(Number(valor));
export const inteiro = (valor) => numero.format(Number(valor));
export const percentual = (fracao, casas = 0) => `${(fracao * 100).toFixed(casas).replace(".", ",")}%`;
export const curto = (id) => String(id).slice(0, 8);

export const dataHora = (iso) => new Date(iso).toLocaleString("pt-BR");
export const hora = (ms) => new Date(ms).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" });

export function saudacao(agora = new Date()) {
  const h = agora.getHours();
  if (h < 5) return "Boa madrugada";
  if (h < 12) return "Bom dia";
  if (h < 18) return "Boa tarde";
  return "Boa noite";
}

/** "agora", "há 5 min", "há 3 h", "há 2 d" a partir de um instante ISO. */
export function haQuantoTempo(iso, agora = Date.now()) {
  const segundos = Math.max(0, Math.round((agora - Date.parse(iso)) / 1000));
  if (segundos < 45) return "agora";
  const minutos = Math.round(segundos / 60);
  if (minutos < 60) return `há ${minutos} min`;
  const horas = Math.round(minutos / 60);
  if (horas < 24) return `há ${horas} h`;
  return `há ${Math.round(horas / 24)} d`;
}

export const ROTULOS_STATUS = {
  PENDING: "Pendente",
  INVENTORY_RESERVED: "Estoque reservado",
  PAYMENT_AUTHORIZED: "Pagamento autorizado",
  CONFIRMED: "Confirmado",
  CANCELLED: "Cancelado",
};

export const ROTULOS_PAGAMENTO = {
  PENDING: "Pendente",
  AUTHORIZED: "Autorizado",
  DECLINED: "Recusado",
  CANCELLED: "Cancelado",
};

export const ROTULOS_GATILHO = {
  ORDER_CREATED: "Pedido criado",
  INVENTORY_RESERVED: "Estoque reservado",
  INVENTORY_REJECTED: "Estoque insuficiente",
  PAYMENT_AUTHORIZED: "Pagamento autorizado",
  PAYMENT_DECLINED: "Pagamento recusado",
  CONFIRM: "Pedido confirmado",
  CUSTOMER_CANCEL: "Cancelado pelo cliente",
};
