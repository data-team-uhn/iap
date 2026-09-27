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

import { useEffect, useSyncExternalStore } from "react";

// Crumbs a page adds to the breadcrumb trail, after the ancestors its path already names: what the
// path alone cannot say, such as the schema a version's page belongs to, under that schema's title.
export interface PageCrumb {
  path: string;
  label: string;
}

// Kept on `window`, like the persona store, so the page and the trail share it whichever bundles they
// were loaded from.
export const STORE_KEY = "__iapPageCrumbs";
const CHANGE_EVENT = "iap:pagecrumbschange";

const NONE: PageCrumb[] = [];

const holder = () => window as unknown as Record<string, PageCrumb[] | undefined>;

export const getPageCrumbs = (): PageCrumb[] => holder()[STORE_KEY] ?? NONE;

const setPageCrumbs = (crumbs: PageCrumb[]): void => {
  holder()[STORE_KEY] = crumbs;
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
};

const subscribe = (onChange: () => void): (() => void) => {
  window.addEventListener(CHANGE_EVENT, onChange);
  return () => window.removeEventListener(CHANGE_EVENT, onChange);
};

// For a page: adds these crumbs while it is shown, and takes them away when it goes.
export function usePageCrumbs(crumbs: PageCrumb[]): void {
  const key = JSON.stringify(crumbs);
  useEffect(() => {
    setPageCrumbs(JSON.parse(key) as PageCrumb[]);
    return () => setPageCrumbs(NONE);
  }, [ key ]);
}

// For the trail: the crumbs the current page adds, re-rendering when they change.
export const useAddedCrumbs = (): PageCrumb[] => useSyncExternalStore(subscribe, getPageCrumbs);
