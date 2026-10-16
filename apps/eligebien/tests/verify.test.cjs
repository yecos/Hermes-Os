const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const html = fs.readFileSync(path.join(root, "index.html"), "utf8");
const api = fs.readFileSync(path.join(root, "api", "event.js"), "utf8");

test("home page has search, navigation and transparency section", () => {
  assert.match(html, /<form id="search"/);
  assert.match(html, /id="cards"/);
  assert.match(html, /id="metodo"/);
  assert.match(html, /id="legal"/);
  assert.match(html, /No hay afiliaciones ni patrocinios activos/);
});

test("editorial catalog lists 15 official HTTPS destinations", () => {
  const entries = html.match(/"https:\/\/[^"]+"/g) || [];
  assert.equal(entries.length, 15);
  assert.ok(entries.every((value) => !/ref=|affiliat|clickid|utm_/.test(value)));
  assert.ok(entries.some((value) => value.includes("duolingo.com")));
  assert.ok(entries.some((value) => value.includes("ilovepdf.com")));
});

test("embedded client-side application parses", () => {
  const match = html.match(/<script>([\s\S]*?)<\/script>/);
  assert.ok(match, "inline application exists");
  assert.doesNotThrow(() => new vm.Script(match[1]));
});

test("search text is never sent through metrics endpoint", () => {
  assert.match(api, /event_type/);
  assert.match(api, /offer_id/);
  assert.doesNotMatch(api, /searchQuery|query_text|req\.body\.q/);
  assert.match(html, /metric\("recommend","all",null\)/);
});

test("backend caps stored metrics and requires database configuration", () => {
  assert.match(api, /DATABASE_URL/);
  assert.match(api, /COUNT\(\*\)/);
  assert.match(api, /< 5000/);
});
