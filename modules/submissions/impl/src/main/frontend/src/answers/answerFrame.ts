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

import type { SxProps, Theme } from "@mui/material";

// The frame around an answer the model drafted and nobody has confirmed yet. Everything else gets
// an invisible border of the same width, so confirming an answer does not shift the page.
const AI_BORDER = "2px dashed rgb(107, 79, 187)";
const AI_BACKGROUND = "rgb(247, 244, 253)";
const PLAIN_BORDER = "2px solid transparent";

// For one picked option, or a tick box: framed when it is the model's pick still to confirm.
export function getOptionFrame(framed: boolean) {
  return {
    border: framed ? AI_BORDER : PLAIN_BORDER,
    background: framed ? AI_BACKGROUND : "none",
    borderRadius: 1,
    ml: 0,
    mr: 0,
    pr: 1.5,
  };
}

// For a typed-in answer: the input's own outline becomes the frame.
export function getInputFrame(framed: boolean): SxProps<Theme> {
  return framed
    ? {
      "& .MuiOutlinedInput-root": { background: AI_BACKGROUND },
      "& .MuiOutlinedInput-notchedOutline, & .MuiOutlinedInput-root:hover .MuiOutlinedInput-notchedOutline":
        { border: AI_BORDER },
    }
    : {};
}
