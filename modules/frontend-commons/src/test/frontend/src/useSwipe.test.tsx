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
import { useState, type CSSProperties } from "react";

import { createTheme } from "@mui/material/styles";
import { cleanup, fireEvent, render, renderHook, screen } from "@testing-library/react";

import { useSwipe } from "@iap/frontend-commons/useSwipe";

const ITEMS = [ "First", "Second", "Third" ];
const { duration } = createTheme().transitions;

// A card showing one of the items at a time, swiped through, with a field and a button on it: a new card
// for each item, unless kept the same.
function Cards({ start = 1, kept = false, style, onClick }: {
  start?: number;
  kept?: boolean;
  style?: CSSProperties;
  onClick?: () => void;
}) {
  const [ index, setIndex ] = useState(start);
  const ref = useSwipe(
    index > 0 ? () => setIndex(index - 1) : undefined,
    index < ITEMS.length - 1 ? () => setIndex(index + 1) : undefined,
  );
  return (
    <section key={kept ? "kept" : index} ref={ref} aria-label="Cards" style={style}>
      <button type="button" onClick={onClick}>{ITEMS[index]}</button>
      <input aria-label="Note" />
    </section>
  );
}

const getCard = () => screen.getByRole("region", { name: "Cards" });

// A touch going from one point to another, starting on the card's button unless on something else
function touch(from: [number, number], to: [number, number], target: Element = screen.getByRole("button")) {
  const at = ([ clientX, clientY ]: [number, number]) => ({ pointerType: "touch", pointerId: 1, clientX, clientY });
  fireEvent.pointerDown(target, at(from));
  fireEvent.pointerMove(target, at(to));
  fireEvent.pointerUp(target, at(to));
}

let animate: ReturnType<typeof vi.fn>;

function stubReducedMotion(reduce: boolean) {
  vi.stubGlobal("matchMedia", (media: string) => ({ media, matches: reduce && media.includes("reduce") }));
}

