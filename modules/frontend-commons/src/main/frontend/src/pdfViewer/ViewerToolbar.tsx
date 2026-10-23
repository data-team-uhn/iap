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
import { useEffect, useImperativeHandle, useRef, useState, type ReactNode, type Ref } from "react";

import SearchIcon from "@mui/icons-material/Search";
import { Box, IconButton } from "@mui/material";

import PdfToolbar from "./PdfToolbar";
import SearchField from "./SearchField";
import { useNarrowerThan } from "./useNarrowViewer";

import type { DocumentSearch } from "./pdfViewerModel";

// The narrowest toolbar with room for the search field beside its page and zoom controls.
const SEARCH_FIELD_ROOM = 600;

export interface ViewerToolbarHandle {
  // Puts the focus in the search field, opening it where it takes the toolbar's place.
  openSearch: () => void;
}

// The viewer's toolbar: the page controls given, the search, and zoom. Without room for the search field,
// a button opens it in the toolbar's place. It stays there while it is open or holds a query. It takes the
// focus, and gives it back to the button once closed.
export default function ViewerToolbar({ ref, pages, opened, search, onFind, onFindAgain, ...zoom }: {
  ref?: Ref<ViewerToolbarHandle>;
  pages?: ReactNode;
  opened: boolean;
  search: DocumentSearch;
  onFind: (query: string) => void;
  onFindAgain: (previous: boolean) => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitWidth: () => void;
}) {
  const [row, setRow] = useState<HTMLElement | null>(null);
  const compact = useNarrowerThan(row, SEARCH_FIELD_ROOM);
  const [searchOpen, setSearchOpen] = useState(false);
  const searchInPlace = compact && (searchOpen || search.query !== "");
  const searchButtonRef = useRef<HTMLButtonElement>(null);
  const closingSearch = useRef(false);
  const searchInputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (searchInPlace) {
      searchInputRef.current?.focus();
      searchInputRef.current?.select();
    } else if (closingSearch.current) {
      closingSearch.current = false;
      searchButtonRef.current?.focus();
    }
  }, [searchInPlace]);
  useImperativeHandle(ref, () => ({
    openSearch: () => {
      setSearchOpen(compact);
      searchInputRef.current?.focus();
      searchInputRef.current?.select();
    },
  }), [compact]);
  const closeSearch = () => {
    closingSearch.current = true;
    onFind("");
    setSearchOpen(false);
  };

  return (
    <Box ref={setRow}>
      {searchInPlace
        ? (
          <Box sx={{ display: "flex", alignItems: "center" }}>
            <SearchField
              search={search}
              inputRef={searchInputRef}
              onFind={onFind}
              onFindAgain={onFindAgain}
              onClose={closeSearch}
            />
          </Box>
        )
        : (
          <PdfToolbar
            pages={pages}
            opened={opened}
            search={compact
              ? (
                <IconButton
                  ref={searchButtonRef}
                  aria-label="Search the document"
                  disabled={!opened}
                  onClick={() => setSearchOpen(true)}
                >
                  <SearchIcon />
                </IconButton>
              )
              : (
                <SearchField
                  search={search}
                  inputRef={searchInputRef}
                  disabled={!opened}
                  onFind={onFind}
                  onFindAgain={onFindAgain}
                />
              )}
            {...zoom}
          />
        )}
    </Box>
  );
}
