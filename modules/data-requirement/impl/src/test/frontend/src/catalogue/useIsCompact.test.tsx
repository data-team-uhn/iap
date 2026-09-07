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

import { render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useIsCompact } from "@iap/data-requirement/catalogue/useIsCompact";

// MUI reads the breakpoint through matchMedia, which jsdom does not implement; without a stand-in
// useMediaQuery just reports false, so the narrow branch needs one.
const stubMatchMedia = (matches: boolean) => vi.stubGlobal("matchMedia", (query: string) => ({
  matches,
  media: query,
  onchange: null,
  addListener: () => { /* deprecated, unused */ },
  removeListener: () => { /* deprecated, unused */ },
  addEventListener: () => { /* no live changes in these tests */ },
  removeEventListener: () => { /* no live changes in these tests */ },
  dispatchEvent: () => false,
}));

function compactness() {
  let compact!: boolean;
  function Probe() {
    compact = useIsCompact();
    return null;
  }
  render(<Probe />);
  return compact;
}

describe("whether there is room for the selection beside the tree", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("says there is not, below the breakpoint", () => {
    stubMatchMedia(true);

    expect(compactness()).toBe(true);
  });

  it("says there is, above it", () => {
    stubMatchMedia(false);

    expect(compactness()).toBe(false);
  });
});
