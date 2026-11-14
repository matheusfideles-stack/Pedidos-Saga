// Configurações do painel. São preferências deste navegador (localStorage), não dados do sistema.
// Toda leitura do storage é protegida: em aba privada ou com o armazenamento bloqueado, valem os padrões.

const CHAVE = "marketplace.configuracoes.v1";

export const PADROES = Object.freeze({
  atualizacaoAutomatica: true,
  limiteEstoqueBaixo: 100,
  mostrarFoto: true,
});

export const LIMITE_MAXIMO_ESTOQUE = 10000;

/** Garante tipos e faixas válidos, venha o valor do formulário ou do storage. */
export function sanear(entrada = {}) {
  const limite = Math.round(Number(entrada.limiteEstoqueBaixo));
  return {
    atualizacaoAutomatica: entrada.atualizacaoAutomatica === undefined ? PADROES.atualizacaoAutomatica : Boolean(entrada.atualizacaoAutomatica),
    limiteEstoqueBaixo: Number.isFinite(limite) ? Math.min(LIMITE_MAXIMO_ESTOQUE, Math.max(0, limite)) : PADROES.limiteEstoqueBaixo,
    mostrarFoto: entrada.mostrarFoto === undefined ? PADROES.mostrarFoto : Boolean(entrada.mostrarFoto),
  };
}

function lerDoStorage() {
  try {
    return sanear({ ...PADROES, ...JSON.parse(localStorage.getItem(CHAVE) || "{}") });
  } catch {
    return sanear(PADROES);
  }
}

let atual = lerDoStorage();
const ouvintes = new Set();

function gravar(proximo) {
  atual = sanear(proximo);
  try {
    localStorage.setItem(CHAVE, JSON.stringify(atual));
  } catch {
    // sem storage a preferência vale só nesta sessão
  }
  ouvintes.forEach((ouvir) => ouvir(atual));
}

export const configuracoes = {
  obter: () => atual,
  alterar: (parcial) => gravar({ ...atual, ...parcial }),
  restaurarPadroes: () => gravar({ ...PADROES }),
  observar(ouvir) {
    ouvintes.add(ouvir);
    return () => ouvintes.delete(ouvir);
  },
};

/** Aplica a aparência escolhida no documento (a foto some por CSS, sem recarregar nada). */
export function aplicarAparencia(valores = atual) {
  document.body.classList.toggle("sem-foto", !valores.mostrarFoto);
}
