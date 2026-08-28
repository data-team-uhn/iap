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

// Registers the jest-dom matchers (toBeInTheDocument, toHaveAttribute, ...) on
// Vitest's `expect`. @testing-library/react auto-cleans the DOM between tests
// when Vitest globals are enabled, so no explicit afterEach cleanup is needed.
import "@testing-library/jest-dom/vitest";

import { configure } from "@testing-library/react";
import ResizeObserverPolyfill from "resize-observer-polyfill";

// findBy*/waitFor default to 1s, which regularly times out on slow CI runners once a test
// involves debounced inputs and asynchronous refetches; give them more headroom everywhere
// (fast machines are unaffected: waiting stops as soon as the condition is met)
configure({ asyncUtilTimeout: 5000 });

// jsdom has no ResizeObserver, but MUI X v9's data grid depends on it for its layout; without
// it, rendering falls back to timing-sensitive paths that flake under load
if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = ResizeObserverPolyfill;
}

// Node 22+ defines its own experimental global `localStorage`, which is undefined unless the
// process runs with --localstorage-file, and which prevents Vitest from exposing jsdom's
// localStorage on the global scope. Components under test only need Storage semantics, so give
// them a simple in-memory implementation.
//
// Ask the property descriptor rather than reading the value: Node's global is an accessor that
// emits an ExperimentalWarning the first time it is read, so the obvious `!globalThis.localStorage`
// prints a warning per test process — one per test file, since setup files run per file. A
// descriptor with no `value` is either absent or that accessor; either way ours is needed.
if (!Object.getOwnPropertyDescriptor(globalThis, "localStorage")?.value) {
  const stored = new Map<string, string>();
  const memoryStorage: Storage = {
    get length() {
      return stored.size;
    },
    clear: () => stored.clear(),
    getItem: (key: string) => stored.get(key) ?? null,
    key: (index: number) => [...stored.keys()][index] ?? null,
    removeItem: (key: string) => {
      stored.delete(key);
    },
    setItem: (key: string, value: string) => {
      stored.set(key, String(value));
    },
  };
  Object.defineProperty(globalThis, "localStorage", { value: memoryStorage, configurable: true });
  Object.defineProperty(window, "localStorage", { value: memoryStorage, configurable: true });
}
