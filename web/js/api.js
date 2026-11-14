// Acesso à API (mesma origem, via gateway). Reporta se a API está acessível para o indicador do cabeçalho.

let aoMudarStatus = () => {};

export function observarStatusDaApi(callback) {
  aoMudarStatus = callback;
}

async function chamar(metodo, url, { corpo, cabecalhos = {} } = {}) {
  let resposta;
  try {
    resposta = await fetch(url, {
      method: metodo,
      headers: { "Content-Type": "application/json", ...cabecalhos },
      body: corpo === undefined ? undefined : JSON.stringify(corpo),
    });
  } catch (erro) {
    aoMudarStatus(false);
    throw new Error("Não foi possível falar com a API. Verifique a conexão.");
  }
  aoMudarStatus(true);

  const texto = await resposta.text();
  const dados = texto ? JSON.parse(texto) : null;
  if (!resposta.ok) {
    const erro = new Error(dados?.detail || `Erro ${resposta.status}`);
    erro.status = resposta.status;
    throw erro;
  }
  return dados;
}

export const api = {
  listarPedidos: (limite = 200) => chamar("GET", `/orders?limit=${limite}`),
  pedido: (id) => chamar("GET", `/orders/${id}`),
  historico: (id) => chamar("GET", `/orders/${id}/history`),
  pagamento: async (id) => {
    try {
      return await chamar("GET", `/payments/by-order/${id}`);
    } catch (erro) {
      if (erro.status === 404) return null; // ainda não há cobrança para este pedido
      throw erro;
    }
  },
  criarPedido: (corpo, chave) => chamar("POST", "/orders", { corpo, cabecalhos: { "Idempotency-Key": chave } }),
  cancelarPedido: (id) => chamar("POST", `/orders/${id}/cancel`),
  produtos: () => chamar("GET", "/catalog/products"),
  atualizarProduto: (id, corpo) => chamar("PUT", `/catalog/products/${id}`, { corpo }),
  estoque: () => chamar("GET", "/inventory"),
  estatisticasDoCache: () => chamar("GET", "/catalog/products/cache/stats"),
};
