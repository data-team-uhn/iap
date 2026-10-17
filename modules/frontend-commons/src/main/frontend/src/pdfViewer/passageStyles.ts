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
import type { MarkKind } from "./marksModel";
import type { Theme } from "@mui/material/styles";

// Text cut short past a number of lines.
export function cutShort(lines: number) {
  return { display: "-webkit-box", WebkitBoxOrient: "vertical", WebkitLineClamp: lines, overflow: "hidden" } as const;
}

// The tint of a mark's colour a passage's card has, stronger for the one selected or under the pointer.
export function passageTint(theme: Theme, kind: MarkKind, strength: number): string {
  return theme.alpha((theme.vars ?? theme).palette[kind].light, strength);
}

// The card a passage is on: a stripe of its mark's colour at its start, over a tint of it.
export function passageCard(theme: Theme, kind: MarkKind, strength = 0.25) {
  return {
    borderRadius: 1,
    borderInlineStart: `${theme.spacing(0.5)} solid`,
    borderColor: `${kind}.main`,
    bgcolor: passageTint(theme, kind, strength),
  };
}

// The error colour, in the shade that stays readable as small text on a passage's card, or beside a
// search, in each scheme
export function failedColour(theme: Theme) {
  const { error } = (theme.vars ?? theme).palette;
  return { color: error.dark, ...theme.applyStyles("dark", { color: `color-mix(in srgb, ${error.main}, white)` }) };
}
