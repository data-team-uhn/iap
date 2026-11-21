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
// A hook telling whether an element is narrower than a given width as it is resized: the room a component
// has, rather than the screen's, as for a component in a side panel.
import { useLayoutEffect, useState } from "react";

// Whether the element is narrower than the width given, in pixels, kept up to date as it is resized. It
// counts as narrow until it is measured.
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
