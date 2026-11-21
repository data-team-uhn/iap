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
// Whether the viewer is narrow, updated as it is resized.
import { useNarrowerThan } from "@iap/frontend-commons/useNarrowerThan";

// The width, in pixels, below which a viewer shows its passages one at a time above the document rather
// than in a list beside it.
const NARROW_VIEWER_WIDTH = 720;

// Whether the viewer's element is narrower than NARROW_VIEWER_WIDTH.
export function useNarrowViewer(viewer: HTMLElement | null): boolean {
  return useNarrowerThan(viewer, NARROW_VIEWER_WIDTH);
}
