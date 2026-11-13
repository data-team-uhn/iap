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
import {
  countMinorChanges,
  getChangeType,
  getShownChanges,
  type ComparisonState,
  type RevisionChange,
} from "./revisionDiffModel";

// What the comparison of two versions says to the people using it, on screen and to a screen reader.

// How much of a change's text a screen reader hears before it is cut short.
const SAID_LENGTH = 120;

function quote(text: string): string {
  return `“${text.length > SAID_LENGTH ? `${text.slice(0, SAID_LENGTH).trimEnd()}…` : text}”`;
}

// What a screen reader hears for a change, such as "Change 3 of 17. Text removed on page 4. It said “…”.".
// Without `among`, the first sentence is left out. The page is the newer version's.
export function describeChange(change: RevisionChange, among?: { index: number; count: number }): string {
  const { page } = change.newer[0];
  const which = among === undefined ? "" : `Change ${among.index} of ${among.count}.`;
  switch (getChangeType(change)) {
    case "added":
      return `${which} Text added on page ${page}. It says ${quote(change.added)}.`.trim();
    case "removed":
      return `${which} Text removed on page ${page}. It said ${quote(change.removed)}.`.trim();
    case "replaced":
      return `${which} Text replaced on page ${page}. ${quote(change.removed)} became ${quote(change.added)}.`.trim();
  }
}

// What to say about a comparison: how many changes it shows, once there are some; a caption for beside the
// controls, with its progress, why there is nothing to show, or the minor changes left out or included; and
// what a screen reader hears, which is the caption with the count.
export function describeComparison(
  state: ComparisonState,
  strict: boolean,
): { count?: number; caption: string; said: string } {
  switch (state.status) {
    case "reading": {
      const caption = state.total > 0 ? `Comparing… page ${state.read} of ${state.total}` : "Comparing…";
      return { caption, said: "Comparing the versions…" };
    }
    case "comparing":
      return { caption: "Comparing…", said: "Comparing the versions…" };
    case "failed": {
      const caption = "These versions could not be compared.";
      return { caption, said: caption };
    }
    case "noText": {
      const caption = {
        older: "The older version has no text to compare. It may be a scan.",
        newer: "The newer version has no text to compare. It may be a scan.",
        both: "Neither version has text to compare. They may be scans.",
      }[state.without];
      return { caption, said: caption };
    }
    case "tooDifferent": {
      const caption = "These versions differ too much to show their changes.";
      return { caption, said: caption };
    }
    case "compared": {
      const { length } = getShownChanges(state.changes, strict);
      const caption = [
        length === 0 ? "No changes found." : "",
        describeMinor(countMinorChanges(state.changes), strict),
      ].filter(part => part !== "").join(" ");
      const said = length === 0 ? caption : [ `${countChanges(length)} found.`, caption ].join(" ").trim();
      return { count: length === 0 ? undefined : length, caption, said };
    }
  }
}

// A number of changes, as in "7 changes".
export function countChanges(count: number): string {
  return `${count} ${count === 1 ? "change" : "changes"}`;
}

// The caption about minor changes, left out or included. Empty when there are none.
export function describeMinor(minor: number, strict: boolean): string {
  if (minor === 0) {
    return "";
  }
  const changes = `${minor} minor ${minor === 1 ? "change" : "changes"} of punctuation or letter case`;
  return strict ? `Including ${changes}.` : `${changes} left out.`;
}
