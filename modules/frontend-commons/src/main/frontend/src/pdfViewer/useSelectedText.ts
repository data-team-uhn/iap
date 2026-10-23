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
import { useCallback, useEffect, useRef, useState } from "react";

import { getSelectedSpans, getTextEnd } from "./markLayer";
import { getCommentButtonPlace, type CommentButtonPlace } from "./pdfViewerModel";

import type { MarkSpan } from "./marksModel";
import type { ShownDocument } from "./usePdfView";

// How long the selection must stay the same, with the pointer up, before it counts as settled. A phone's
// handles change it in steps.
export const SETTLE_TIME = 300;

// Text selected in the document, and where to offer to act on it.
export interface SelectedText {
  spans: MarkSpan[];
  place: CommentButtonPlace;
}

// The whole selection, from the start of its first range to the end of its last. A browser may give a
// selection across the document as several ranges.
function getSelectedRange(selection: Selection | null): Range | undefined {
  if (selection === null || selection.isCollapsed) {
    return undefined;
  }
  const range = selection.getRangeAt(0).cloneRange();
  const last = selection.getRangeAt(selection.rangeCount - 1);
  range.setEnd(last.endContainer, last.endOffset);
  return range;
}

// The text selected in the document shown, once the selection settles, while enabled. It is put aside
// while the selection changes, the document scrolls or zooms, or the pointer is down in it, and comes
// back once all is still. Dismissing it puts it aside until the selection changes. Holding it keeps it
// until the pointer is pressed anywhere else, even when pressing the button clears the selection, as a
// tap does on some phones.
export function useSelectedText(shown: ShownDocument | undefined, enabled: boolean) {
  const [selected, setSelected] = useState<SelectedText | undefined>(undefined);
  const heldRef = useRef(false);

  useEffect(() => {
    if (!enabled || shown === undefined) {
      return undefined;
    }
    const { frame, eventBus } = shown;
    const stop = new AbortController();
    const { signal } = stop;
    let settling = 0;
    let pressed = false;
    let byTouch = false;
    const read = () => {
      const range = getSelectedRange(document.getSelection());
      const spans = range === undefined ? [] : getSelectedSpans(frame, range);
      const end = getTextEnd(frame, spans);
      if (end === undefined) {
        return;
      }
      const box = frame.getBoundingClientRect();
      const place = getCommentButtonPlace(
        { x: end.right - box.left, bottom: end.bottom - box.top },
        { width: box.width, height: box.height },
        byTouch,
      );
      setSelected({ spans, place });
    };
    const settle = () => {
      if (heldRef.current) {
        return;
      }
      setSelected(undefined);
      window.clearTimeout(settling);
      if (!pressed) {
        settling = window.setTimeout(read, SETTLE_TIME);
      }
    };
    document.addEventListener("selectionchange", settle, { signal });
    frame.addEventListener("scroll", settle, { signal });
    eventBus.on("scalechanging", settle, { signal });
    // Before the button's own handler, which holds the selection again when it is the button pressed.
    window.addEventListener("pointerdown", () => {
      heldRef.current = false;
    }, { signal, capture: true });
    frame.addEventListener("pointerdown", event => {
      pressed = true;
      byTouch = event.pointerType === "touch";
      settle();
    }, { signal });
    // A phone takes over a long press to select, cancelling the pointer, and moves its handles itself.
    for (const type of [ "pointerup", "pointercancel" ]) {
      window.addEventListener(type, () => {
        if (pressed) {
          pressed = false;
          settle();
        }
      }, { signal });
    }
    return () => {
      stop.abort();
      window.clearTimeout(settling);
      heldRef.current = false;
      setSelected(undefined);
    };
  }, [shown, enabled]);

  const hold = useCallback(() => {
    heldRef.current = true;
  }, []);

  const dismiss = useCallback(() => {
    heldRef.current = false;
    setSelected(undefined);
  }, []);

  return { selected, hold, dismiss };
}
