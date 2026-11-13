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

import { useEffect, useState, type ComponentType } from "react";

import { readJson } from "./requestFailure";

const ASSET_PREFIX = "asset:";

// The asset map, from an asset name to its file name under /libs/iap/resources, which carries a
// content hash
let assetsJson: Record<string, string> | null = null;
// The request for the asset map while it runs, so that only one is sent
let assetsJsonRequest: Promise<Record<string, string> | undefined> | null = null;
// The dependency map, from an asset name to the asset URLs it depends on
let assetDependenciesJson: Record<string, string[]> | null = null;
// The request for the dependency map while it runs, so that only one is sent
let assetDependenciesJsonRequest: Promise<Record<string, string[]> | undefined> | null = null;
// The loaded modules, by the URL they were fetched from
const modules: Record<string, unknown> = {};
// The loaded components, by the asset URL they were asked for with
const assets: Record<string, unknown> = {};
// The assets whose dependencies are currently being resolved, so that a dependency cycle is
// followed once rather than forever
const loading = new Set<string>();

// The asset map, fetched once and kept. After a failure, which is logged, the next call fetches it
// again.
//
// @return the asset map; undefined if it could not be loaded
const getAssetsJson = async function(): Promise<Record<string, string> | undefined> {
  if (!assetsJson) {
    assetsJsonRequest ??= fetch("/libs/iap/resources/assets.json")
      .then(response => response.ok ? readJson<Record<string, string>>(response) : Promise.reject(new Error(`Failed to load assets.json: ${response.status}`)))
      .then(json => assetsJson = json)
      .catch((e: unknown) => { console.error('Failed to resolve assets', e); return undefined; })
      .finally(() => assetsJsonRequest = null);
    return assetsJsonRequest;
  }
  return assetsJson;
};

// The dependency map, fetched once and kept.
//
// @return the dependency map; undefined if it could not be loaded
const getAssetDependenciesJson = async function(): Promise<Record<string, string[]> | undefined> {
  if (!assetDependenciesJson) {
    assetDependenciesJsonRequest ??= fetch("/libs/iap/resources/assetDependencies.json")
      .then(response => response.ok ? readJson<Record<string, string[]>>(response) : Promise.reject(new Error(`Failed to load assetDependencies.json: ${response.status}`)))
      .then(json => assetDependenciesJson = json)
      .catch((_e: unknown) => {
        // A missing map means no asset declares dependencies. It is kept as an empty map, so that
        // it is not fetched again for every asset.
        assetDependenciesJson = {};
        return undefined;
      })
      .finally(() => assetDependenciesJsonRequest = null);
    return assetDependenciesJsonRequest;
  }
  return assetDependenciesJson;
};

// The name of an asset, without the `asset:` prefix and the query parameters, if it has them.
//
// @param {string} assetURL an asset name, or `asset:` followed by an asset name
// @return the asset name, such as "iap-commons.404.js"
const getAssetName = function(assetURL: string): string {
  let assetName = assetURL;
  if (assetName.startsWith(ASSET_PREFIX)) {
    assetName = assetName.slice(ASSET_PREFIX.length);
  }
  if (assetName.includes("?")) {
    assetName = assetName.slice(0, assetName.indexOf("?"));
  }
  return assetName;
}

// The URL an asset is fetched from. An `asset:` URL is looked up in the asset map, which gives the
// file name with its content hash. Any other URL is returned as it is.
//
// @param {string} assetURL a URL, or `asset:` followed by an asset name
// @return the URL to fetch; an empty string if the asset is unknown or the asset map could not be loaded
const getAssetURL = async function(assetURL: string): Promise<string> {
  if (!assetURL.startsWith(ASSET_PREFIX)) {
    return assetURL;
  }

  const assetName = getAssetName(assetURL);
  return getAssetsJson()
    .then(json => {
      if (!json?.[assetName]) {
        console.error(`Unknown asset ${assetURL}`);
        return "";
      }
      return "/libs/iap/resources/" + json[assetName];
    });
}

// The assets an asset depends on.
//
// @param {string} assetURL an asset name, such as "iap-commons.404.js", or `asset:` followed by one
// @return the asset URLs of its dependencies; empty if it has none
const getAssetDependencies = async function(assetURL: string): Promise<string[]> {
  const assetName = getAssetName(assetURL);
  return getAssetDependenciesJson()
    .then(json => json?.[assetName] ?? []);
}

// The query parameters of a URL.
//
// @param {string} assetURL a URL, or `asset:` followed by an asset name
// @return the query parameters; empty if the URL has none
const getURLParameters = (assetURL: string): URLSearchParams => {
  if (!assetURL.includes("?")) {
    return new URLSearchParams();
  }
  return new URLSearchParams(assetURL.slice(assetURL.indexOf("?") + 1));
}

// Fetches and evaluates a module, once. Later calls return the same module.
//
// @param {string} assetURL a URL, or `asset:` followed by an asset name
// @return the module; null if the asset is unknown
const loadModule = async function(assetURL: string): Promise<unknown> {
  const realURL = await getAssetURL(assetURL);
  // An unknown asset
  if (realURL === "") {
    return null;
  }
  return modules[realURL] ??= await import(/* webpackIgnore: true */realURL);
};

// Loads a component from an asset, after the assets it depends on. It is loaded once, and later calls
// return the same component.
//
// @param {string} assetURL a URL, or `asset:` followed by an asset name; a `component` query
//     parameter names the export to take
// @return the module's default export, or the export the `component` parameter names; null if the
//     asset is unknown
const loadAsset = async function(assetURL: string): Promise<unknown> {
  if (!assets[assetURL]) {
    const dependencies = await getAssetDependencies(assetURL);
    // Dependencies load recursively, so a cycle between them would never end. An asset already
    // loading further up the chain is skipped, and the call that started it awaits it.
    loading.add(assetURL);
    try {
      await Promise.all(dependencies
        .filter(dependency => !loading.has(dependency))
        .map(dependency => loadAsset(dependency)));
    } finally {
      loading.delete(assetURL);
    }
    return loadModule(assetURL)
      .then(module => {
        if (!module) {
          console.error(`Failed to load module ${assetURL}`);
          return null;
        }
        const parameters = getURLParameters(assetURL);
        const loaded = module as Record<string, unknown>;
        const componentName = parameters.get("component");
        return assets[assetURL] = componentName ? loaded[componentName] : loaded.default;
      });
  }

  return assets[assetURL];
};

// Renders a component from an asset, which is fetched only once this is mounted, such as when its
// route is first visited. Nothing renders until the asset loads, nor after a failure, which is logged.
//
// @param {string} url a URL, or `asset:` followed by an asset name
// @param {object} props the props for the loaded component
function LazyAsset({ url, ...props }: { url: string, [prop: string]: unknown }) {
  const [ Component, setComponent ] = useState<ComponentType<Record<string, unknown>> | null>(null);

  useEffect(() => {
    let cancelled = false;
    loadAsset(url)
      .then(component => {
        if (!cancelled) {
          setComponent(() => component as ComponentType<Record<string, unknown>>);
        }
      })
      .catch((err: unknown) => console.error(`Something went wrong loading the asset [${url}]`, err));
    return () => { cancelled = true; };
  }, [url]);

  return Component ? <Component {...props} /> : null;
}

export { getAssetURL, getURLParameters, loadAsset, LazyAsset };
