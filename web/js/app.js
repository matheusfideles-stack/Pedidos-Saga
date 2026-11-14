// Ponto de entrada: monta o esqueleto e roteia por hash (#/inicio, #/pedidos/<id>, ...).
import { montarShell } from "./shell.js";
import { estadoErro } from "./components/states.js";
import { telaCatalogo } from "./screens/catalogo.js";
import { telaEstoque } from "./screens/estoque.js";
import { telaInicio } from "./screens/inicio.js";
import { telaNovoPedido } from "./screens/novo.js";
import { telaPedido } from "./screens/pedido.js";
import { telaPedidos } from "./screens/pedidos.js";
import { telaConfiguracoes } from "./screens/configuracoes.js";
import { aplicarAparencia, configuracoes } from "./settings.js";
import { carregarFundoSalvo } from "./fundo.js";

const ROTAS = [
  { padrao: /^\/inicio$/, secao: "inicio", titulo: "Início", tela: telaInicio },
  { padrao: /^\/novo$/, secao: "novo", titulo: "Novo pedido", tela: telaNovoPedido },
  { padrao: /^\/pedidos$/, secao: "pedidos", titulo: "Pedidos", tela: telaPedidos },
  { padrao: /^\/pedidos\/([0-9a-f-]{36})$/i, secao: "pedidos", titulo: "Pedido", tela: telaPedido, parametros: (m) => ({ id: m[1] }) },
  { padrao: /^\/estoque$/, secao: "estoque", titulo: "Estoque", tela: telaEstoque },
  { padrao: /^\/catalogo$/, secao: "catalogo", titulo: "Catálogo", tela: telaCatalogo },
  { padrao: /^\/configuracoes$/, secao: "configuracoes", titulo: "Configurações", tela: telaConfiguracoes },
];

/** Contexto de uma tela: ciclo de vida (ativo/encerrar), navegação e atualização periódica que para ao sair da tela. */
function criarContexto(parametros) {
  let ativo = true;
  let temporizador = null;

  const pararDeAtualizar = () => {
    clearInterval(temporizador);
    temporizador = null;
  };

  return {
    parametros,
    ativo: () => ativo,
    ir: (hash) => {
      location.hash = hash;
    },
    atualizarACada(ms, funcao) {
      pararDeAtualizar();
      temporizador = setInterval(() => {
        if (!document.hidden && configuracoes.obter().atualizacaoAutomatica) funcao();
      }, ms);
    },
    pararDeAtualizar,
    encerrar() {
      ativo = false;
      pararDeAtualizar();
    },
  };
}

aplicarAparencia();
carregarFundoSalvo();
configuracoes.observar(() => aplicarAparencia());

const shell = montarShell(document.getElementById("raiz"));
let contextoAtual = null;

async function navegar() {
  contextoAtual?.encerrar();

  const caminho = location.hash.slice(1) || "/inicio";
  let parametros = {};
  const rota = ROTAS.find((r) => {
    const resultado = caminho.match(r.padrao);
    if (resultado && r.parametros) parametros = r.parametros(resultado);
    return resultado;
  });
  if (!rota) {
    location.hash = "#/inicio";
    return;
  }

  shell.marcarAtivo(rota.secao);
  document.title = `${rota.titulo} · Marketplace de Pedidos`;

  const contexto = criarContexto(parametros);
  contextoAtual = contexto;

  shell.tela.classList.remove("content"); // reinicia a animação de entrada
  void shell.tela.offsetWidth;
  shell.tela.classList.add("content");
  window.scrollTo({ top: 0 });

  try {
    await rota.tela(shell.tela, contexto);
  } catch (erro) {
    if (contexto.ativo()) shell.tela.replaceChildren(estadoErro({ mensagem: erro.message, aoTentarNovamente: navegar }));
  }
}

window.addEventListener("hashchange", navegar);
navegar();
