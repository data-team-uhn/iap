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

import { renderHook } from "@testing-library/react";

import { useKeepInPlace } from "@iap/frontend-commons/useKeepInPlace";

// An element whose top is at `top` pixels down the screen, as long as `top` holds
function elementAt(position: { top: number }): HTMLElement {
  const element = document.createElement("div");
  document.body.append(element);
  element.getBoundingClientRect = () => ({ top: position.top }) as DOMRect;
  return element;
}

describe("useKeepInPlace", () => {
  const scrollBy = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("scrollBy", scrollBy);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    scrollBy.mockReset();
    document.body.replaceChildren();
  });

  it("scrolls by as much as the element kept moved when the page changed", () => {
    const position = { top: 200 };
    const { result, rerender } = renderHook(({ change }) => useKeepInPlace(change), { initialProps: { change: 1 } });

    result.current(elementAt(position));
    position.top = 260;
    rerender({ change: 2 });

    expect(scrollBy).toHaveBeenCalledWith(0, 60);
  });

  it("keeps an element through one change only", () => {
    const position = { top: 200 };
    const { result, rerender } = renderHook(({ change }) => useKeepInPlace(change), { initialProps: { change: 1 } });

    result.current(elementAt(position));
    rerender({ change: 2 });
    position.top = 260;
    rerender({ change: 3 });

    expect(scrollBy).not.toHaveBeenCalled();
  });

  it("leaves the page where it is for no element, or one no longer on it", () => {
    const position = { top: 200 };
    const { result, rerender } = renderHook(({ change }) => useKeepInPlace(change), { initialProps: { change: 1 } });
    const element = elementAt(position);

    result.current(element);
    element.remove();
    position.top = 260;
    rerender({ change: 2 });
    result.current();
    rerender({ change: 3 });

    expect(scrollBy).not.toHaveBeenCalled();
  });
});