describe("useSwipe", () => {
  beforeEach(() => {
    animate = vi.fn();
    Element.prototype.animate = animate as unknown as typeof Element.prototype.animate;
    stubReducedMotion(false);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("leaves up and down to the browser, to scroll the page, and pinching, to zoom it", () => {
    render(<Cards />);
    expect(getCard().style.touchAction).toBe("pan-y pinch-zoom");
  });

  it("goes to the next item when swiped left, and the previous one when swiped right", () => {
    render(<Cards />);

    touch([ 200, 100 ], [ 100, 100 ]);
    expect(screen.getByRole("button")).toHaveTextContent("Third");

    touch([ 100, 100 ], [ 200, 100 ]);
    touch([ 100, 100 ], [ 200, 100 ]);
    expect(screen.getByRole("button")).toHaveTextContent("First");
  });

  it("goes the other way round in a right-to-left layout", () => {
    render(<Cards style={{ direction: "rtl" }} />);

    touch([ 200, 100 ], [ 100, 100 ]);

    expect(screen.getByRole("button")).toHaveTextContent("First");
  });

  it("takes a short move, or one more up or down than across, for a tap or a scroll", () => {
    render(<Cards />);

    touch([ 200, 100 ], [ 160, 100 ]);
    touch([ 200, 100 ], [ 100, 160 ]);

    expect(screen.getByRole("button")).toHaveTextContent("Second");
  });

  it("follows the finger across, and only a third as far where there is nothing that way", () => {
    render(<Cards start={2} />);
    fireEvent.pointerDown(getCard(), { pointerType: "touch", pointerId: 1, clientX: 200, clientY: 100 });

    fireEvent.pointerMove(getCard(), { pointerType: "touch", pointerId: 1, clientX: 230, clientY: 100 });
    expect(getCard().style.transform).toBe("translateX(30px)");

    fireEvent.pointerMove(getCard(), { pointerType: "touch", pointerId: 1, clientX: 170, clientY: 100 });
    expect(getCard().style.transform).toBe("translateX(-10px)");
  });

  it("does not move for a finger going more up or down than across, or a mere touch", () => {
    render(<Cards />);
    fireEvent.pointerDown(getCard(), { pointerType: "touch", pointerId: 1, clientX: 200, clientY: 100 });

    fireEvent.pointerMove(getCard(), { pointerType: "touch", pointerId: 1, clientX: 196, clientY: 100 });
    fireEvent.pointerMove(getCard(), { pointerType: "touch", pointerId: 1, clientX: 180, clientY: 150 });

    expect(getCard().style.transform).toBe("");
  });

  it("springs back where there is nothing that way", () => {
    render(<Cards start={2} />);

    touch([ 200, 100 ], [ 110, 100 ]);

    expect(screen.getByRole("button")).toHaveTextContent("Third");
    expect(getCard().style.transform).toBe("");
    expect(animate).toHaveBeenCalledWith(
      [ { transform: "translateX(-30px)" }, { transform: "none" } ],
      expect.objectContaining({ duration: duration.shortest }),
    );
  });

  it("springs back when the browser takes the touch over", () => {
    render(<Cards />);
    fireEvent.pointerDown(getCard(), { pointerType: "touch", pointerId: 1, clientX: 200, clientY: 100 });
    fireEvent.pointerMove(getCard(), { pointerType: "touch", pointerId: 1, clientX: 170, clientY: 100 });

    fireEvent.pointerCancel(getCard(), { pointerType: "touch", pointerId: 1 });

    expect(getCard().style.transform).toBe("");
    expect(animate).toHaveBeenCalledTimes(1);
  });

  it("slides the next item in from where the finger went", () => {
    render(<Cards />);

    touch([ 200, 100 ], [ 100, 100 ]);
    expect(animate).toHaveBeenLastCalledWith(
      [ { transform: "translateX(48px)", opacity: 0.4 }, { transform: "none", opacity: 1 } ],
      expect.objectContaining({ duration: duration.shorter }),
    );

    touch([ 100, 100 ], [ 200, 100 ]);
    expect(animate).toHaveBeenLastCalledWith(
      [ { transform: "translateX(-48px)", opacity: 0.4 }, { transform: "none", opacity: 1 } ],
      expect.anything(),
    );
  });

  it("slides in only the element shown for the swipe, not one shown later", () => {
    const { rerender } = render(<Cards kept />);

    touch([ 200, 100 ], [ 100, 100 ]);
    expect(screen.getByRole("button")).toHaveTextContent("Third");
    rerender(<Cards />);

    expect(animate).not.toHaveBeenCalled();
  });

  it("moves nothing on its own for someone who would rather not see motion", () => {
    stubReducedMotion(true);
    render(<Cards start={2} />);

    touch([ 200, 100 ], [ 100, 100 ]);
    touch([ 100, 100 ], [ 200, 100 ]);

    expect(screen.getByRole("button")).toHaveTextContent("Second");
    expect(animate).not.toHaveBeenCalled();
  });

  it("leaves a mouse dragging across to select text", () => {
    render(<Cards />);
    const at = (clientX: number) => ({ pointerType: "mouse", pointerId: 1, clientX, clientY: 100 });

    fireEvent.pointerDown(getCard(), at(200));
    fireEvent.pointerMove(getCard(), at(100));
    fireEvent.pointerUp(getCard(), at(100));

    expect(getCard().style.transform).toBe("");
    expect(screen.getByRole("button")).toHaveTextContent("Second");
  });

  it("leaves a swipe starting in a field to the field", () => {
    render(<Cards />);

    touch([ 200, 100 ], [ 100, 100 ], screen.getByRole("textbox", { name: "Note" }));

    expect(screen.getByRole("button")).toHaveTextContent("Second");
  });

  it("does not pass on the click a browser may end a swipe with, on the item swiped to", () => {
    const onClick = vi.fn();
    render(<Cards onClick={onClick} />);

    touch([ 200, 100 ], [ 300, 100 ]);
    fireEvent.click(screen.getByRole("button"), { detail: 1 });

    expect(screen.getByRole("button")).toHaveTextContent("First");
    expect(onClick).not.toHaveBeenCalled();
  });

  it("passes on a tap's click, and a key's after a swipe", () => {
    const onClick = vi.fn();
    render(<Cards onClick={onClick} />);

    touch([ 200, 100 ], [ 300, 100 ]);
    fireEvent.click(screen.getByRole("button"), { detail: 0 });
    touch([ 200, 100 ], [ 200, 100 ]);
    fireEvent.click(screen.getByRole("button"), { detail: 1 });

    expect(onClick).toHaveBeenCalledTimes(2);
  });

  it("does nothing given no element", () => {
    const { result } = renderHook(() => useSwipe(vi.fn(), vi.fn()));
    expect(result.current(null)).toBeUndefined();
  });

  it("lets go of the element when it is no longer given", () => {
    const { unmount } = render(<Cards />);
    const card = getCard();
    fireEvent.pointerDown(card, { pointerType: "touch", pointerId: 1, clientX: 200, clientY: 100 });
    fireEvent.pointerMove(card, { pointerType: "touch", pointerId: 1, clientX: 170, clientY: 100 });

    unmount();

    expect(card.style.touchAction).toBe("");
    expect(card.style.transform).toBe("");
  });
});
