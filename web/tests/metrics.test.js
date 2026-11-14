import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  distribuicaoPorStatus,
  estoqueMaisBaixo,
  montarAvisos,
  pedidosPorHora,
  resumir,
  variacao,
  variacaoJanelaMovel,
} from "../js/metrics.js";
import { haQuantoTempo, percentual, saudacao } from "../js/format.js";

// 08/11/2026 15:30, horário local: o teste não depende do fuso da máquina.
const AGORA = new Date(2026, 10, 8, 15, 30).getTime();
const minutosAtras = (minutos) => new Date(AGORA - minutos * 60_000).toISOString();

const pedido = (status, total, minutos) => ({ status, total, createdAt: minutosAtras(minutos) });

describe("resumir", () => {
  it("conta pedidos de hoje, abertos, confirmados e cancelados", () => {
    const pedidos = [
      pedido("CONFIRMED", 100, 10),
      pedido("CONFIRMED", 50.5, 20),
      pedido("CANCELLED", 70, 30),
      pedido("PENDING", 10, 1),
      pedido("INVENTORY_RESERVED", 10, 2),
      pedido("CONFIRMED", 999, 60 * 40), // 40 h atrás: não é de hoje
    ];
    const resumo = resumir(pedidos, AGORA);
    assert.equal(resumo.total, 6);
    assert.equal(resumo.hoje, 5);
    assert.equal(resumo.abertos, 2);
    assert.equal(resumo.confirmados, 3);
    assert.equal(resumo.cancelados, 1);
    assert.equal(resumo.valorConfirmado, 1149.5);
  });

  it("taxa de confirmação ignora pedidos ainda abertos e é null sem finalizados", () => {
    assert.equal(resumir([pedido("PENDING", 1, 1)], AGORA).taxaConfirmacao, null);
    const resumo = resumir([pedido("CONFIRMED", 1, 1), pedido("CONFIRMED", 1, 2), pedido("CANCELLED", 1, 3), pedido("PENDING", 1, 4)], AGORA);
    assert.equal(resumo.taxaConfirmacao, 2 / 3);
  });

  it("lista vazia não quebra", () => {
    const resumo = resumir([], AGORA);
    assert.equal(resumo.total, 0);
    assert.equal(resumo.valorConfirmado, 0);
    assert.equal(resumo.taxaConfirmacao, null);
  });
});

describe("pedidosPorHora", () => {
  it("agrupa nas últimas 8 horas, da mais antiga para a mais recente", () => {
    const pedidos = [
      pedido("CONFIRMED", 100, 5), // 15h
      pedido("CANCELLED", 40, 10), // 15h
      pedido("CONFIRMED", 60, 45), // 14h
      pedido("CONFIRMED", 5, 60 * 30), // fora da janela
    ];
    const baldes = pedidosPorHora(pedidos, AGORA, 8);
    assert.equal(baldes.length, 8);
    assert.equal(baldes[7].quantidade, 2); // hora atual
    assert.equal(baldes[7].valorConfirmado, 100); // só o confirmado
    assert.equal(baldes[6].quantidade, 1);
    assert.equal(baldes[6].valorConfirmado, 60);
    assert.equal(baldes.slice(0, 6).reduce((soma, b) => soma + b.quantidade, 0), 0);
  });

  it("os baldes são horas consecutivas", () => {
    const baldes = pedidosPorHora([], AGORA, 4);
    for (let i = 1; i < baldes.length; i++) assert.equal(baldes[i].inicio - baldes[i - 1].inicio, 3_600_000);
  });
});

