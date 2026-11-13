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

import { getURLParameters, loadAsset, LazyAsset } from '@iap/frontend-commons/assetManager';
import { readJson } from '@iap/frontend-commons/requestFailure';

import { type Extension } from './ExtensionList';

// The enabled extensions of an extension point, in their `defaultOrder`, as the server serializes them.
//
// @param {string} extensionPoint the node name of an extension point under `/apps/iap/ExtensionPoints/`,
//     such as `Views`, or its repository path, such as `/apps/iap/ExtensionPoints/Views`
// @return the extensions; rejects if the extension point cannot be read
const getExtensions = async function(extensionPoint: string): Promise<Extension[]> {
  return fetch(extensionPoint.startsWith("/") ? extensionPoint : `/apps/iap/ExtensionPoints/${extensionPoint}`)
    .then(response => response.ok ? readJson<Extension[]>(response) : Promise.reject(new Error(`Failed to load extensions from ${extensionPoint}: ${response.status}`)));
}

// The enabled extensions of an extension point, in their `defaultOrder`, with their `asset:` properties
// loaded. An extension whose assets fail to load is logged and left out.
//
// @param {string} extensionPoint the extension point, named as for `getExtensions`
// @return the extensions; empty if the point has none. Rejects if the extension point cannot be read.
const loadExtensions = async function(extensionPoint: string): Promise<Extension[]> {
  const extensions = await getExtensions(extensionPoint);
  const results = await Promise.allSettled(extensions.map(extension => loadRemoteComponents(extension)));
  return results
    .filter(result => {
      if (result.status === 'rejected') {
        console.error(`Skipping an extension of [${extensionPoint}] that failed to load.`, result.reason);
      }
      return result.status === 'fulfilled';
    })
    .map(result => (result).value);
};

// Loads the assets of an extension. A property whose value is `asset:` followed by an asset name, such
// as `"ext:renderURL": "asset:iap-commons.NoticeBanner.js"`, gives the component the asset exports. The
// component is stored under the property's name without a final `URL`, in any case, so `ext:renderURL`
// gives `ext:render`.
//
// An asset with a `lazy` query parameter, such as `asset:iap-x.View.js?lazy`, gives a component that
// loads the asset when it is first rendered. An asset that registers something when it is evaluated
// must not be lazy.
//
// A non-lazy asset that fails to load, or that is unknown, makes the returned promise reject. A lazy asset
// that fails is logged by `LazyAsset` when it is rendered.
//
// @param {object} extension an extension, as the server serializes it, which is changed in place
// @return the same extension, once its non-lazy assets have loaded
const loadRemoteComponents = async function(extension: Extension): Promise<Extension> {
  await Promise.all(
    Object.entries(extension)
      .filter(([, value]) => typeof value === 'string' && value.startsWith("asset:"))
      .map(async ([key, rawValue]) => {
        // The filter above has checked that it is a string
        const value = rawValue as string;
        const resolvedKey = key.replace(/url$/i, '');
        if (getURLParameters(value).has('lazy')) {
          extension[resolvedKey] = (props: Record<string, unknown>) => <LazyAsset url={value} {...props} />;
          return;
        }

        const asset = await loadAsset(value);
        if (asset == null) {
          const label = (extension['@path'] as string | undefined)
            ?? (extension['ext:name'] as string | undefined)
            ?? 'unknown';
          throw new Error(`Asset [${value}] for extension [${label}] resolved to nothing`);
        }
        extension[resolvedKey] = asset;
      })
  );
  return extension;
};

// Whether an extension is shown to someone acting as the given persona. An extension that names no
// `ext:personas` is shown to every persona.
//
// The persona alone decides, never what the user may do. An administrator acting as a submitter sees
// what a submitter sees.
const visibleInPersona = function(extension: Extension, persona: string): boolean {
  const personas = extension["ext:personas"];
  if (personas === undefined || personas === null) {
    return true;
  }
  // A multi-valued property arrives as an array, but a single value may arrive bare
  return (Array.isArray(personas) ? personas : [ personas ]).includes(persona);
};

export { getExtensions, loadExtensions, visibleInPersona };
