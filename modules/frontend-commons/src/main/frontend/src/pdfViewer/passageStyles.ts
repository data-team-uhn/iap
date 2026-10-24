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
import { fadeEdges, type ScrollEdges } from "../useScrollEdges";
import { isChangeKind, type MarkKind } from "./marksModel";

import type { Theme } from "@mui/material/styles";

// Styles that cut text short past the given number of lines.
export function cutShort(lines: number) {
  return { display: "-webkit-box", WebkitBoxOrient: "vertical", WebkitLineClamp: lines, overflow: "hidden" } as const;
}

// The colour of a kind of mark. A passage's is its palette colour, in the shade asked for. A change, whatever
// it added or removed, is neutral: its lines carry the colour.
export function getMarkColour(theme: Theme, kind: MarkKind, shade: "main" | "light" = "main"): string {
  const { palette } = theme.vars ?? theme;
  return isChangeKind(kind) ? palette.divider : palette[kind][shade];
}

// The background of a passage's card, a tint of its mark's colour. A stronger tint shows the card
// selected or under the pointer. A change's card is untinted, and greys as a list item does.
export function passageTint(theme: Theme, kind: MarkKind, strength: number): string {
  if (isChangeKind(kind)) {
    return strength > 0.25 ? (theme.vars ?? theme).palette.action.hover : "transparent";
  }
  return theme.alpha(getMarkColour(theme, kind, "light"), strength);
}

// The card a passage is on: a stripe of its mark's colour at its start, over a tint of it. A change's card
// is also outlined, as it has no tint to set it apart.
export function passageCard(theme: Theme, kind: MarkKind, strength = 0.25) {
  return {
    borderRadius: 1,
    ...isChangeKind(kind) ? { border: "1px solid" } : {},
    borderInlineStart: `${theme.spacing(0.5)} solid`,
    borderColor: getMarkColour(theme, kind),
    bgcolor: passageTint(theme, kind, strength),
  };
}

// The background of a passage's own text, a shade deeper in its mark's colour than its card, setting it
// apart from what the caller shows under it.
export function passageLayer(theme: Theme, kind: MarkKind) {
  return { bgcolor: passageTint(theme, kind, 0.25) };
}

// What the caller shows under a passage, such as comments, cut short past `lines` lines when given, and
// faded out where it is cut. The room above keeps a field's label, which reaches up out of the field, from
// being cut off. Hidden when empty.
export function passageDetails(edges: ScrollEdges, lines?: number) {
  return {
    pt: 1,
    "&:empty": { display: "none" },
    maxHeight: lines === undefined ? undefined : `${lines}lh`,
    overflowY: lines === undefined ? undefined : "hidden" as const,
    maskImage: fadeEdges(edges),
  };
}

// The error colour, in the shade that stays readable as small text in each scheme, on a passage's card or
// beside a search
export function failedColour(theme: Theme) {
  const { error } = (theme.vars ?? theme).palette;
  return { color: error.dark, ...theme.applyStyles("dark", { color: `color-mix(in srgb, ${error.main}, white)` }) };
}
