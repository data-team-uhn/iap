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

// The pure, testable model behind the LLM configuration screen: reading the catalog the
// configuration servlet serves, and the lookups the UI needs. No React, no fetch.

// One provider or model property as the servlet serializes it. Providers and models both carry an
// open set of these - the node types allow deployment-specific extras - so they are kept as a list
// to display rather than as named fields.
export interface LlmSetting {
  name: string;
  value: string;
}

// One model offered by a provider.
export interface LlmModel {
  // The node name, which is what gets sent to the provider as the model identifier.
  name: string;
  // Everything configured on the model: token limits, temperature, developer, and any extras.
  settings: LlmSetting[];
}

// One provider: an endpoint, and the models it serves.
export interface LlmProvider {
  // The node name, used as the active selection value.
  name: string;
  // The human-readable name, when the provider gives one.
  label?: string;
  // Everything configured on the provider: endpoint, timeout, API key variable, and any extras.
  settings: LlmSetting[];
  models: LlmModel[];
}

// The catalog of providers and models, plus which of them is currently in use.
export interface LlmCatalog {
  activeProvider?: string;
  activeModel?: string;
  providers: LlmProvider[];
}

// The catalog before anything has been loaded, so the screen has something to render at once.
export const EMPTY_CATALOG: LlmCatalog = { providers: [] };

// The keys that carry structure rather than a setting to display.
const STRUCTURAL_KEYS = [ "name", "models" ];

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const asText = (value: unknown): string | undefined => {
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  return undefined;
};

// The settings of a provider or model, in the order the servlet listed them, with the structural
// keys and anything that is not a plain value left out.
const parseSettings = (node: Record<string, unknown>): LlmSetting[] =>
  Object.entries(node)
    .filter(([ name ]) => !STRUCTURAL_KEYS.includes(name))
    .flatMap(([ name, value ]) => {
      const text = asText(value);
      return text === undefined ? [] : [ { name, value: text } ];
    });

const parseModel = (node: unknown): LlmModel[] => {
  if (!isRecord(node)) {
    return [];
  }
  const name = asText(node.name);
  return name === undefined ? [] : [ { name, settings: parseSettings(node) } ];
};

const parseProvider = (node: unknown): LlmProvider[] => {
  if (!isRecord(node)) {
    return [];
  }
  const name = asText(node.name);
  if (name === undefined) {
    return [];
  }
  const models = Array.isArray(node.models) ? node.models.flatMap(parseModel) : [];
  return [ { name, label: asText(node.label), settings: parseSettings(node), models } ];
};

// Reads the catalog out of what the configuration servlet answered. Anything unrecognizable is
// dropped rather than thrown over: the screen is an administrator's view of a configuration node
// that allows extra properties, so it has to render whatever it is given.
export const parseCatalog = (body: unknown): LlmCatalog => {
  if (!isRecord(body)) {
    return EMPTY_CATALOG;
  }
  return {
    activeProvider: asText(body.activeProvider),
    activeModel: asText(body.activeModel),
    providers: Array.isArray(body.providers) ? body.providers.flatMap(parseProvider) : [],
  };
};

// The provider with the given name, or undefined when the catalog does not offer it.
export const findProvider = (catalog: LlmCatalog, name?: string): LlmProvider | undefined =>
  name === undefined ? undefined : catalog.providers.find(provider => provider.name === name);

// The model with the given name among a provider's models, or undefined.
export const findModel = (provider: LlmProvider | undefined, name?: string): LlmModel | undefined =>
  provider === undefined || name === undefined
    ? undefined
    : provider.models.find(model => model.name === name);

// The model to fall back on when a provider is picked: its first, since a provider's models are
// listed in the order the configuration node stores them.
export const firstModelName = (provider: LlmProvider | undefined): string | undefined =>
  provider?.models[0]?.name;

// What to call a provider on screen.
export const providerLabel = (provider: LlmProvider): string => provider.label ?? provider.name;
