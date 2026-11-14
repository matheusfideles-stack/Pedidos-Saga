// Helpers de DOM. Todo texto entra como nó de texto (nunca innerHTML): conteúdo vindo da API não executa HTML.

const SVG_NS = "http://www.w3.org/2000/svg";

function flatten(children) {
  return children
    .flat(Infinity)
    .filter((c) => c !== null && c !== undefined && c !== false)
    .map((c) => (c instanceof Node ? c : document.createTextNode(String(c))));
}

function aplicarPropriedades(node, props) {
  for (const [nome, valor] of Object.entries(props)) {
    if (valor === undefined || valor === null || valor === false) continue;
    if (nome === "class") node.className = valor;
    else if (nome === "dataset") Object.assign(node.dataset, valor);
    else if (nome.startsWith("on") && typeof valor === "function") node.addEventListener(nome.slice(2).toLowerCase(), valor);
    else node.setAttribute(nome, valor === true ? "" : String(valor));
  }
}

export function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  aplicarPropriedades(node, props);
  node.append(...flatten(children));
  return node;
}

export function svgEl(tag, attrs = {}, ...children) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [nome, valor] of Object.entries(attrs)) {
    if (valor !== undefined && valor !== null && valor !== false) node.setAttribute(nome, String(valor));
  }
  node.append(...flatten(children));
  return node;
}

export function limpar(node) {
  node.replaceChildren();
}
