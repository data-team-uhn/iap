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
// The page buttons: Previous page and Next page, with which page is shown between them.
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import { IconButton, Typography } from "@mui/material";

// The Previous page and Next page buttons, with "Page 3 of 10" between them. In a narrow row, the label is
// cut short. Both buttons are disabled until the document has opened.
export default function PageControls({ page, pageCount, onShowPage }: {
  page?: number;
  pageCount?: number;
  onShowPage: (page: number) => void;
}) {
  // 0 until the document opens, which keeps both page buttons disabled.
  const shownPage = page ?? 0;
  const lastPage = pageCount ?? 0;

  return (
    <>
      <IconButton
        aria-label="Previous page"
        edge="start"
        disabled={shownPage <= 1}
        onClick={() => onShowPage(shownPage - 1)}
      >
        <ChevronLeftIcon />
      </IconButton>
      <Typography variant="caption" noWrap sx={{ minWidth: 0, mx: 0.5 }}>
        {pageCount === undefined ? "Page" : `Page ${shownPage} of ${lastPage}`}
      </Typography>
      <IconButton aria-label="Next page" disabled={shownPage >= lastPage} onClick={() => onShowPage(shownPage + 1)}>
        <ChevronRightIcon />
      </IconButton>
    </>
  );
}
