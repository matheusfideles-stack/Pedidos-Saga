import { api } from "../api.js";
import { el } from "../dom.js";
import { moeda } from "../format.js";
import { botao, comCarregamento } from "../components/button.js";
import { cabecalhoDePagina, card } from "../components/card.js";
import { campo, entrada, selecao } from "../components/form.js";
import { esqueletoBloco, estadoErro } from "../components/states.js";
import { avisar } from "../components/toast.js";

const QUANTIDADE_MAXIMA = 1000;

/** Novo pedido. Cada tentativa usa uma Idempotency-Key: reenviar após falha de rede não duplica o pedido. */
export async function telaNovoPedido(raiz, ctx) {
  raiz.replaceChildren(cabecalhoDePagina({ titulo: "Novo pedido", descricao: "Escolha os produtos. O total é conferido pela API ao criar." }), card({ filhos: [esqueletoBloco()] }));

  let produtos;
  try {
    produtos = await api.produtos();
  } catch (erro) {
    raiz.replaceChildren(estadoErro({ mensagem: erro.message, aoTentarNovamente: () => telaNovoPedido(raiz, ctx) }));
    return;
  }
  if (!ctx.ativo()) return;

  let chave = crypto.randomUUID();
  const linhas = [];

  const cliente = campo({
    rotulo: "Cliente (id)",
    controle: entrada({ value: `cliente-${Math.floor(Math.random() * 9000) + 1000}`, maxlength: "64", autocomplete: "off" }),
    dica: "Identificador do cliente. Use um id começando com “decline” para simular pagamento recusado.",
  });

  const listaDeItens = el("div");
  const erroGeral = el("p", { class: "field__erro", role: "alert" });
  const painelResumo = el("div", { class: "sumario" });

  const produtoPorId = (id) => produtos.find((p) => p.id === id);

  function atualizarResumo() {
    const itens = linhas.map((l) => ({ produto: produtoPorId(l.selecao.value), quantidade: Number(l.quantidade.controle.value) || 0 }));
    const total = itens.reduce((soma, i) => soma + (i.produto ? Number(i.produto.price) * i.quantidade : 0), 0);
    painelResumo.replaceChildren(
      ...itens.map((i) => el("div", { class: "sumario__linha" }, el("span", {}, `${i.produto?.name ?? "—"} × ${i.quantidade}`), el("span", {}, moeda(i.produto ? Number(i.produto.price) * i.quantidade : 0)))),
      el("div", { class: "sumario__total" }, el("span", {}, "Total"), el("span", { class: "valor-grande" }, moeda(total))),
      el("div", {}, el("small", {}, "Chave de idempotência desta tentativa"), el("div", { class: "chave mono" }, chave)),
    );
  }

  function adicionarItem() {
    const escolha = selecao(
      produtos.map((p) => ({ valor: p.id, rotulo: `${p.name} · ${moeda(p.price)}` })),
      { "aria-label": "Produto" },
    );
    const produtoCampo = campo({ rotulo: "Produto", controle: escolha });
    const quantidade = campo({ rotulo: "Quantidade", controle: entrada({ type: "number", min: "1", max: String(QUANTIDADE_MAXIMA), value: "1", inputmode: "numeric" }) });
    escolha.addEventListener("change", atualizarResumo);
    quantidade.controle.addEventListener("input", atualizarResumo);

    const item = { selecao: escolha, quantidade, produtoCampo };
    item.no = el(
      "div",
      { class: "item-form" },
      produtoCampo.raiz,
      quantidade.raiz,
      botao({
        icone: "x",
        variante: "ghost",
        titulo: "Remover item",
        onClick: () => {
          linhas.splice(linhas.indexOf(item), 1);
          item.no.remove();
          atualizarResumo();
        },
      }),
    );
    linhas.push(item);
    listaDeItens.append(item.no);
    atualizarResumo();
  }

  function validar() {
    let valido = true;
    erroGeral.textContent = "";

    const texto = cliente.controle.value.trim();
    if (texto.length < 2) {
      cliente.mostrarErro("Informe o cliente (mínimo 2 caracteres).");
      valido = false;
    }
    if (linhas.length === 0) {
      erroGeral.textContent = "Adicione pelo menos um produto.";
      valido = false;
    }
    for (const linha of linhas) {
      const quantidade = Number(linha.quantidade.controle.value);
      if (!Number.isInteger(quantidade) || quantidade < 1 || quantidade > QUANTIDADE_MAXIMA) {
        linha.quantidade.mostrarErro(`De 1 a ${QUANTIDADE_MAXIMA}.`);
        valido = false;
      }
    }
    return valido;
  }

  const botaoCriar = botao({ rotulo: "Criar pedido", icone: "check", tipo: "submit" });

  const formulario = el(
    "form",
    { novalidate: true, onsubmit: (evento) => { evento.preventDefault(); enviar(); } },
    cliente.raiz,
    el("h2", { class: "card__title", style: "margin:20px 0 12px" }, "Itens"),
    listaDeItens,
    erroGeral,
    el(
      "div",
      { class: "form-acoes" },
      botao({ rotulo: "Adicionar produto", icone: "plus", variante: "ghost", onClick: adicionarItem }),
      botaoCriar,
    ),
  );

  async function enviar() {
    if (!validar()) return;
    const corpo = {
      customerId: cliente.controle.value.trim(),
      items: linhas.map((l) => ({ productId: l.selecao.value, quantity: Number(l.quantidade.controle.value) })),
    };
    await comCarregamento(botaoCriar, async () => {
      try {
        const pedido = await api.criarPedido(corpo, chave);
        chave = crypto.randomUUID(); // o próximo pedido precisa de uma chave nova
        avisar("Pedido criado.", "success");
        ctx.ir(`#/pedidos/${pedido.id}`);
      } catch (erro) {
        erroGeral.textContent = erro.message; // mantém a chave: reenviar o mesmo pedido não duplica
        avisar(erro.message, "error", 6000);
      }
    });
  }

  raiz.replaceChildren(
    cabecalhoDePagina({ titulo: "Novo pedido", descricao: "Escolha os produtos. O total é conferido pela API ao criar." }),
    el("div", { class: "grid grid--form" }, card({ filhos: [formulario] }), card({ titulo: "Resumo", subtitulo: "Prévia do pedido", filhos: [painelResumo] })),
  );
  adicionarItem();
}
