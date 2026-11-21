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

import { createContext, useContext } from "react";

import { Box } from "@mui/material";

import { occurrences, type FormMatch } from "./searchSubmission";

// PRETOTYPE. What the submission search is looking for, and the occurrence it is on, for the text it covers to
// mark them where it shows them
export const SearchContext = createContext<{ terms: string; active?: FormMatch }>({ terms: "" });

// A text with the search's occurrences marked in it, as a document marks them: the one the search is on
// stronger, and kept for the page to bring into view
export function Highlighted({ text, question, field }: { text: string; question: string; field: FormMatch["field"] }) {
  const { terms, active } = useContext(SearchContext);
  const found = occurrences(text, terms);
  if (found.length === 0) {
    return text;
  }
  const parts = [];
  let from = 0;
  found.forEach((at, nth) => {
    const current = active?.question === question && active.field === field && active.nth === nth;
    parts.push(text.slice(from, at));
    parts.push(
      <Box
        component="mark" key={at} data-search-current={current ? "" : undefined}
        sx={theme => ({ color: "inherit", borderRadius: 0.5,
          bgcolor: current ? theme.palette.highlight.searchCurrent : theme.palette.highlight.search })}
      >
        {text.slice(at, at + terms.length)}
      </Box>,
    );
    from = at + terms.length;
  });
  parts.push(text.slice(from));
  return <>{parts}</>;
}
