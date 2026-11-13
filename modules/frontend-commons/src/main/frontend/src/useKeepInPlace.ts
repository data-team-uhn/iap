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

import { useCallback, useLayoutEffect, useRef } from "react";

// Keeps an element at the same place on the screen while the page changes above it. The returned
// function marks the element to keep, or none. When `change` next changes, the page scrolls by as
// much as the element moved, and the mark is cleared.
export function useKeepInPlace(change: unknown) {
  const kept = useRef<{ element: HTMLElement; top: number }>(undefined);
  useLayoutEffect(() => {
    const current = kept.current;
    kept.current = undefined;
    const shift = current?.element.isConnected ? current.element.getBoundingClientRect().top - current.top : 0;
    if (shift !== 0) {
      window.scrollBy(0, shift);
    }
  }, [ change ]);
  return useCallback((element?: HTMLElement) => {
    kept.current = element && { element, top: element.getBoundingClientRect().top };
  }, []);
}
