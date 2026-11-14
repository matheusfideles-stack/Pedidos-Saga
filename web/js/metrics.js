// Métricas do dashboard calculadas a partir dos dados REAIS da API. Funções puras (sem DOM, sem rede, sem relógio
// implícito: o instante atual entra por parâmetro), por isso testáveis em web/tests/metrics.test.js.

const HORA_MS = 3_600_000;

export const STATUS_ABERTOS = ["PENDING", "INVENTORY_RESERVED", "PAYMENT_AUTHORIZED"];
export const ESTOQUE_BAIXO = 100;

const inicioDaHora = (ms) => {
  const d = new Date(ms);
  d.setMinutes(0, 0, 0);
  return d.getTime();
};

const inicioDoDia = (ms) => {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
};

/** Pedidos (e valor confirmado) por hora nas últimas `horas` horas, da mais antiga para a mais recente. */
export function pedidosPorHora(pedidos, agora = Date.now(), horas = 8) {
  const fim = inicioDaHora(agora) + HORA_MS;
  const baldes = Array.from({ length: horas }, (_, i) => ({
    inicio: fim - (horas - i) * HORA_MS,
    quantidade: 0,
    valorConfirmado: 0,
  }));
  for (const pedido of pedidos) {
    const indice = Math.floor((Date.parse(pedido.createdAt) - baldes[0].inicio) / HORA_MS);
    if (indice < 0 || indice >= horas) continue;
    baldes[indice].quantidade += 1;
    if (pedido.status === "CONFIRMED") baldes[indice].valorConfirmado += Number(pedido.total);
  }
  return baldes;
}

/** Totais usados nos cards. `taxaConfirmacao` considera só pedidos já finalizados; é null se ainda não há nenhum. */
export function resumir(pedidos, agora = Date.now()) {
  const inicioHoje = inicioDoDia(agora);
  const resumo = { total: pedidos.length, hoje: 0, abertos: 0, confirmados: 0, cancelados: 0, valorConfirmado: 0 };
  for (const pedido of pedidos) {
    if (Date.parse(pedido.createdAt) >= inicioHoje) resumo.hoje += 1;
    if (STATUS_ABERTOS.includes(pedido.status)) resumo.abertos += 1;
    if (pedido.status === "CONFIRMED") {
      resumo.confirmados += 1;
      resumo.valorConfirmado += Number(pedido.total);
    }
    if (pedido.status === "CANCELLED") resumo.cancelados += 1;
  }
  const finalizados = resumo.confirmados + resumo.cancelados;
  resumo.taxaConfirmacao = finalizados === 0 ? null : resumo.confirmados / finalizados;
  return resumo;
}

/** Variação relativa. Retorna null quando não há base de comparação (anterior = 0 e atual > 0). */
export function variacao(atual, anterior) {
  if (anterior === 0) return atual === 0 ? 0 : null;
  return (atual - anterior) / anterior;
}

/**
 * Compara os últimos 60 minutos com os 60 minutos anteriores (janela móvel). Evita comparar uma hora que mal começou
 * com uma hora completa. `campo`: "quantidade" (nº de pedidos) ou "valorConfirmado" (soma dos confirmados).
 */
export function variacaoJanelaMovel(pedidos, campo, agora = Date.now()) {
  const limiteRecente = agora - HORA_MS;
  const limiteAnterior = agora - 2 * HORA_MS;
  let atual = 0;
  let anterior = 0;
  for (const pedido of pedidos) {
    const instante = Date.parse(pedido.createdAt);
    if (instante > agora) continue;
    const valor = campo === "quantidade" ? 1 : pedido.status === "CONFIRMED" ? Number(pedido.total) : 0;
    if (instante > limiteRecente) atual += valor;
    else if (instante > limiteAnterior) anterior += valor;
  }
  return variacao(atual, anterior);
}

export function estoqueMaisBaixo(niveis, limite = 4) {
  return [...niveis].sort((a, b) => a.available - b.available).slice(0, limite);
}

/** Avisos do sino: estoque baixo e cancelamentos nas últimas 24 h. */
export function montarAvisos(niveis, pedidos, agora = Date.now(), limiteEstoque = ESTOQUE_BAIXO) {
  const avisos = [];
  for (const nivel of niveis) {
    if (nivel.available < limiteEstoque) {
      avisos.push({ tipo: "estoque", produto: nivel.productId, disponivel: nivel.available, destino: "#/estoque" });
    }
  }
  const limite = agora - 24 * HORA_MS;
  const cancelados = pedidos.filter((p) => p.status === "CANCELLED" && Date.parse(p.createdAt) >= limite);
  if (cancelados.length > 0) {
    avisos.push({ tipo: "cancelados", quantidade: cancelados.length, destino: "#/pedidos" });
  }
  return avisos;
}

/** Contagem por grupo de status para a barra empilhada: confirmados, cancelados e em andamento. */
export function distribuicaoPorStatus(pedidos) {
  const resumo = resumir(pedidos);
  return [
    { chave: "CONFIRMED", quantidade: resumo.confirmados },
    { chave: "aberto", quantidade: resumo.abertos },
    { chave: "CANCELLED", quantidade: resumo.cancelados },
  ];
}
