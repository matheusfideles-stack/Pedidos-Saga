import { api } from "../api.js";
import { el } from "../dom.js";
import { icon } from "../icons.js";
import { ROTULOS_STATUS, curto, haQuantoTempo, hora, inteiro, moeda, percentual, saudacao } from "../format.js";
import { distribuicaoPorStatus, estoqueMaisBaixo, pedidosPorHora, resumir, variacaoJanelaMovel } from "../metrics.js";
import { botao } from "../components/button.js";
import { configuracoes } from "../settings.js";
import { barras, anel, tendencia } from "../components/charts.js";
import { cabecalhoDePagina, card, indicador, seloVariacao } from "../components/card.js";
import { esqueletoBloco, estadoErro, estadoVazio } from "../components/states.js";

const HORAS_NO_GRAFICO = 8;

/** Dashboard inicial: indicadores, gráfico por hora, atividade recente, estoque crítico, cache e distribuição por status. */
export async function telaInicio(raiz, ctx) {
  const corpo = el("div");
  const atualizadoEm = el("span", { class: "card__sub" });
  let primeiraVez = true;

  const recarregar = botao({ icone: "refresh", variante: "ghost", titulo: "Atualizar agora", onClick: () => carregar() });
  raiz.replaceChildren(
    cabecalhoDePagina({
      titulo: `${saudacao()}`,
      descricao: "Resumo operacional do marketplace, com dados em tempo real.",
      acoes: [atualizadoEm, recarregar, botao({ rotulo: "Novo pedido", icone: "plus", onClick: () => ctx.ir("#/novo") })],
    }),
    corpo,
  );

  corpo.append(
    el("div", { class: "grid grid--kpi" }, [1, 2, 3, 4].map(() => card({ filhos: [esqueletoBloco()] }))),
    card({ filhos: [esqueletoBloco()] }),
  );

  async function carregar() {
    const [pedidosR, estoqueR, cacheR] = await Promise.allSettled([api.listarPedidos(200), api.estoque(), api.estatisticasDoCache()]);
    if (!ctx.ativo()) return;

    if (pedidosR.status === "rejected") {
      corpo.replaceChildren(estadoErro({ mensagem: pedidosR.reason.message, aoTentarNovamente: carregar }));
      return;
    }

    corpo.replaceChildren(
      ...desenhar({
        pedidos: pedidosR.value,
        niveis: estoqueR.status === "fulfilled" ? estoqueR.value : null,
        cache: cacheR.status === "fulfilled" ? cacheR.value : null,
      }),
    );
    atualizadoEm.textContent = `Atualizado às ${hora(Date.now())}`;
    if (primeiraVez) {
      corpo.classList.add("sem-animacao"); // as barras só animam na primeira carga
      primeiraVez = false;
    }
  }

  await carregar();
  ctx.atualizarACada(6000, carregar);
}

function desenhar({ pedidos, niveis, cache }) {
  const resumo = resumir(pedidos);
  const baldes = pedidosPorHora(pedidos, Date.now(), HORAS_NO_GRAFICO);
  const amostraLimitada = pedidos.length >= 200;

  const quantidades = baldes.map((b) => b.quantidade);
  const valores = baldes.map((b) => b.valorConfirmado);

  const indicadores = el(
    "div",
    { class: "grid grid--kpi" },
    indicador({
      rotulo: "Pedidos hoje",
      icone: "list",
      valor: inteiro(resumo.hoje),
      tendenciaSvg: tendencia(quantidades, { rotulo: "Pedidos por hora" }),
      variacaoNode: seloVariacao(variacaoJanelaMovel(pedidos, "quantidade")),
      rodape: "vs. 60 min anteriores",
    }),
    indicador({
      rotulo: "Valor confirmado",
      icone: "dollar",
      tom: "ok",
      valor: moeda(resumo.valorConfirmado),
      tendenciaSvg: tendencia(valores, { tom: "verde", rotulo: "Valor confirmado por hora" }),
      variacaoNode: seloVariacao(variacaoJanelaMovel(pedidos, "valorConfirmado")),
      rodape: "vs. 60 min anteriores",
    }),
    indicador({
      rotulo: "Em andamento",
      icone: "clock",
      tom: "warn",
      valor: inteiro(resumo.abertos),
      rodape: "aguardando estoque ou pagamento",
    }),
    indicador({
      rotulo: "Taxa de confirmação",
      icone: "check",
      tom: "violet",
      valor: resumo.taxaConfirmacao === null ? "—" : percentual(resumo.taxaConfirmacao, 1),
      rodape: `${inteiro(resumo.confirmados)} confirmados · ${inteiro(resumo.cancelados)} cancelados`,
    }),
  );

  const grafico = card({
    titulo: "Pedidos por hora",
    subtitulo: `Últimas ${HORAS_NO_GRAFICO} horas${amostraLimitada ? " · base: 200 pedidos mais recentes" : ""}`,
    filhos: [
      barras(
        baldes.map((b) => ({
          rotulo: hora(b.inicio).replace(/:00$/, "h"),
          valor: b.quantidade,
          dica: `${b.quantidade} pedido(s) · ${moeda(b.valorConfirmado)} confirmados`,
        })),
        { rotuloAcessivel: "Pedidos por hora nas últimas 8 horas" },
      ),
    ],
  });

  const atividade = card({
    titulo: "Atividade recente",
    subtitulo: "Últimos pedidos",
    acoes: el("a", { class: "card__sub", href: "#/pedidos" }, "Ver todos"),
    filhos: [
      pedidos.length === 0
        ? estadoVazio({ titulo: "Nenhum pedido ainda", texto: "Crie o primeiro em “Novo pedido”." })
        : el(
            "div",
            { class: "atividade" },
            pedidos.slice(0, 6).map((pedido) => linhaDeAtividade(pedido)),
          ),
    ],
  });

  const estoqueCritico = card({
    titulo: "Estoque crítico",
    subtitulo: `Abaixo de ${configuracoes.obter().limiteEstoqueBaixo} unidades é considerado baixo`,
    acoes: el("a", { class: "card__sub", href: "#/estoque" }, "Ver estoque"),
    filhos: [niveis ? listaDeNiveis(niveis) : indisponivel("estoque")],
  });

  const painelCache = card({
    titulo: "Cache do catálogo",
    subtitulo: "Taxa de acerto por camada",
    acoes: el("a", { class: "card__sub", href: "#/catalogo" }, "Detalhes"),
    filhos: [cache ? metricasDoCache(cache) : indisponivel("cache")],
  });

  const porStatus = card({ titulo: "Pedidos por status", subtitulo: "Distribuição atual", filhos: [distribuicao(pedidos)] });

  return [
    indicadores,
    el("div", { class: "grid grid--dash" }, grafico, atividade),
    el("div", { class: "grid grid--tres" }, estoqueCritico, painelCache, porStatus),
  ];
}

