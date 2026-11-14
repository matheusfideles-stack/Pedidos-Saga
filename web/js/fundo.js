// Foto de fundo escolhida pelo usuário. Fica no IndexedDB deste navegador (um navegador não grava na pasta do projeto).
// Sem escolha, vale o arquivo padrão web/fundo.webp.

const BANCO = "marketplace-visual";
const LOJA = "arquivos";
const CHAVE = "fundo";
const TIPOS_ACEITOS = ["image/jpeg", "image/png", "image/webp"];
export const TAMANHO_MAXIMO_BYTES = 10 * 1024 * 1024;
export const LARGURA_RECOMENDADA = 2560;
export const LARGURA_MINIMA_SEM_AVISO = 1920;

let urlAtual = null;

function abrirBanco() {
  return new Promise((resolver, rejeitar) => {
    const pedido = indexedDB.open(BANCO, 1);
    pedido.onupgradeneeded = () => pedido.result.createObjectStore(LOJA);
    pedido.onsuccess = () => resolver(pedido.result);
    pedido.onerror = () => rejeitar(pedido.error);
  });
}

async function operar(modo, executar) {
  const banco = await abrirBanco();
  try {
    return await new Promise((resolver, rejeitar) => {
      const transacao = banco.transaction(LOJA, modo);
      const resultado = executar(transacao.objectStore(LOJA));
      transacao.oncomplete = () => resolver(resultado.result);
      transacao.onerror = () => rejeitar(transacao.error);
    });
  } finally {
    banco.close();
  }
}

/** Valida tipo e tamanho. Lança erro com mensagem para o usuário. */
export function validarArquivo(arquivo) {
  if (!TIPOS_ACEITOS.includes(arquivo.type)) throw new Error("Use uma imagem JPG, PNG ou WebP.");
  if (arquivo.size > TAMANHO_MAXIMO_BYTES) throw new Error("A imagem passa de 10 MB. Reduza o tamanho e tente de novo.");
}

/** Largura da imagem, para avisar quando for pequena demais para a tela. */
export async function medirLargura(arquivo) {
  const bitmap = await createImageBitmap(arquivo);
  const largura = bitmap.width;
  bitmap.close();
  return largura;
}

function aplicarUrl(url) {
  if (urlAtual) URL.revokeObjectURL(urlAtual);
  urlAtual = url;
  if (url) document.documentElement.style.setProperty("--fundo-foto", `url("${url}")`);
  else document.documentElement.style.removeProperty("--fundo-foto");
}

export async function salvarFundo(arquivo) {
  validarArquivo(arquivo);
  await operar("readwrite", (loja) => loja.put(arquivo, CHAVE));
  aplicarUrl(URL.createObjectURL(arquivo));
}

export async function removerFundo() {
  await operar("readwrite", (loja) => loja.delete(CHAVE));
  aplicarUrl(null);
}

/** Carrega a foto salva, se existir. Falhas (navegador sem IndexedDB, aba privada) mantêm o padrão. */
export async function carregarFundoSalvo() {
  try {
    const arquivo = await operar("readonly", (loja) => loja.get(CHAVE));
    if (arquivo) aplicarUrl(URL.createObjectURL(arquivo));
    return Boolean(arquivo);
  } catch {
    return false;
  }
}

/** Miniatura para a tela de configurações. Retorna a URL, ou null se não houver foto escolhida. */
export function urlDaFotoEscolhida() {
  return urlAtual;
}
