import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { PADROES, sanear, LIMITE_MAXIMO_ESTOQUE } from "../js/settings.js";

describe("sanear configurações", () => {
  it("valores válidos passam como estão", () => {
    assert.deepEqual(sanear({ atualizacaoAutomatica: false, limiteEstoqueBaixo: 250, mostrarFoto: false }), {
      atualizacaoAutomatica: false,
      limiteEstoqueBaixo: 250,
      mostrarFoto: false,
    });
  });

  it("limite fora da faixa é limitado e não negativo", () => {
    assert.equal(sanear({ limiteEstoqueBaixo: -5 }).limiteEstoqueBaixo, 0);
    assert.equal(sanear({ limiteEstoqueBaixo: 999999 }).limiteEstoqueBaixo, LIMITE_MAXIMO_ESTOQUE);
  });

  it("valor não numérico volta ao padrão", () => {
    assert.equal(sanear({ limiteEstoqueBaixo: "abc" }).limiteEstoqueBaixo, PADROES.limiteEstoqueBaixo);
    assert.equal(sanear({}).atualizacaoAutomatica, true);
    assert.equal(sanear({}).mostrarFoto, true);
  });

  it("decimal é arredondado para inteiro", () => {
    assert.equal(sanear({ limiteEstoqueBaixo: 42.6 }).limiteEstoqueBaixo, 43);
  });
});
