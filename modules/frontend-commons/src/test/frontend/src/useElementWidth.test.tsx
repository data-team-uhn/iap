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

import { useRef } from "react";

import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { FakeResizeObserver } from "@iap/frontend-commons/resizeObserver.fixture";
import { useElementWidth } from "@iap/frontend-commons/useElementWidth";

/** Draws a box and says how wide the hook reports it. */
function Measured() {
  const box = useRef<HTMLDivElement>(null);
  const width = useElementWidth(box);
  return <div ref={box}>{width === undefined ? "unmeasured" : `${String(width)}px`}</div>;
}

describe("useElementWidth", () => {
  describe("where the browser observes resizing", () => {
    beforeEach(() => {
      vi.stubGlobal("ResizeObserver", FakeResizeObserver);
    });

    afterEach(() => {
      vi.unstubAllGlobals();
      FakeResizeObserver.latest = undefined;
    });

    it("reports the element's width, and follows it as it changes", () => {
      render(<Measured />);
      expect(screen.getByText("unmeasured")).toBeInTheDocument();

      act(() => { FakeResizeObserver.latest?.resize(640); });
      expect(screen.getByText("640px")).toBeInTheDocument();

      act(() => { FakeResizeObserver.latest?.resize(320); });
      expect(screen.getByText("320px")).toBeInTheDocument();
    });

    it("stops observing when the element goes", () => {
      const { unmount } = render(<Measured />);
      const observer = FakeResizeObserver.latest;

      unmount();

      expect(observer?.disconnect).toHaveBeenCalled();
    });
  });

  it("stays unmeasured where the browser cannot observe resizing", () => {
    vi.stubGlobal("ResizeObserver", undefined);
    render(<Measured />);

    expect(screen.getByText("unmeasured")).toBeInTheDocument();
    vi.unstubAllGlobals();
  });
});
