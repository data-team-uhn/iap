/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  EMPTY_CATALOG, findModel, findProvider, firstModelName, parseCatalog, providerLabel,
} from "@iap/llm/llmConfigModel";

const catalogJson = {
  activeProvider: "local",
  activeModel: "llama3.2-3b",
  providers: [
    {
      name: "local",
      label: "Local (Ollama)",
      api: "openai",
      endpoint: "http://localhost:11434/v1",
      timeoutSeconds: 600,
      models: [
        { name: "llama3.2-3b", maxOutputTokens: 1024, temperature: 0, developer: "meta" },
        { name: "other-model", maxOutputTokens: 2048 },
      ],
    },
    {
      name: "prompter",
      endpoint: "https://prompter.example.invalid/v1",
      models: [],
    },
  ],
};

describe("parseCatalog", () => {
  it("reads the providers, their models and the active selection", () => {
    const catalog = parseCatalog(catalogJson);

    expect(catalog.activeProvider).toBe("local");
    expect(catalog.activeModel).toBe("llama3.2-3b");
    expect(catalog.providers.map(provider => provider.name)).toEqual(["local", "prompter"]);
    expect(catalog.providers[0].models.map(model => model.name)).toEqual(["llama3.2-3b", "other-model"]);
  });

  it("keeps every setting but the structural keys, as text", () => {
    const [ local ] = parseCatalog(catalogJson).providers;

    expect(local.settings).toEqual([
      { name: "label", value: "Local (Ollama)" },
      { name: "api", value: "openai" },
      { name: "endpoint", value: "http://localhost:11434/v1" },
      { name: "timeoutSeconds", value: "600" },
    ]);
    expect(local.models[0].settings).toEqual([
      { name: "maxOutputTokens", value: "1024" },
      { name: "temperature", value: "0" },
      { name: "developer", value: "meta" },
    ]);
  });

  it("keeps deployment-specific extras it knows nothing about", () => {
    const catalog = parseCatalog({
      providers: [ { name: "local", apiVersion: "2024-02-01", verified: true, models: [] } ],
    });

    expect(catalog.providers[0].settings).toEqual([
      { name: "apiVersion", value: "2024-02-01" },
      { name: "verified", value: "true" },
    ]);
  });

  it("drops settings that are not plain values", () => {
    const catalog = parseCatalog({
      providers: [ { name: "local", tags: ["a", "b"], nested: { deep: 1 }, missing: null, models: [] } ],
    });

    expect(catalog.providers[0].settings).toEqual([]);
  });

  it("returns an empty catalog for anything unreadable", () => {
    expect(parseCatalog(null)).toEqual(EMPTY_CATALOG);
    expect(parseCatalog("nonsense")).toEqual(EMPTY_CATALOG);
    expect(parseCatalog([])).toEqual(EMPTY_CATALOG);
    expect(parseCatalog({}).providers).toEqual([]);
  });

  it("omits an active selection the server did not report", () => {
    const catalog = parseCatalog({ providers: [] });

    expect(catalog.activeProvider).toBeUndefined();
    expect(catalog.activeModel).toBeUndefined();
  });

  it("skips providers and models it cannot name", () => {
    const catalog = parseCatalog({
      providers: [
        "not a provider",
        { label: "nameless" },
        { name: "local", models: [ { maxOutputTokens: 10 }, "not a model", { name: "fine" } ] },
      ],
    });

    expect(catalog.providers.map(provider => provider.name)).toEqual(["local"]);
    expect(catalog.providers[0].models.map(model => model.name)).toEqual(["fine"]);
  });

  it("copes with providers that is not a list", () => {
    expect(parseCatalog({ providers: "some" }).providers).toEqual([]);
    expect(parseCatalog({ providers: [ { name: "local", models: "some" } ] })
      .providers[0].models).toEqual([]);
  });
});

describe("catalog lookups", () => {
  const catalog = parseCatalog(catalogJson);

  it("finds a provider and a model by name", () => {
    expect(findProvider(catalog, "local")?.name).toBe("local");
    expect(findModel(findProvider(catalog, "local"), "other-model")?.name).toBe("other-model");
  });

  it("finds nothing for a name that is absent or missing", () => {
    expect(findProvider(catalog, "anthropic")).toBeUndefined();
    expect(findProvider(catalog, undefined)).toBeUndefined();
    expect(findModel(undefined, "llama3.2-3b")).toBeUndefined();
    expect(findModel(findProvider(catalog, "local"), undefined)).toBeUndefined();
    expect(findModel(findProvider(catalog, "local"), "absent")).toBeUndefined();
  });

  it("falls back to a provider's first model, when it has one", () => {
    expect(firstModelName(findProvider(catalog, "local"))).toBe("llama3.2-3b");
    expect(firstModelName(findProvider(catalog, "prompter"))).toBeUndefined();
    expect(firstModelName(undefined)).toBeUndefined();
  });

  it("shows a provider's label, or its name when it has none", () => {
    expect(providerLabel(catalog.providers[0])).toBe("Local (Ollama)");
    expect(providerLabel(catalog.providers[1])).toBe("prompter");
  });
});
