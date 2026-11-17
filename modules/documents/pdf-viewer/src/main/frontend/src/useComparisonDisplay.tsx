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
// What a comparison with an older version adds to what the viewer shows. It says whether the versions are
// side by side and how a change reads on its card and to a screen reader. It also gives the comparison's
// controls, caption and progress.
import { useMemo } from "react";

import ValueChange from "@iap/frontend-commons/diff/ValueChange";

import { describeChange, describeComparison } from "./comparisonText";
import { countMinorChanges, getChangeInContext, type RevisionChange } from "./revisionDiffModel";

import type { Mark } from "./marksModel";
import type { Comparing } from "./useComparison";
import type { PdfView } from "./usePdfView";

// A comparison with an older version, the view showing that version, and the labels above each version.
export type ComparisonPanes = Comparing & {
  olderView: PdfView;
  olderLabel?: string;
  newerLabel?: string;
};

// How far along reading or comparing is, as a share from 0 to 1, and what is being looked for.
export interface Progress {
  share: number;
  what: string;
}

// How far a comparison that is still reading or comparing has got. Until both versions are open, its pages are
// not counted yet, and nothing is read.
function getComparedShare(state: Comparing["state"]): number {
  if (state.status !== "reading") {
    return 1;
  }
  return state.total > 0 ? state.read / state.total : 0;
}

// A change among the words around it, with what it removed struck through.
function ChangeInContext({ change }: { change: RevisionChange }) {
  const { before, removed, added, after } = getChangeInContext(change);
  return (
    <>
      {before === "" ? null : `${before} `}
      <ValueChange inline before={removed === "" ? undefined : removed} after={added === "" ? undefined : added} />
      {after === "" ? null : ` ${after}`}
    </>
  );
}

// The comparison's part in what the viewer shows. Without a comparison, nothing is side by side, no mark has a
// body of its own, and there are no controls.
export function useComparisonDisplay(comparison: ComparisonPanes | undefined, { narrow, selectedId, openError }: {
  narrow: boolean;
  selectedId: string | undefined;
  // Why this version could not be opened, if it could not.
  openError: string | undefined;
}) {
  // A narrow viewer shows this version alone, but keeps the choice of side by side for when it is wider.
  const split = comparison !== undefined && !narrow && comparison.view === "split";
  const changes = comparison?.changes;
  const olderOpenError = comparison?.older.openError;
  // Nothing is compared while changes are off, or while either version could not be opened. A version that
  // could not be opened shows its own error.
  const comparing = comparison !== undefined && comparison.view !== "off";
  const state = comparing && openError === undefined && olderOpenError === undefined ? comparison.state : undefined;
  const described = state === undefined || comparison === undefined
    ? undefined
    : describeComparison(state, comparison.strict);
  const changeIndex = changes?.findIndex(change => change.id === selectedId) ?? -1;
  const selectedChange = changeIndex < 0 ? undefined : changes?.[changeIndex];
  // A change is shown in place of the text it marks, as what it removed and added among the words around it.
  const renderBody = useMemo(() => {
    const byId = new Map(changes?.map(change => [ change.id, change ]));
    return function renderChange(mark: Mark) {
      const change = byId.get(mark.id);
      return change === undefined ? undefined : <ChangeInContext change={change} />;
    };
  }, [changes]);
  // What a screen reader is told of each change, as a sentence.
  const describeBody = useMemo(() => {
    const said = new Map(changes?.map(change => [ change.id, describeChange(change) ]));
    return (mark: Mark) => said.get(mark.id);
  }, [changes]);
  const progress: Progress | undefined = state?.status === "reading" || state?.status === "comparing"
    ? { share: getComparedShare(state), what: "the changes" }
    : undefined;
  // When there are changes, the caption only speaks of the minor changes left out or included. A narrow viewer
  // leaves that to the changes menu.
  const caption = narrow && described?.count !== undefined ? "" : described?.caption ?? "";
  const controls = comparison === undefined
    ? undefined
    : {
      view: comparison.view,
      shownView: narrow && comparison.view === "split" ? "unified" as const : comparison.view,
      onViewChange: comparison.setView,
      strict: comparison.strict,
      onStrictChange: comparison.setStrict,
      minor: state?.status === "compared" ? countMinorChanges(state.changes) : undefined,
      count: described?.count,
      narrow,
      changes: split ? changes : undefined,
      selectedIndex: changeIndex,
      caption,
    };

  return {
    shown: comparison !== undefined,
    split,
    renderBody,
    describeBody,
    // What a screen reader hears of the selected change, numbered among the changes.
    selectedChangeSaid: selectedChange === undefined || changes === undefined
      ? undefined
      : describeChange(selectedChange, { index: changeIndex + 1, count: changes.length }),
    // What the comparison found, once it is done.
    found: state !== undefined && state.status !== "reading" && state.status !== "comparing" ? described : undefined,
    described,
    progress,
    controls,
    older: comparison === undefined
      ? undefined
      : {
        view: comparison.olderView,
        label: comparison.olderLabel,
        error: olderOpenError,
        retry: comparison.older.retry,
      },
    newerLabel: comparison?.newerLabel,
  };
}
