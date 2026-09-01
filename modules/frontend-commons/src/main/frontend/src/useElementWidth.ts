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

import { useLayoutEffect, useState, type RefObject } from "react";

/**
 * The width of an element, kept current as it resizes, for a component that lays itself out by the
 * room it is given rather than by the size of the window.
 *
 * @returns the width in pixels; undefined until the element has been measured, or where the browser
 *     cannot observe resizing
 */
export function useElementWidth(element: RefObject<HTMLElement | null>): number | undefined {
  const [ width, setWidth ] = useState<number>();
  useLayoutEffect(() => {
    const target = element.current;
    if (!target || typeof ResizeObserver === "undefined") {
      return undefined;
    }
    const observer = new ResizeObserver(entries => {
      setWidth(entries[0]?.contentRect.width);
    });
    observer.observe(target);
    return () => { observer.disconnect(); };
  }, [ element ]);
  return width;
}
