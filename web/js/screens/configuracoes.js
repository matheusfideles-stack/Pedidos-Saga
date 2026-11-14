import { el } from "../dom.js";
import { icon } from "../icons.js";
import { LIMITE_MAXIMO_ESTOQUE, aplicarAparencia, configuracoes } from "../settings.js";
import { botao } from "../components/button.js";
import { cabecalhoDePagina, card } from "../components/card.js";
import { campo, entrada } from "../components/form.js";
import { confirmar } from "../components/modal.js";
import { avisar } from "../components/toast.js";
import { LARGURA_MINIMA_SEM_AVISO, LARGURA_RECOMENDADA, medirLargura, removerFundo, salvarFundo, urlDaFotoEscolhida } from "../fundo.js";

const SECOES = [
  { chave: "atualizacao", rotulo: "Atualização", icone: "refresh" },
  { chave: "estoque", rotulo: "Estoque", icone: "box" },
  { chave: "aparencia", rotulo: "Aparência", icone: "layers" },
];

/**
 * Tela de configurações: menu próprio à esquerda (uma seção por vez) e o conteúdo à direita.
 * Cada alteração é salva ao sair do campo ou ao mudar o interruptor, e confirmada com um aviso.
 */
export async function telaConfiguracoes(raiz) {
  let secao = "atualizacao";

  const menu = el("nav", { class: "config-menu", "aria-label": "Seções de configuração" });
  const conteudo = el("div", { class: "config-conteudo" });

  function desenharMenu() {
    menu.replaceChildren(
      ...SECOES.map((s) =>
        el(
          "button",
          {
            type: "button",
            class: `config-menu__item${s.chave === secao ? " is-active" : ""}`,
            "aria-current": s.chave === secao ? "true" : undefined,
            onclick: () => {
              secao = s.chave;
              desenharMenu();
              desenharSecao();
            },
          },
          icon(s.icone, 16),
          s.rotulo,
        ),
      ),
    );
  }

  function desenharSecao() {
    const valores = configuracoes.obter();
    const secaoAtual = {
      atualizacao: () => secaoAtualizacao(valores),
      estoque: () => secaoEstoque(valores),
      aparencia: () => secaoAparencia(valores),
    }[secao];
    conteudo.replaceChildren(secaoAtual());
  }

  function interruptor({ rotulo, descricao, marcado, aoMudar }) {
    const controle = el("input", { type: "checkbox", ...(marcado ? { checked: true } : {}), role: "switch" });
    controle.addEventListener("change", () => {
      aoMudar(controle.checked);
      avisar("Configuração salva.", "success", 2500);
    });
    return el(
      "label",
      { class: "config-linha" },
      el("span", {}, el("span", { class: "config-linha__titulo" }, rotulo), el("small", {}, descricao)),
      el("span", { class: "switch" }, controle, el("span", { class: "switch__trilho" })),
    );
  }

  function secaoAtualizacao(valores) {
    return card({
      titulo: "Atualização automática",
      subtitulo: "Telas como Início, Pedidos e Estoque recarregam os dados sozinhas.",
      filhos: [
        interruptor({
          rotulo: "Atualizar automaticamente",
          descricao: "Desligue para ver os dados só quando você mandar atualizar.",
          marcado: valores.atualizacaoAutomatica,
          aoMudar: (ligado) => configuracoes.alterar({ atualizacaoAutomatica: ligado }),
        }),
      ],
    });
  }

  function secaoEstoque(valores) {
    const controle = entrada({
      type: "number",
      min: "0",
      max: String(LIMITE_MAXIMO_ESTOQUE),
      step: "1",
      inputmode: "numeric",
      value: String(valores.limiteEstoqueBaixo),
    });
    const campoLimite = campo({
      rotulo: "Limite de estoque baixo (unidades)",
      controle,
      dica: "Produtos com menos unidades disponíveis que este número aparecem como alerta. Use 0 para desligar os alertas.",
    });

    const salvar = () => {
      const valor = controle.value.trim();
      const numero = Number(valor);
      if (valor === "" || !Number.isInteger(numero) || numero < 0 || numero > LIMITE_MAXIMO_ESTOQUE) {
        campoLimite.mostrarErro(`Informe um número inteiro de 0 a ${LIMITE_MAXIMO_ESTOQUE}.`);
        return;
      }
      if (numero === configuracoes.obter().limiteEstoqueBaixo) return;
      configuracoes.alterar({ limiteEstoqueBaixo: numero });
      avisar("Limite de estoque salvo.", "success", 2500);
    };
    controle.addEventListener("change", salvar);
    controle.addEventListener("keydown", (evento) => {
      if (evento.key === "Enter") salvar();
    });

    return card({
      titulo: "Alerta de estoque",
      subtitulo: "Vale para o Início, a tela de Estoque e o sino de avisos.",
      filhos: [el("div", { class: "grid" }, campoLimite.raiz)],
    });
  }

  function secaoAparencia(valores) {
    const previa = el("div", { class: "fundo-escolha__previa", role: "img", "aria-label": "Prévia da foto de fundo" });
    const atualizarPrevia = () => {
      const url = urlDaFotoEscolhida();
      previa.style.backgroundImage = url ? `url("${url}")` : "url(\"fundo.webp\")";
      removerBotao.hidden = !url;
    };
    const arquivo = el("input", { type: "file", accept: "image/jpeg,image/png,image/webp", class: "arquivo-oculto", "aria-label": "Escolher foto de fundo" });
    const removerBotao = botao({
      rotulo: "Usar foto padrão",
      icone: "x",
      variante: "ghost",
      onClick: async () => {
        await removerFundo();
        atualizarPrevia();
        avisar("Foto padrão restaurada.", "success", 2500);
      },
    });

    arquivo.addEventListener("change", async () => {
      const escolhido = arquivo.files[0];
      arquivo.value = "";
      if (!escolhido) return;
      try {
        const largura = await medirLargura(escolhido).catch(() => null);
        await salvarFundo(escolhido);
        atualizarPrevia();
        if (largura !== null && largura < LARGURA_MINIMA_SEM_AVISO) {
          avisar(`Foto salva, mas tem ${largura} px de largura. Em telas grandes ela pode ficar borrada. Recomendado: ${LARGURA_RECOMENDADA} px.`, "info", 9000);
        } else {
          avisar("Foto de fundo salva.", "success", 2500);
        }
      } catch (erro) {
        avisar(erro.message, "error", 6000);
      }
    });

    atualizarPrevia();

    return card({
      titulo: "Aparência",
      subtitulo: "Ajustes visuais deste navegador.",
      filhos: [
        interruptor({
          rotulo: "Mostrar foto de fundo",
          descricao: "Desligue para usar só o fundo escuro, sem a imagem atrás dos painéis.",
          marcado: valores.mostrarFoto,
          aoMudar: (ligado) => {
            configuracoes.alterar({ mostrarFoto: ligado });
            aplicarAparencia();
          },
        }),
        el(
          "div",
          { class: "config-linha", style: "cursor:default" },
          el("div", { class: "fundo-escolha" },
            previa,
            el("div", { class: "fundo-escolha__texto" },
              el("span", { class: "config-linha__titulo" }, "Foto de fundo"),
              el("small", {}, "JPG, PNG ou WebP, até 10 MB. Use pelo menos 1920 × 1080 px (ideal: 2560 × 1440 px)."),
            ),
            el("div", { class: "fundo-escolha__acoes" },
              arquivo,
              botao({ rotulo: "Trocar foto", icone: "edit", variante: "primary", onClick: () => arquivo.click() }),
              removerBotao,
            ),
          ),
        ),
      ],
    });
  }

  async function restaurar() {
    const confirmado = await confirmar({
      titulo: "Restaurar padrões?",
      mensagem: "Todas as configurações voltam ao padrão deste navegador. Os dados dos pedidos não são afetados.",
      rotuloConfirmar: "Restaurar",
      perigo: true,
    });
    if (!confirmado) return;
    configuracoes.restaurarPadroes();
    aplicarAparencia();
    desenharSecao();
    avisar("Padrões restaurados.", "success", 2500);
  }

  raiz.replaceChildren(
    cabecalhoDePagina({
      titulo: "Configurações",
      descricao: "Preferências deste navegador. Não alteram os dados do sistema.",
      acoes: botao({ rotulo: "Restaurar padrões", icone: "refresh", variante: "ghost", onClick: restaurar }),
    }),
    el("div", { class: "config-layout" }, card({ classe: "config-lateral", filhos: [menu] }), conteudo),
  );

  desenharMenu();
  desenharSecao();
}