function linhaDeAtividade(pedido) {
  const aberto = !["CONFIRMED", "CANCELLED"].includes(pedido.status);
  const classeIcone = aberto ? "aberto" : pedido.status;
  const nomeIcone = pedido.status === "CONFIRMED" ? "check" : pedido.status === "CANCELLED" ? "x" : "clock";
  return el(
    "a",
    { class: "atividade__item", href: `#/pedidos/${pedido.id}` },
    el("span", { class: `atividade__ponto atividade__ponto--${classeIcone}` }, icon(nomeIcone, 16)),
    el(
      "span",
      { class: "atividade__texto" },
      el("div", { class: "atividade__titulo" }, `Pedido ${curto(pedido.id)} · ${pedido.customerId}`),
      el("div", { class: "atividade__meta" }, `${ROTULOS_STATUS[pedido.status] || pedido.status} · ${haQuantoTempo(pedido.createdAt)}`),
    ),
    el("span", { class: "atividade__valor" }, moeda(pedido.total)),
  );
}

function listaDeNiveis(niveis) {
  return el(
    "div",
    {},
    estoqueMaisBaixo(niveis, 4).map((nivel) => {
      const limite = configuracoes.obter().limiteEstoqueBaixo;
      const fracao = Math.min(nivel.available / Math.max(limite, 1), 1);
      const tom = nivel.available < limite / 4 ? "danger" : nivel.available < limite ? "warn" : "";
      return el(
        "div",
        { class: "nivel" },
        el("div", { class: "nivel__topo" }, el("span", { class: "nivel__nome" }, nivel.productId), el("span", { class: "nivel__qtd" }, `${inteiro(nivel.available)} disponíveis`)),
        el("div", { class: `progress${tom ? ` progress--${tom}` : ""}`, role: "progressbar", "aria-valuenow": Math.round(fracao * 100), "aria-valuemin": 0, "aria-valuemax": 100 },
          el("div", { class: "progress__fill", style: `width:${fracao * 100}%` })),
      );
    }),
  );
}

function metricasDoCache(cache) {
  const linha = (nome, fracao, detalhe, tom) =>
    el(
      "div",
      { class: "metricas" },
      anel(fracao, { tom, rotuloAcessivel: `${nome}: ${Math.round(fracao * 100)}% de acertos` }),
      el("div", {}, el("div", { class: "metricas__nome" }, nome), el("small", {}, detalhe)),
    );
  return el(
    "div",
    {},
    linha("L1 · memória", cache.l1HitRatio, `${inteiro(cache.l1Hits)} acertos · ${inteiro(cache.l1Misses)} erros`, "azul"),
    linha("L2 · Redis", cache.l2HitRatio, `${inteiro(cache.l2Hits)} acertos · ${inteiro(cache.l2Misses)} erros`, "verde"),
  );
}

function distribuicao(pedidos) {
  const dados = distribuicaoPorStatus(pedidos);
  const total = dados.reduce((soma, d) => soma + d.quantidade, 0);
  const rotulos = { CONFIRMED: "Confirmados", aberto: "Em andamento", CANCELLED: "Cancelados" };
  if (total === 0) return estadoVazio({ titulo: "Sem pedidos", texto: "A distribuição aparece com o primeiro pedido." });
  return el(
    "div",
    {},
    el(
      "div",
      { class: "pilha", role: "img", "aria-label": "Distribuição dos pedidos por status" },
      dados.map((d) => el("div", { class: `pilha__seg pilha__seg--${d.chave}`, style: `width:${(d.quantidade / total) * 100}%` })),
    ),
    el(
      "div",
      { class: "lista-status" },
      dados.map((d) =>
        el("div", { class: "lista-status__linha" }, el("span", { class: "legenda__item" }, el("span", { class: `legenda__cor pilha__seg--${d.chave}` }), rotulos[d.chave]), el("strong", {}, inteiro(d.quantidade))),
      ),
    ),
  );
}

function indisponivel(o_que) {
  return estadoVazio({ icone: "alert", titulo: "Indisponível", texto: `Não foi possível ler o ${o_que} agora.` });
}