describe("variacao", () => {
  it("calcula a variação relativa", () => {
    assert.equal(variacao(150, 100), 0.5);
    assert.equal(variacao(50, 100), -0.5);
  });

  it("retorna null quando não há base de comparação e 0 quando ambos são zero", () => {
    assert.equal(variacao(5, 0), null);
    assert.equal(variacao(0, 0), 0);
  });

  it("janela móvel compara os últimos 60 min com os 60 anteriores, e não horas de relógio", () => {
    const pedidos = [
      pedido("CONFIRMED", 100, 5), // últimos 60 min
      pedido("CONFIRMED", 50, 30), // últimos 60 min
      pedido("CANCELLED", 10, 59), // últimos 60 min
      pedido("CONFIRMED", 40, 90), // 60 min anteriores
      pedido("CONFIRMED", 999, 60 * 5), // fora das duas janelas
    ];
    assert.equal(variacaoJanelaMovel(pedidos, "quantidade", AGORA), 2); // 3 contra 1
    assert.equal(variacaoJanelaMovel(pedidos, "valorConfirmado", AGORA), 2.75); // 150 contra 40
  });

  it("janela móvel sem base de comparação retorna null", () => {
    assert.equal(variacaoJanelaMovel([pedido("CONFIRMED", 10, 5)], "quantidade", AGORA), null);
    assert.equal(variacaoJanelaMovel([], "quantidade", AGORA), 0);
  });
});

describe("estoque e avisos", () => {
  const niveis = [
    { productId: "P-001", available: 99997, reserved: 0 },
    { productId: "P-009", available: 5, reserved: 2 },
    { productId: "P-003", available: 50, reserved: 0 },
  ];

  it("ordena do menor para o maior estoque disponível", () => {
    assert.deepEqual(estoqueMaisBaixo(niveis, 2).map((n) => n.productId), ["P-009", "P-003"]);
  });

  it("gera aviso para estoque baixo e para cancelamentos nas últimas 24 h", () => {
    const pedidos = [pedido("CANCELLED", 1, 30), pedido("CANCELLED", 1, 60 * 30), pedido("CONFIRMED", 1, 5)];
    const avisos = montarAvisos(niveis, pedidos, AGORA);
    assert.equal(avisos.filter((a) => a.tipo === "estoque").length, 2);
    const cancelados = avisos.find((a) => a.tipo === "cancelados");
    assert.equal(cancelados.quantidade, 1); // o de 30 h atrás não entra
  });

  it("sem estoque baixo nem cancelamentos, não há avisos", () => {
    assert.deepEqual(montarAvisos([{ productId: "P-001", available: 500, reserved: 0 }], [pedido("CONFIRMED", 1, 5)], AGORA), []);
  });
});

describe("distribuicaoPorStatus", () => {
  it("separa confirmados, em andamento e cancelados", () => {
    const resultado = distribuicaoPorStatus([pedido("CONFIRMED", 1, 1), pedido("PENDING", 1, 1), pedido("PAYMENT_AUTHORIZED", 1, 1), pedido("CANCELLED", 1, 1)]);
    assert.deepEqual(resultado, [
      { chave: "CONFIRMED", quantidade: 1 },
      { chave: "aberto", quantidade: 2 },
      { chave: "CANCELLED", quantidade: 1 },
    ]);
  });
});

describe("format", () => {
  it("saudação muda conforme a hora", () => {
    assert.equal(saudacao(new Date(2026, 0, 1, 9)), "Bom dia");
    assert.equal(saudacao(new Date(2026, 0, 1, 15)), "Boa tarde");
    assert.equal(saudacao(new Date(2026, 0, 1, 21)), "Boa noite");
    assert.equal(saudacao(new Date(2026, 0, 1, 3)), "Boa madrugada");
  });

  it("tempo relativo", () => {
    assert.equal(haQuantoTempo(minutosAtras(0), AGORA), "agora");
    assert.equal(haQuantoTempo(minutosAtras(5), AGORA), "há 5 min");
    assert.equal(haQuantoTempo(minutosAtras(180), AGORA), "há 3 h");
    assert.equal(haQuantoTempo(minutosAtras(60 * 48), AGORA), "há 2 d");
  });

  it("percentual usa vírgula decimal", () => {
    assert.equal(percentual(0.857, 1), "85,7%");
    assert.equal(percentual(1), "100%");
  });
});

describe("limite de estoque configurável", () => {
  it("montarAvisos usa o limite informado, não o padrão", () => {
    const niveis = [{ productId: "P-001", available: 150, reserved: 0 }];
    assert.equal(montarAvisos(niveis, [], AGORA, 100).length, 0);
    assert.equal(montarAvisos(niveis, [], AGORA, 200).length, 1);
    assert.equal(montarAvisos(niveis, [], AGORA, 0).length, 0); // 0 desliga o alerta
  });
});
