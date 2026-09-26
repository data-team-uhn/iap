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
import { useEffect, useRef, useState } from "react";

import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import { Box, DialogContent, IconButton, Typography } from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";

import { loadPdfjs } from "./pdfjsClient";
import {
  findQuoteRects,
  pageSearchOrder,
  parsePdfSource,
  type HighlightRect,
  type PdfTextRun,
} from "./pdfQuoteLocator";

import type { ProvenancePassage } from "./provenance";

interface PdfViewerProps {
  passage: ProvenancePassage;
  onClose: () => void;
}

interface CssBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

interface PdfViewport {
  width: number;
  height: number;
  convertToViewportRectangle: (rect: number[]) => number[];
}

interface PdfPage {
  getViewport: (params: { scale: number }) => PdfViewport;
  getTextContent: () => Promise<{ items: readonly unknown[] }>;
  render: (params: { canvasContext: CanvasRenderingContext2D; viewport: PdfViewport }) => {
    promise: Promise<void>;
    cancel: () => void;
  };
}

interface PdfDocument {
  numPages: number;
  getPage: (page: number) => Promise<PdfPage>;
  destroy: () => Promise<void>;
}

// One glyph run as PDF.js reports it. Marked-content items have no string and are not text.
function isTextItem(item: unknown): item is {
  str: string;
  transform: number[];
  width: number;
  height: number;
  hasEOL: boolean;
} {
  if (typeof item !== "object" || item === null || !("str" in item)) {
    return false;
  }
  return typeof item.str === "string";
}

function runsFrom(items: readonly unknown[]): PdfTextRun[] {
  const runs: PdfTextRun[] = [];
  for (const item of items) {
    if (!isTextItem(item) || item.str.length === 0) {
      continue;
    }
    const [,, skewX = 0, scaleY = 0, x = 0, y = 0] = item.transform;
    const height = item.height > 0 ? item.height : Math.hypot(skewX, scaleY);
    runs.push({ str: item.str, x, y, width: item.width, height, hasEOL: item.hasEOL });
  }
  return runs;
}

function fitScale(frame: HTMLElement | null, page: PdfPage): number {
  const unscaled = page.getViewport({ scale: 1 });
  const available = frame !== null && frame.clientWidth > 40 ? frame.clientWidth - 16 : 860;
  if (unscaled.width <= 0) {
    return 1;
  }
  return Math.min(2, available / unscaled.width);
}

function toCss(viewport: PdfViewport, rect: HighlightRect): CssBox {
  const [x1, y1, x2, y2] = viewport.convertToViewportRectangle([
    rect.x, rect.y, rect.x + rect.width, rect.y + rect.height,
  ]);
  return {
    left: Math.min(x1, x2),
    top: Math.min(y1, y2),
    width: Math.abs(x2 - x1),
    height: Math.abs(y2 - y1),
  };
}

async function readRuns(doc: PdfDocument, pageNumber: number): Promise<PdfTextRun[]> {
  const page = await doc.getPage(pageNumber);
  const content = await page.getTextContent();
  return runsFrom(content.items);
}

