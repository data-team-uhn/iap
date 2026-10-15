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
import { useLayoutEffect, useState } from "react";

// Below this width, a viewer shows its passages one at a time above the document rather than beside it.
export const NARROW_VIEWER_WIDTH = 720;

// Whether the element is narrower than the width given, as it changes. Narrow until it is measured.
export function useNarrowerThan(element: HTMLElement | null, width: number): boolean {
  const [narrow, setNarrow] = useState(true);

  useLayoutEffect(() => {
    if (element === null) {
      return undefined;
    }
    const measure = () => setNarrow(element.getBoundingClientRect().width < width);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [element, width]);

  return narrow;
}

// Whether the element, a viewer, is narrower than NARROW_VIEWER_WIDTH.
export function useNarrowViewer(viewer: HTMLElement | null): boolean {
  return useNarrowerThan(viewer, NARROW_VIEWER_WIDTH);
}
