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
// Compares the document shown with an older version, and keeps the person's choices of how to see the
// changes.
import { useMemo } from "react";

import { useStoredSetting } from "@iap/frontend-commons/useStoredSetting";

import { getChangeMarks, getShownChanges, type RevisionChange } from "./revisionDiffModel";
import { usePdfDocument, type OpenedDocument } from "./usePdfDocument";
import { useRevisionDiff } from "./useRevisionDiff";

// How changes are shown: not at all, on the newer version, or with the versions side by side.
export type ComparisonView = "off" | "unified" | "split";

const isView = (value: unknown): value is ComparisonView => value === "off" || value === "unified" || value === "split";

const isBoolean = (value: unknown): value is boolean => typeof value === "boolean";

// How the person chose to see changes, on the newer version by default. Kept across documents and visits.
export function useComparisonView() {
  return useStoredSetting<ComparisonView>("iap.pdfComparison.view", "unified", isView);
}

// Whether the person chose to include minor changes, of punctuation or letter case. Off by default, and kept
// across documents and visits.
export function useStrictComparison() {
  return useStoredSetting<boolean>("iap.pdfComparison.strict", false, isBoolean);
}

// One empty list for every render, so the memos depending on it keep their value.
const NO_CHANGES: RevisionChange[] = [];

// Compares a document with an older version of it, given by its URL. Returns the older version opened, how
// the comparison goes, the changes shown, and the marks showing them on each version. It also returns the
// person's choices of view and of strictness, with their setters. Undefined without an older version. While
// the view is off, the older version is not opened and nothing is compared.
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
