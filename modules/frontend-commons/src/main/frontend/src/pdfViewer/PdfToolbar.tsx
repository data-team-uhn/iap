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
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import FitWidthIcon from "@mui/icons-material/FitScreen";
import ZoomInIcon from "@mui/icons-material/ZoomIn";
import ZoomOutIcon from "@mui/icons-material/ZoomOut";
import { Box, IconButton, Typography } from "@mui/material";

// The page and zoom controls. One line even on a narrow phone: only the page label gives way.
// Every control is disabled until the document has opened.
export default function PdfToolbar({ page, pageCount, onShowPage, onZoomIn, onZoomOut, onFitWidth }: {
  page?: number;
  pageCount?: number;
  onShowPage: (page: number) => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitWidth: () => void;
}) {
  const opened = pageCount !== undefined;
  // 0 until the document opens, which keeps both page buttons disabled.
  const shownPage = page ?? 0;
  const lastPage = pageCount ?? 0;

  return (
    <Box sx={{ display: "flex", alignItems: "center", mb: 1 }}>
      <IconButton
        aria-label="Previous page"
        edge="start"
        disabled={shownPage <= 1}
        onClick={() => onShowPage(shownPage - 1)}
      >
        <ChevronLeftIcon />
      </IconButton>
      <Typography variant="caption" noWrap sx={{ minWidth: 0, mx: 0.5 }}>
        {opened ? `Page ${shownPage} of ${lastPage}` : "Page"}
      </Typography>
      <IconButton aria-label="Next page" disabled={shownPage >= lastPage} onClick={() => onShowPage(shownPage + 1)}>
        <ChevronRightIcon />
      </IconButton>
      <Box sx={{ flexGrow: 1 }} />
      <IconButton aria-label="Zoom out" disabled={!opened} onClick={onZoomOut}>
        <ZoomOutIcon />
      </IconButton>
      <IconButton aria-label="Fit to width" disabled={!opened} onClick={onFitWidth}>
        <FitWidthIcon />
      </IconButton>
      <IconButton aria-label="Zoom in" edge="end" disabled={!opened} onClick={onZoomIn}>
        <ZoomInIcon />
      </IconButton>
    </Box>
  );
}
