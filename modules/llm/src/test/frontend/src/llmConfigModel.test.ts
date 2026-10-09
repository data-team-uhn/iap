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
  activeProvider: "example",
  activeModel: "model-a",
  providers: [
    {
      name: "example",
      label: "Example Provider",
      api: "openai",
      endpoint: "http://model-a.example.invalid/v1",
      timeoutSeconds: 600,
      models: [
        { name: "model-a", contextLimitTokens: 1024, temperature: 0, developer: "meta" },
        { name: "other-model", contextLimitTokens: 2048 },
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

    expect(catalog.activeProvider).toBe("example");
    expect(catalog.activeModel).toBe("model-a");
    expect(catalog.providers.map(provider => provider.name)).toEqual(["example", "prompter"]);
    expect(catalog.providers[0].models.map(model => model.name)).toEqual(["model-a", "other-model"]);
  });

  it("keeps every setting but the structural keys, as text", () => {
    const [ provider ] = parseCatalog(catalogJson).providers;

    expect(provider.settings).toEqual([
      { name: "label", value: "Example Provider" },
      { name: "api", value: "openai" },
      { name: "endpoint", value: "http://model-a.example.invalid/v1" },
      { name: "timeoutSeconds", value: "600" },
    ]);
    expect(provider.models[0].settings).toEqual([
      { name: "contextLimitTokens", value: "1024" },
      { name: "temperature", value: "0" },
      { name: "developer", value: "meta" },
    ]);
  });

  it("keeps deployment-specific extras it knows nothing about", () => {
    const catalog = parseCatalog({
      providers: [ { name: "example", apiVersion: "2024-02-01", verified: true, models: [] } ],
    });

    expect(catalog.providers[0].settings).toEqual([
      { name: "apiVersion", value: "2024-02-01" },
      { name: "verified", value: "true" },
    ]);
  });

  it("drops settings that are not plain values", () => {
    const catalog = parseCatalog({
      providers: [ { name: "example", tags: ["a", "b"], nested: { deep: 1 }, missing: null, models: [] } ],
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
        { name: "example", models: [ { contextLimitTokens: 10 }, "not a model", { name: "fine" } ] },
      ],
    });

    expect(catalog.providers.map(provider => provider.name)).toEqual(["example"]);
    expect(catalog.providers[0].models.map(model => model.name)).toEqual(["fine"]);
  });

  it("copes with providers that is not a list", () => {
    expect(parseCatalog({ providers: "some" }).providers).toEqual([]);
    expect(parseCatalog({ providers: [ { name: "example", models: "some" } ] })
      .providers[0].models).toEqual([]);
  });
});

describe("catalog lookups", () => {
  const catalog = parseCatalog(catalogJson);

  it("finds a provider and a model by name", () => {
    expect(findProvider(catalog, "example")?.name).toBe("example");
    expect(findModel(findProvider(catalog, "example"), "other-model")?.name).toBe("other-model");
  });

  it("finds nothing for a name that is absent or missing", () => {
    expect(findProvider(catalog, "anthropic")).toBeUndefined();
    expect(findProvider(catalog, undefined)).toBeUndefined();
    expect(findModel(undefined, "model-a")).toBeUndefined();
    expect(findModel(findProvider(catalog, "example"), undefined)).toBeUndefined();
    expect(findModel(findProvider(catalog, "example"), "absent")).toBeUndefined();
  });

  it("falls back to a provider's first model, when it has one", () => {
    expect(firstModelName(findProvider(catalog, "example"))).toBe("model-a");
    expect(firstModelName(findProvider(catalog, "prompter"))).toBeUndefined();
    expect(firstModelName(undefined)).toBeUndefined();
  });

  it("shows a provider's label, or its name when it has none", () => {
    expect(providerLabel(catalog.providers[0])).toBe("Example Provider");
    expect(providerLabel(catalog.providers[1])).toBe("prompter");
  });
});