// Opens the cited PDF on the page the quote came from, and highlights the quote when the PDF
// text contains it. The page still opens when the quote cannot be found.
export default function PdfViewer({ passage, onClose }: PdfViewerProps) {
  const frameRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const highlightRef = useRef<HTMLDivElement>(null);
  const pdfRef = useRef<PdfDocument | undefined>(undefined);
  const renderRef = useRef<{ cancel: () => void } | undefined>(undefined);
  const followSearch = useRef<boolean>(true);

  const [page, setPage] = useState<number | undefined>(undefined);
  const [pageCount, setPageCount] = useState(0);
  const [docVersion, setDocVersion] = useState(0);
  const [boxes, setBoxes] = useState<CssBox[]>([]);
  const [canvasSize, setCanvasSize] = useState({ width: 0, height: 0 });
  const [failed, setFailed] = useState(false);
  const [searched, setSearched] = useState(false);
  const [located, setLocated] = useState<number | undefined>(undefined);
  const [userPaged, setUserPaged] = useState(false);

  const source = passage.source;

  useEffect(() => {
    if (source === undefined) {
      return undefined;
    }
    let cancelled = false;
    let task: { destroy: () => void } | undefined;
    const { url, page: preferred } = parsePdfSource(source);
    followSearch.current = true;

    void loadPdfjs()
      .then(pdfjs => {
        if (cancelled) {
          return undefined;
        }
        const loading = pdfjs.getDocument({ url, withCredentials: true });
        task = loading;
        return loading.promise;
      })
      .then(loaded => {
        if (cancelled || loaded === undefined) {
          return;
        }
        const doc = loaded as PdfDocument;
        pdfRef.current = doc;
        setPageCount(doc.numPages);
        setPage(preferred !== undefined && preferred <= doc.numPages ? preferred : 1);
        setDocVersion(version => version + 1);
      })
      .catch(() => {
        if (!cancelled) {
          setFailed(true);
        }
      });

    return () => {
      cancelled = true;
      renderRef.current?.cancel();
      task?.destroy();
      const doc = pdfRef.current;
      pdfRef.current = undefined;
      if (doc !== undefined) {
        void doc.destroy().catch(() => undefined);
      }
    };
  }, [source]);

  useEffect(() => {
    const doc = pdfRef.current;
    if (doc === undefined || source === undefined) {
      return undefined;
    }
    const lifetime = { cancelled: false };
    const stopped = () => lifetime.cancelled || !followSearch.current;
    const preferred = parsePdfSource(source).page;
    void (async () => {
      for (const candidate of pageSearchOrder(doc.numPages, preferred)) {
        if (stopped()) {
          return;
        }
        const runs = await readRuns(doc, candidate);
        if (stopped()) {
          return;
        }
        if (findQuoteRects(runs, passage.quote).length > 0) {
          setLocated(candidate);
          setPage(candidate);
          setSearched(true);
          return;
        }
      }
      if (!stopped()) {
        setLocated(undefined);
        setSearched(true);
      }
    })();
    return () => {
      lifetime.cancelled = true;
    };
  }, [docVersion, passage.quote, source]);

  useEffect(() => {
    const doc = pdfRef.current;
    if (doc === undefined || page === undefined) {
      return undefined;
    }
    const lifetime = { cancelled: false };
    const stopped = () => lifetime.cancelled;
    void (async () => {
      const pdfPage = await doc.getPage(page);
      if (stopped()) {
        return;
      }
      const viewport = pdfPage.getViewport({ scale: fitScale(frameRef.current, pdfPage) });
      const runs = runsFrom((await pdfPage.getTextContent()).items);
      if (stopped()) {
        return;
      }
      setBoxes(findQuoteRects(runs, passage.quote).map(rect => toCss(viewport, rect)));
      setCanvasSize({ width: viewport.width, height: viewport.height });
      const canvas = canvasRef.current;
      const context = canvas?.getContext("2d") ?? null;
      if (canvas === null || context === null) {
        return;
      }
      canvas.width = viewport.width;
      canvas.height = viewport.height;
      const rendering = pdfPage.render({ canvasContext: context, viewport });
      renderRef.current = rendering;
      try {
        await rendering.promise;
      } catch {
        // Leaving the page cancels the render, and PDF.js rejects that on purpose.
      }
    })();
    return () => {
      lifetime.cancelled = true;
      renderRef.current?.cancel();
    };
  }, [page, docVersion, passage.quote]);

  useEffect(() => {
    const mark = highlightRef.current;
    if (mark !== null && typeof mark.scrollIntoView === "function") {
      mark.scrollIntoView({ block: "center" });
    }
  }, [boxes]);

  function showPage(next: number) {
    followSearch.current = false;
    setUserPaged(true);
    setBoxes([]);
    setPage(next);
  }

  const note = failed
    ? "The protocol could not be opened."
    : page === undefined
      ? "Opening the protocol…"
      : !searched && !userPaged
        ? "Looking for the passage…"
        : boxes.length > 0 || located === page
          ? undefined
          : located === undefined
            ? "The passage is not in the PDF text, so nothing is marked."
            : "The passage is not on this page.";

  return (
    <ResponsiveDialog
      open
      title={passage.cite ?? "Protocol"}
      width="lg"
      withCloseButton
      onClose={onClose}
    >
      <DialogContent>
        <Typography sx={{ fontSize: "0.9rem", mb: 1 }}>{`“${passage.quote}”`}</Typography>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1 }}>
          <IconButton
            aria-label="Previous page"
            size="small"
            disabled={page === undefined || page <= 1}
            onClick={() => page !== undefined && showPage(page - 1)}
          >
            <ChevronLeftIcon />
          </IconButton>
          <Typography sx={{ fontSize: "0.8rem" }}>
            {page === undefined ? "Page" : `Page ${page} of ${pageCount}`}
          </Typography>
          <IconButton
            aria-label="Next page"
            size="small"
            disabled={page === undefined || page >= pageCount}
            onClick={() => page !== undefined && showPage(page + 1)}
          >
            <ChevronRightIcon />
          </IconButton>
        </Box>
        {note === undefined
          ? null
          : <Typography sx={{ fontSize: "0.8rem", mb: 1, color: "text.secondary" }}>{note}</Typography>}
        <Box
          ref={frameRef}
          sx={{ overflow: "auto", maxHeight: "70vh", bgcolor: "grey.100", borderRadius: 1 }}
        >
          <Box sx={{ position: "relative", width: canvasSize.width, height: canvasSize.height, mx: "auto" }}>
            <canvas ref={canvasRef} aria-label={page === undefined ? "Protocol" : `Protocol page ${page}`} />
            {boxes.map((box, index) => (
              <Box
                key={`${box.left}-${box.top}-${index}`}
                ref={index === 0 ? highlightRef : undefined}
                data-testid="citation-highlight"
                aria-hidden
                sx={{
                  position: "absolute",
                  left: box.left,
                  top: box.top,
                  width: box.width,
                  height: box.height,
                  bgcolor: "rgba(255, 214, 0, 0.45)",
                  pointerEvents: "none",
                }}
              />
            ))}
          </Box>
        </Box>
      </DialogContent>
    </ResponsiveDialog>
  );
}
