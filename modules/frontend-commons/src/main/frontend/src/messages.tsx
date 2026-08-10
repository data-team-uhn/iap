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

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

// The catalog of developer-authored interface strings. The server decides which language to answer in,
// from the browser's own Accept-Language, so nothing here has to know or guess the reader's locale.
const INTERFACE_CATALOG = "iap.interface";

const CATALOG_URL = "/libs/iap/messages.json";

type Catalog = Record<string, string>;

// One catalog per name and language, and one request for it however many components ask at once — the
// same fetch-once shape assetManager uses, for the same reason: every page needs this before it can render.
// Keyed by language as well as by name, or switching language would be answered from the cache in the
// language just left.
const catalogs = new Map<string, Catalog>();
const requests = new Map<string, Promise<Catalog>>();

const cacheKey = (catalog: string, locale: string | null) => `${catalog}\u0000${locale ?? ""}`;

// The language the URL names, if it names one.
//
// Read from the URL rather than from a stored preference because the server has already used this same
// parameter to render the page's content-driven text into its <meta> tags. One source of truth for both
// halves is what keeps them in the same language; a preference the browser kept to itself could not, and
// the page would sign you in in French under an English heading.
//
// It follows that changing language is a page load, not a re-render — the server has to be asked again.
// It also follows that the choice lasts as long as the URL does, which is the whole of the sign-in page
// and no further. A preference that outlives the visit belongs to a signed-in user's profile.
export const currentLocale = function(): string | null {
  return new URLSearchParams(window.location.search).get("locale");
};

// The language this page was rendered in, as the server put it on <html lang>.
//
// Not the same question as currentLocale() above, which reports what the URL asked for. This reports what
// was actually served: a request for a language the deployment does not offer is answered in the default,
// and it is that answer the page is written in.
export const readerLanguage = function(): string {
  return document.documentElement.lang;
};

// A repository URL asking for its content in the reader's language.
//
// Repository content is translated by the serializer, which is driven by the resource and its selectors
// and never sees a request — so the language has to be in the URL. That is deliberate rather than
// awkward: the URL stays a truthful cache key, and one address cannot come back in two languages
// depending on who asked.
//
// Reader-facing fetches only. Content fetched to be *edited* must arrive as written, or an author would
// be shown a translation and save it over the source — which is why this is something a caller opts into
// by name rather than something every fetch does.
//
// Nothing calls this yet, and that is a statement about the content rather than about the helper: every
// repository node a reader's page fetches today is a bare nt:unstructured with no resource type, so
// Sling's own default renderer serves it and our serializer — the only thing that would honour this
// selector — never runs. On such a node the selector does not merely fail to translate, it fails to
// resolve. Giving those nodes a type is what unblocks this.
//
// @param url a repository URL, with the extension it is to be served with
// @return the same URL with a language selector, or unchanged where the page names no language
export const localized = function(url: string): string {
  const language = readerLanguage();
  const path = url.split(/[?#]/)[0];
  const extension = path.lastIndexOf(".");
  // Without an extension there is nowhere for a selector to go: selectors sit between the path and the
  // extension, so a bare path would be served by whatever handles it and the selector read as part of
  // the resource's own name.
  if (!language || extension < 0) {
    return url;
  }
  return path.slice(0, extension) + ".localize:" + language + path.slice(extension) + url.slice(path.length);
};

// Fetches a catalog, reusing the one already loaded or the request already in flight.
//
// A catalog that cannot be fetched resolves to an empty one rather than rejecting: a deployment that has
// not been translated yet, or a server that answered badly, should still render — in the source language,
// which is what an empty catalog produces.
//
// @param catalog which catalog to load, defaulting to the interface strings
// @return a Promise resolving to the catalog's messages
export const loadMessages = async function(catalog: string = INTERFACE_CATALOG): Promise<Catalog> {
  const locale = currentLocale();
  const key = cacheKey(catalog, locale);
  const loaded = catalogs.get(key);
  if (loaded) {
    return loaded;
  }
  let request = requests.get(key);
  if (!request) {
    const named = locale === null ? "" : `&locale=${encodeURIComponent(locale)}`;
    request = fetch(`${CATALOG_URL}?catalog=${encodeURIComponent(catalog)}${named}`)
      .then(response => (response.ok ? response.json() as Promise<{ messages?: Catalog }> : { messages: {} }))
      .then(body => {
        const messages = body.messages ?? {};
        catalogs.set(key, messages);
        return messages;
      })
      .catch(() => ({}));
    requests.set(key, request);
  }
  return request;
};

// Clears what has been loaded. Only for tests, which would otherwise share one process-wide catalog.
export const forgetMessages = function(): void {
  catalogs.clear();
  requests.clear();
};

// Puts a catalog in place without fetching it. Only for tests: a component test cares what the interface
// says, not how the catalog reached the browser, and seeding it means MessagesProvider has something to
// render on its first pass rather than every test having to wait out a request.
//
// @param messages the messages to make available
// @param catalog which catalog they belong to
export const seedMessages = function(messages: Catalog, catalog: string = INTERFACE_CATALOG): void {
  catalogs.set(cacheKey(catalog, currentLocale()), messages);
};

const MessagesContext = createContext<Catalog>({});

interface MessagesProviderProps {
  catalog?: string;
  children: ReactNode;
}

// Loads a catalog and puts it in reach of everything below.
//
// Nothing is rendered until the catalog has settled, and that is deliberate. Rendering first would show
// either the raw keys or the English source for a moment and then swap, and both are worse than a short
// wait: the first is unreadable, and the second makes an untranslated page indistinguishable from a
// translated one that has not arrived yet — including to the pseudo-locale check, whose entire job is to
// notice English on screen.
export function MessagesProvider({ catalog = INTERFACE_CATALOG, children }: MessagesProviderProps) {
  const [ messages, setMessages ] = useState<Catalog | null>(
    catalogs.get(cacheKey(catalog, currentLocale())) ?? null);

  useEffect(() => {
    let current = true;
    void loadMessages(catalog).then(loaded => {
      if (current) {
        setMessages(loaded);
      }
    });
    return () => {
      current = false;
    };
  }, [ catalog ]);

  if (messages === null) {
    return null;
  }
  return <MessagesContext.Provider value={messages}>{children}</MessagesContext.Provider>;
}

// The messages of the surrounding catalog, as a lookup by key.
//
// A key with no message answers with the key itself: visible, harmless, and obviously wrong, which is
// what a missing message should be at runtime. Stopping one from reaching a release is the build's key
// check, not this.
//
// Messages take no arguments yet. When one needs them, this is where an ICU MessageFormat
// implementation goes — `intl-messageformat` reads the same MessageFormat 1 syntax the server formats
// with, which is one of the reasons that syntax was chosen.
//
// @return a function from message key to message
export function useMessage(): (key: string) => string {
  const messages = useContext(MessagesContext);
  return (key: string) => messages[key] ?? key;
}
