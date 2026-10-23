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

import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";

import { fadeEdges, useScrollEdges } from "@iap/frontend-commons/useScrollEdges";

// A list that says where it has more to show.
function List({ entries }: { entries: number }) {
  const ref = useRef<HTMLUListElement>(null);
  const { above, below } = useScrollEdges(ref);
  return (
    <>
      <output>{[ above && "more above", below && "more below" ].filter(Boolean).join(", ") || "all of it"}</output>
      <ul ref={ref} aria-label="Entries">
        {Array.from({ length: entries }, (_, index) => <li key={index}>{`Entry ${index + 1}`}</li>)}
      </ul>
    </>
  );
}

const getList = () => screen.getByRole("list", { name: "Entries" });
const getEdges = () => screen.getByRole("status");

// jsdom has no layout: a list is 100px high, an entry 50px unless grown, and the scroll position kept as set.
let entryHeight = 50;
let observed: Set<Element>;
let resized: (() => void) | undefined;

beforeEach(() => {
  entryHeight = 50;
  observed = new Set();
  vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(100);
  vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(function (this: HTMLElement) {
    return this.children.length * entryHeight;
  });
  vi.stubGlobal("ResizeObserver", class {
    constructor(callback: () => void) {
      resized = callback;
    }
    observe(element: Element) {
      observed.add(element);
    }
    unobserve(element: Element) {
      observed.delete(element);
    }
    disconnect() {
      observed.clear();
      resized = undefined;
    }
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function scrollTo(top: number) {
  getList().scrollTop = top;
  fireEvent.scroll(getList());
}

describe("useScrollEdges", () => {
  it("says there is nothing more when it all fits", () => {
    render(<List entries={2} />);
    expect(getEdges()).toHaveTextContent("all of it");
  });

  it("says there is more below at the top of a longer list", () => {
    render(<List entries={6} />);
    expect(getEdges()).toHaveTextContent("more below");
  });

  it("follows the scroll, to more both ways in the middle and more above at the end", () => {
    render(<List entries={6} />);

    scrollTo(100);
    expect(getEdges()).toHaveTextContent("more above, more below");

    // Within a pixel of the end, for sizes rounded by the browser
    scrollTo(199.5);
    expect(getEdges()).toHaveTextContent("more above");
  });

  it("measures again when the element changes size", () => {
    render(<List entries={6} />);
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(300);

    act(() => resized?.());

    expect(getEdges()).toHaveTextContent("all of it");
  });

  it("measures again when an entry grows, the element keeping its size", () => {
    render(<List entries={2} />);
    expect(observed).toContain(screen.getByText("Entry 2"));
    entryHeight = 80;

    act(() => resized?.());

    expect(getEdges()).toHaveTextContent("more below");
  });

  it("measures again as entries come and go, watching the ones there", async () => {
    const { rerender } = render(<List entries={2} />);

    rerender(<List entries={3} />);
    await act(async () => {});
    expect(getEdges()).toHaveTextContent("more below");
    const third = screen.getByText("Entry 3");
    expect(observed).toContain(third);

    rerender(<List entries={2} />);
    await act(async () => {});
    expect(getEdges()).toHaveTextContent("all of it");
    expect(observed).not.toContain(third);
  });

  it("says nothing more of an element not shown", () => {
    function Nothing() {
      const { above, below } = useScrollEdges(useRef<HTMLElement>(null));
      return <p>{above || below ? "more" : "nothing more"}</p>;
    }
    render(<Nothing />);

    expect(screen.getByText("nothing more")).toBeInTheDocument();
  });

  it("stops watching when the element goes", () => {
    const { unmount } = render(<List entries={6} />);

    unmount();

    expect(observed.size).toBe(0);
  });
});

describe("fadeEdges", () => {
  it("fades nothing out of an element showing all of itself", () => {
    expect(fadeEdges({ above: false, below: false })).toBeUndefined();
  });

  it("fades out the end that goes on, and only that one", () => {
    expect(fadeEdges({ above: false, below: true }))
      .toBe("linear-gradient(to bottom, black, black 32px, black calc(100% - 32px), transparent)");
    expect(fadeEdges({ above: true, below: false }))
      .toBe("linear-gradient(to bottom, transparent, black 32px, black calc(100% - 32px), black)");
    expect(fadeEdges({ above: true, below: true }))
      .toBe("linear-gradient(to bottom, transparent, black 32px, black calc(100% - 32px), transparent)");
  });
});
