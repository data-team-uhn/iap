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

import { Box } from "@mui/material";

import { type TextLine } from "./contentDiffModel";

// A text compared with another, as compareText gives it: changed lines tinted, with a mark as well as a colour, and the
// words that changed within them in a stronger shade. A changed line is an insertion or a deletion, for assistive
// technology too.
function TextDiff({ lines }: { lines: TextLine[] }) {
  return (
    <Box sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
      { lines.map((line, at) => {
        if (line.change === "unchanged") {
          // An empty line still takes a line, so that paragraphs stay apart
          return <Box key={at} sx={{ pl: 3 }}>{line.parts.map(part => part.text).join("") || "\u00a0"}</Box>;
        }
        const colors = line.change === "added" ? "diff.added" : "diff.removed";
        return (
          <Box key={at} component={line.change === "added" ? "ins" : "del"}
            sx={{ display: "flex", textDecoration: "none", bgcolor: `${colors}.line` }}>
            <Box component="span" aria-hidden
              sx={{ width: 24, flexShrink: 0, textAlign: "center", color: `${colors}.main` }}>
              { line.change === "added" ? "+" : "−" }
            </Box>
            <span>
              { line.parts.map((part, index) => (part.changed
                ? (
                  <Box key={index} component="span" sx={{ bgcolor: `${colors}.word`, borderRadius: 0.5 }}>
                    {part.text}
                  </Box>
                ) : part.text)) }
            </span>
          </Box>
        );
      }) }
    </Box>
  );
}

export default TextDiff;
