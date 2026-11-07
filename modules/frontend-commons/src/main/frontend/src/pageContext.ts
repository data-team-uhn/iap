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

import { useEffect, useSyncExternalStore, type ReactNode } from "react";

// PRETOTYPE. What a page shows about itself in the app bar, which stays in view while the page scrolls: its
// title; its short form, such as an identifier, which is all a phone's bar has room for; details to go with the
// short form, such as a person; badges such as its status tags; and something to end with, such as a countdown.
export interface PageContext {
  title: string;
  short?: string;
  details?: string;
  badges?: ReactNode;
  end?: ReactNode;
  // Tools for the page, such as its search, shown at the bar's far end on wide screens; the page shows them
  // itself on narrower ones
  tools?: ReactNode;
  // Whether the page fills the window as a workspace, with nothing under it, such as the footer
  workspace?: boolean;
}

// Kept on `window`, like the page crumbs, so the page and the bar share it whichever bundles they were loaded from.
export const STORE_KEY = "__iapPageContext";
const CHANGE_EVENT = "iap:pagecontextchange";

const holder = () => window as unknown as Record<string, PageContext | undefined>;

export const getPageContext = (): PageContext | undefined => holder()[STORE_KEY];

const setPageContext = (context: PageContext | undefined): void => {
  holder()[STORE_KEY] = context;
  window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
};

const subscribe = (onChange: () => void): (() => void) => {
  window.addEventListener(CHANGE_EVENT, onChange);
  return () => window.removeEventListener(CHANGE_EVENT, onChange);
};

// For a page: shows this in the app bar while it is shown, and takes it away when it goes.
export function usePublishedPageContext(context: PageContext | undefined): void {
  useEffect(() => {
    setPageContext(context);
  }, [ context ]);
  useEffect(() => () => setPageContext(undefined), []);
}

// For the app bar: what the current page asked it to show.
export function usePageContext(): PageContext | undefined {
  return useSyncExternalStore(subscribe, getPageContext);
}
