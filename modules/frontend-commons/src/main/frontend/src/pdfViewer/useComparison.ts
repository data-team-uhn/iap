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
import { useMemo } from "react";

import { getChangeMarks, getShownChanges, type RevisionChange } from "./revisionDiffModel";
import { usePdfDocument, type OpenedDocument } from "./usePdfDocument";
import { useRevisionDiff } from "./useRevisionDiff";
import { useStoredSetting } from "../useStoredSetting";

export type ComparisonView = "off" | "unified" | "split";

const isView = (value: unknown): value is ComparisonView => value === "off" || value === "unified" || value === "split";

const isBoolean = (value: unknown): value is boolean => typeof value === "boolean";

// How the person chose to see changes: not at all, on the newer version, the default, or side by side.
// Kept across documents and visits.
export function useComparisonView() {
  return useStoredSetting<ComparisonView>("iap.pdfComparison.view", "unified", isView);
}

// Whether the person chose to include minor changes, of case and punctuation, across documents and visits.
export function useStrictComparison() {
  return useStoredSetting<boolean>("iap.pdfComparison.strict", false, isBoolean);
}

const NO_CHANGES: RevisionChange[] = [];

// A document compared with an older version of it, given by its URL: the older version opened, how the
// comparison goes, the changes found and the marks each version shows them with, and the person's choices
// of view and of strictness. Nothing without an older version. While changes are not shown, the older
// version is not opened, nor anything compared.
export function useComparison(olderUrl: string | undefined, newer: OpenedDocument | undefined) {
  const [strict, setStrict] = useStrictComparison();
  const [view, setView] = useComparisonView();
  const older = usePdfDocument(view === "off" ? undefined : olderUrl);
  const state = useRevisionDiff(older.pdf, newer);
  const found = state.status === "compared" ? state.changes : NO_CHANGES;
  const changes = useMemo(() => getShownChanges(found, strict), [found, strict]);
  const olderMarks = useMemo(() => getChangeMarks(changes, "older"), [changes]);
  const newerMarks = useMemo(() => getChangeMarks(changes, "newer"), [changes]);
  if (olderUrl === undefined) {
    return undefined;
  }
  return { older, state, changes, olderMarks, newerMarks, strict, setStrict, view, setView };
}

export type Comparing = NonNullable<ReturnType<typeof useComparison>>;
