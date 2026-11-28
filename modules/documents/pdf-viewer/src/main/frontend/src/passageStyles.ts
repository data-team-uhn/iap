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
// The styles of the cards that show marks, in the colour of each kind of mark, and of the text on them.

import { fadeEdges, type ScrollEdges } from "@iap/frontend-commons/useScrollEdges";

import { isChangeKind, type MarkKind } from "./marksModel";

import type { Theme } from "@mui/material/styles";

// The colour of a kind of mark. A passage's is its palette colour, in the shade asked for. A change's is
// neutral whatever its kind, since the removed and added text it shows carries the colour.
function getMarkColour(theme: Theme, kind: MarkKind, shade: "main" | "light" = "main"): string {
  const { palette } = theme.vars ?? theme;
  return isChangeKind(kind) ? palette.divider : palette[kind][shade];
}

// The outline of a selected card. A passage's is its mark's colour. A change's is the secondary text
// colour, since the change's own neutral colour is too light to show against the paper.
export function getSelectedOutline(theme: Theme, kind: MarkKind): string {
  return isChangeKind(kind) ? (theme.vars ?? theme).palette.text.secondary : getMarkColour(theme, kind);
}

// The tint of a card's background: its mark's light colour at the opacity `strength`. A change's card has
// no tint at a strength of 0.25 or less, and the hover grey above that.
export function getPassageTint(theme: Theme, kind: MarkKind, strength: number): string {
  if (isChangeKind(kind)) {
    return strength > 0.25 ? (theme.vars ?? theme).palette.action.hover : "transparent";
  }
  return theme.alpha(getMarkColour(theme, kind, "light"), strength);
}

// The card a mark is shown on: a tint of the mark's colour over the paper. It has no coloured edge, which
// would read as the card being selected, as the outline of the one selected says. The paper under the tint
// makes the card look the same on any background. A change's card is also outlined, since it has no tint to
// set it apart.
export function getPassageCardStyle(theme: Theme, kind: MarkKind, strength = 0.25) {
  const tint = getPassageTint(theme, kind, strength);
  return {
    borderRadius: 1,
    ...isChangeKind(kind) ? { border: "1px solid" } : {},
    borderColor: getMarkColour(theme, kind),
    bgcolor: "background.paper",
    backgroundImage: `linear-gradient(${tint}, ${tint})`,
  };
}

// The background of the part of a card that shows the passage: a second tint over the card's, which sets
// the passage apart from the details under it.
export function getPassageLayerStyle(theme: Theme, kind: MarkKind) {
  return { bgcolor: getPassageTint(theme, kind, 0.25) };
}

// The style of the details a card shows under the passage, such as comments. With `lines`, they are cut
// short after that many lines. They fade out at each edge that `edges` marks as having more to scroll to.
// The padding on top keeps
// a field's label, which reaches up out of the field, from being cut off. Hidden when empty.
export function getPassageDetailsStyle(edges: ScrollEdges, lines?: number) {
  return {
    pt: 1,
    "&:empty": { display: "none" },
    maxHeight: lines === undefined ? undefined : `${lines}lh`,
    overflowY: lines === undefined ? undefined : "hidden" as const,
    maskImage: fadeEdges(edges),
  };
}

// The error colour, in a shade that stays readable as small text in both the light and the dark scheme.
export function getFailedColour(theme: Theme) {
  const { error } = (theme.vars ?? theme).palette;
  return { color: error.dark, ...theme.applyStyles("dark", { color: `color-mix(in srgb, ${error.main}, white)` }) };
}
