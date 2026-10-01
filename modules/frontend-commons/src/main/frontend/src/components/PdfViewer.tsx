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
import { useEffect, useLayoutEffect, useRef, useState } from "react";

import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import { Box, DialogContent, IconButton, Typography } from "@mui/material";
import { useTheme } from "@mui/material/styles";
import { flushSync } from "react-dom";

import LoadError from "./LoadError";
import { getNote, type CssBox } from "./pdfViewerModel";
import ResponsiveDialog from "./ResponsiveDialog";
import { usePdfViewer } from "./usePdfViewer";

import type { HighlightRect } from "../pdfQuoteLocator";
import type { PageViewport, PDFPageProxy, RenderTask } from "pdfjs-dist";

// A quote to find and highlight, and where to open it from.
export interface PdfQuotePassage {
  quote: string;
  // Where the passage lives, for display. Owned by the caller: this component only shows it.
  cite?: string;
  // The PDF to open to see the passage in place, at its page when the fragment names one
  // (`<url>#page=<n>`). Absent when there is nothing to open.
  source?: string;
}

interface PdfViewerProps {
  passage: PdfQuotePassage;
  onClose: () => void;
}

interface Drawing {
  canvas: HTMLCanvasElement;
  task: RenderTask;
}

// Room beside the page.
const FRAME_GUTTER = 16;
// Enough page to read, even when a long quote takes most of the dialog.
const MIN_FRAME_HEIGHT = 200;
const MAX_SCALE = 2;
// A drag resizes once per frame; the page is drawn again once that has stopped.
const RESIZE_SETTLE_MS = 150;
// About 32 MB of canvas: sharp on any screen a page fits, bounded on a dense one.
const MAX_CANVAS_PIXELS = 8_000_000;

function getFitScale(frameWidth: number, page: PDFPageProxy): number {
  return Math.min(MAX_SCALE, (frameWidth - FRAME_GUTTER) / page.getViewport({ scale: 1 }).width);
}

function convertToCss(viewport: PageViewport, rect: HighlightRect): CssBox {
  const [x1 = 0, y1 = 0, x2 = 0, y2 = 0] = viewport.convertToViewportRectangle([
    rect.x, rect.y, rect.x + rect.width, rect.y + rect.height,
  ]) as number[];
  return {
    left: Math.min(x1, x2),
    top: Math.min(y1, y2),
    width: Math.abs(x2 - x1),
    height: Math.abs(y2 - y1),
  };
}

function getPixelDensity(viewport: PageViewport): number {
  const screen = window.devicePixelRatio > 0 ? window.devicePixelRatio : 1;
  return Math.min(screen, Math.sqrt(MAX_CANVAS_PIXELS / (viewport.width * viewport.height)));
}

// Drops the canvas's pixels now, rather than when it is garbage collected.
function releaseCanvas(canvas: HTMLCanvasElement): void {
  canvas.width = 0;
  canvas.height = 0;
}

// Draws the page off screen at the screen's pixel density.
function startDrawing(pdfPage: PDFPageProxy, viewport: PageViewport): Drawing {
  const canvas = document.createElement("canvas");
  const context = canvas.getContext("2d");
  if (!context) {
    throw new Error("The browser gave no 2D canvas to draw on");
  }
  const density = getPixelDensity(viewport);
  canvas.width = Math.floor(viewport.width * density);
  canvas.height = Math.floor(viewport.height * density);
  const task = pdfPage.render({ canvasContext: context, viewport, transform: [ density, 0, 0, density, 0, 0 ] });
  return { canvas, task };
}

// Copies a finished drawing onto the shown canvas in one step, so the old picture stays up until
// the new one is ready.
function showDrawing(target: HTMLCanvasElement, drawing: HTMLCanvasElement): void {
  target.width = drawing.width;
  target.height = drawing.height;
  target.getContext("2d")?.drawImage(drawing, 0, 0);
  releaseCanvas(drawing);
}

// Opens the cited PDF on the page the quote came from, and highlights the quote when the PDF
// text contains it. The page still opens when the quote cannot be found.
export default function PdfViewer({ passage, onClose }: PdfViewerProps) {
  return (
    <ResponsiveDialog
      open
      title={passage.cite ?? "Document"}
      width="lg"
      withCloseButton
      onClose={onClose}
    >
      {/* A column, so the page frame takes the height left and scrolls on its own. The content
          scrolls as a whole only when a long quote leaves the frame less than its minimum. */}
      <DialogContent sx={{ display: "flex", flexDirection: "column", minHeight: 0 }}>
        <Typography variant="body2" sx={{ mb: 1 }}>{`“${passage.quote}”`}</Typography>
        {passage.source === undefined
          ? <Typography variant="caption">There is no document to open.</Typography>
          // Keyed so another document or quote starts over with nothing left from the last one.
          : (
            <PdfPassageView
              key={`${passage.source}\n${passage.quote}`}
              source={passage.source}
              quote={passage.quote}
            />
          )}
      </DialogContent>
    </ResponsiveDialog>
  );
}

