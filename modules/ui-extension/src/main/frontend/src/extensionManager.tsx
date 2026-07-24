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

// An extension is the parsed JSON returned by the repository for one extension; its
// shape depends on the extension point, so it is an open string-keyed record.
type Extension = Record<string, unknown>;

// Retrieves the JSON that lists all the extensions available for the given extension point.
// This is an asynchronous function, it will return a Promise that resolves to the actual JSON.
//
// @param {string} extensionPoint an extension point, either a repository path like `/apps/iap/ExtensionPoints/SidebarEntry`, or just a name that will be automatically prefixed with `/apps/iap/ExtensionPoints/`.
// @return a Promise that will resolve to the extension point JSON
const getExtensions = async function(extensionPoint: string): Promise<Extension[]> {
  return fetch(extensionPoint.startsWith("/") ? extensionPoint : `/apps/iap/ExtensionPoints/${extensionPoint}`)
    .then(response => response.ok ? response.json() as Promise<Extension[]> : Promise.reject(new Error(`Failed to load extensions from ${extensionPoint}: ${response.status}`)));
}

// Loads all the extensions for the given extension point.
// Other than retrieving and parsing the extension point JSON, it will also fetch remote assets such as code to execute or icons to display.
// This is an asynchronous function, it will return a Promise that resolves to the actual list of extensions.
//
// This loader is resilient: a problem with one extension (or even with the whole extension point) does not
// prevent the other extensions from loading. Specifically:
//  - If the extension point itself cannot be retrieved (e.g. it is not deployed), an empty list is returned.
//  - Any individual extension whose assets fail to load is logged and omitted from the result, so a single
//    broken extension can no longer take down every other extension of the same point.
// Every extension that IS returned is guaranteed to have all of its `asset:` properties resolved.
//
// @param {string} extensionPoint an extension point, either a repository path like `/apps/iap/ExtensionPoints/SidebarEntry`, or just a name that will be automatically prefixed with `/apps/iap/ExtensionPoints/`.
// @return a Promise that will resolve to an array of extensions, where each extension is the parsed JSON returned by the repository, with asset properties fetched and parsed
const loadExtensions = async function(extensionPoint: string): Promise<Extension[]> {
  let extensions: Extension[];
  try {
    extensions = await getExtensions(extensionPoint);
  } catch (error) {
    console.error(`Could not retrieve the extension point [${extensionPoint}]; treating it as empty.`, error);
    return [];
  }

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

// Loads all remote assets of an extension.
// Any direct property of the extension that starts with the `asset:` string will be fetched and `eval`-uated.
// The resulting asset will be stored back in the extension under the key without the (case insensitive) `URL` suffix.
// For example, if there's a `"iconUrl": "asset:/path/to/icon.js"`, then the real `/path/to/icon.js` will be fetched and evaluated, and the result will be stored under the `"icon"` property.
// A property whose asset URL carries a `lazy` query parameter (e.g. `"asset:/path/to/view.js?lazy"`) is resolved
// to a small component that renders <LazyAsset> instead of the loaded asset itself, so that fetching and
// evaluating the real asset is deferred to whenever that component actually gets mounted (e.g. only once a
// matching route is navigated to), rather than paying for it on every extension point load. A consumer never
// needs to know whether a given property was lazy or not - either way, it gets back a renderable component. This
// is opt-in and per-property, since some assets rely on side effects that run as soon as they are loaded, and
// cannot be deferred.
// This is an asynchronous function, it will return a Promise that resolves to the actual extension after all remote assets have been fetched.
//
// If any non-lazy asset cannot be loaded - either because the fetch fails, or because it resolves to nothing
// (e.g. an unknown asset name) - the returned Promise rejects, so that `loadExtensions` can omit this extension
// rather than hand back a half-loaded one that would later throw when a caller reads the missing component. A
// lazy asset that fails to load cannot be caught this way, since it isn't fetched until later; that failure is
// instead logged by <LazyAsset> itself when it happens.
//
// @param {object} extension an extension, the parsed JSON returned by the repository
// @return a Promise that will resolve to the extension after all non-lazy remote components have been fetched
const loadRemoteComponents = async function(extension: Extension): Promise<Extension> {
  await Promise.all(
    Object.entries(extension)
      .filter(([, value]) => typeof value === 'string' && value.startsWith("asset:"))
      .map(async ([key, rawValue]) => {
        // Guaranteed to be a string by the .filter() predicate above (typeof value === 'string'),
        // which TS cannot see across the separate .map() callback.
        const value = rawValue as string;
        const resolvedKey = key.replace(/url$/i, '');
        if (getURLParameters(value).has('lazy')) {
          extension[resolvedKey] = (props: Record<string, unknown>) => <LazyAsset url={value} {...props} />;
          return;
        }

        const asset = await loadAsset(value);
        if (asset == null) {
          const label = (extension['jcr:path'] as string | undefined)
            ?? (extension['iap:extensionName'] as string | undefined)
            ?? 'unknown';
          throw new Error(`Asset [${value}] for extension [${label}] resolved to nothing`);
        }
        extension[resolvedKey] = asset;
      })
  );
  return extension;
};

export { getExtensions, loadExtensions };
