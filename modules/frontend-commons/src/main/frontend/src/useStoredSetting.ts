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
import { useCallback, useState } from "react";

function readSetting<T>(key: string, fallback: T, isValid: (value: unknown) => value is T): T {
  try {
    const stored = window.localStorage.getItem(key);
    const parsed: unknown = stored === null ? undefined : JSON.parse(stored);
    return isValid(parsed) ? parsed : fallback;
  } catch {
    return fallback;
  }
}

// A setting kept in the browser's local storage, as JSON, under the key given. The fallback stands for a
// value never stored, one stored that the check refuses, and storage the browser does not allow, as in a
// private window. Given another key, it reads the setting kept under that one.
export function useStoredSetting<T>(
  key: string,
  fallback: T,
  isValid: (value: unknown) => value is T,
): [ T, (value: T) => void ] {
  const [ state, setState ] = useState(() => ({ key, value: readSetting(key, fallback, isValid) }));
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
      // Kept for this visit only
    }
  }, [key]);

  return [ current.value, store ];
}