function PdfPassageView({ source, quote }: { source: string; quote: string }) {
  const theme = useTheme();
  const frameRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  // The page on the canvas and its height, so a redraw of it can keep the same part in view.
  const shown = useRef<{ page: number; height: number } | undefined>(undefined);
  const { state, readMatches, showPage, retry, markDrawn, markDrawFailed } = usePdfViewer(source, quote);
  const [frameWidth, setFrameWidth] = useState(0);
  const { doc, page, boxes, size } = state;

  // The page is fitted to the frame, so it is drawn again when the dialog is resized, once the
  // resizing has settled.
  useLayoutEffect(() => {
    const frame = frameRef.current;
    if (frame === null) {
      return undefined;
    }
    setFrameWidth(frame.clientWidth);
    let settle: number | undefined;
    const observer = new ResizeObserver(() => {
      window.clearTimeout(settle);
      settle = window.setTimeout(() => {
        setFrameWidth(frame.clientWidth);
      }, RESIZE_SETTLE_MS);
    });
    observer.observe(frame);
    return () => {
      window.clearTimeout(settle);
      observer.disconnect();
    };
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    const frame = frameRef.current;
    if (doc === undefined || page === undefined || canvas === null || frame === null || frameWidth <= FRAME_GUTTER) {
      return undefined;
    }
    // A new page starts blank and at its top, so the last page's picture does not linger.
    if (page !== shown.current?.page) {
      releaseCanvas(canvas);
      frame.scrollTop = 0;
    }
    const stop = new AbortController();
    let drawing: Drawing | undefined;
    void (async () => {
      try {
        // The picture is still worth showing when the text cannot be read; only the mark is lost.
        const [pdfPage, rects] = await Promise.all([doc.getPage(page), readMatches(doc, page)]);
        stop.signal.throwIfAborted();
        const viewport = pdfPage.getViewport({ scale: getFitScale(frameWidth, pdfPage) });
        drawing = startDrawing(pdfPage, viewport);
        await drawing.task.promise;
        stop.signal.throwIfAborted();
        const marks = (rects ?? []).map(rect => convertToCss(viewport, rect));
        const previous = shown.current;
        const scrollTop = frame.scrollTop;
        showDrawing(canvas, drawing.canvas);
        // Committed now, so the picture, its size and its marks show up together and the frame
        // can be scrolled over the new size.
        flushSync(() => {
          markDrawn({
            page,
            boxes: marks,
            width: viewport.width,
            height: viewport.height,
            textFailed: rects === undefined,
          });
        });
        shown.current = { page, height: viewport.height };
        if (previous?.page === page) {
          // Redrawn at a new size: keep the same part of the page in view.
          frame.scrollTop = scrollTop * viewport.height / previous.height;
        } else if (marks.length > 0) {
          frame.scrollTop = Math.max(0, marks[0].top + marks[0].height / 2 - frame.clientHeight / 2);
        }
      } catch (error: unknown) {
        // Leaving the page cancels the drawing, which is not an error.
        if (!stop.signal.aborted) {
          console.error("A PDF page could not be drawn", error);
          markDrawFailed(page);
        }
      }
    })();
    return () => {
      stop.abort();
      if (drawing !== undefined) {
        drawing.task.cancel();
        releaseCanvas(drawing.canvas);
      }
    };
  }, [doc, page, frameWidth, readMatches, markDrawn, markDrawFailed]);

  const pageCount = doc?.numPages ?? 0;
  // 0 until the document opens, which keeps both page buttons disabled.
  const shownPage = page ?? 0;
  const highlightColor = theme.alpha((theme.vars ?? theme).palette.warning.main, 0.45);

  return (
    <>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
        <IconButton
          aria-label="Previous page"
          size="large"
          disabled={shownPage <= 1}
          onClick={() => {
            showPage(shownPage - 1);
          }}
        >
          <ChevronLeftIcon />
        </IconButton>
        <Typography variant="caption">
          {page === undefined ? "Page" : `Page ${page} of ${pageCount}`}
        </Typography>
        <IconButton
          aria-label="Next page"
          size="large"
          disabled={shownPage >= pageCount}
          onClick={() => {
            showPage(shownPage + 1);
          }}
        >
          <ChevronRightIcon />
        </IconButton>
      </Box>
      {/* Always rendered, even empty: a screen reader only announces changes to a status it already has. */}
      <Typography variant="caption" role="status" sx={{ display: "block", mb: 1 }}>{getNote(state)}</Typography>
      {state.openError === undefined
        ? null
        : (
          <LoadError
            title="The document could not be opened"
            message={state.openError}
            onRetry={retry}
            sx={{ mb: 1 }}
          />
        )}
      <Box
        ref={frameRef}
        role="region"
        aria-label="Document"
        sx={{
          flex: "1 1 auto",
          minHeight: MIN_FRAME_HEIGHT,
          overflow: "auto",
          // Space for the scroll bar is always kept, so its showing up does not change the width the
          // page was fitted to, and the page stays centred.
          scrollbarGutter: "stable both-edges",
          bgcolor: "background.muted",
          borderRadius: 1,
        }}
      >
        <Box sx={{ position: "relative", width: size.width, height: size.height, mx: "auto" }}>
          <canvas
            ref={canvasRef}
            role="img"
            aria-label={page === undefined ? "Document" : `Document page ${page}`}
            style={{ display: "block", width: size.width, height: size.height }}
          />
          {boxes.map((box, index) => (
            // Plain styles: a styled box would add a CSS class per position. The status line says
            // the passage is marked, so the boxes are hidden from screen readers.
            <div
              key={index}
              aria-hidden
              style={{
                position: "absolute",
                left: box.left,
                top: box.top,
                width: box.width,
                height: box.height,
                backgroundColor: highlightColor,
                pointerEvents: "none",
              }}
            />
          ))}
        </Box>
      </Box>
    </>
  );
}
