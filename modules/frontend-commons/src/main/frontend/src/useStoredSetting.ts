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
// A setting kept in the browser's local storage, so that it outlasts a reload.

import { useCallback, useState } from "react";

// The setting stored under `key`, or `fallback` when none is stored, the stored value does not parse or
// fails `isValid`, or the browser does not allow storage.
function readSetting<T>(key: string, fallback: T, isValid: (value: unknown) => value is T): T {
  try {
    const stored = window.localStorage.getItem(key);
    const parsed: unknown = stored === null ? undefined : JSON.parse(stored);
    return isValid(parsed) ? parsed : fallback;
  } catch {
    return fallback;
  }
}

// A setting kept in the browser's local storage as JSON under `key`, and a function to change it.
// `fallback` stands in for a value never stored, a stored value `isValid` refuses, and storage the browser
// does not allow. When `key` changes, the setting kept under the new key is read.
export function useStoredSetting<T>(
  key: string,
  fallback: T,
  isValid: (value: unknown) => value is T,
): [ T, (value: T) => void ] {
  const [ state, setState ] = useState(() => ({ key, value: readSetting(key, fallback, isValid) }));
  // A new key is read during the render, so that no render shows the old key's value.
  let current = state;
  if (state.key !== key) {
    current = { key, value: readSetting(key, fallback, isValid) };
    setState(current);
  }

  const store = useCallback((next: T) => {
    setState({ key, value: next });
    try {
      window.localStorage.setItem(key, JSON.stringify(next));
    } catch {
      // Storage is not allowed, so the setting is kept in this state alone
    }
  }, [key]);

  return [ current.value, store ];
}
