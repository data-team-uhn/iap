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

import type { ReactNode } from "react";

import { getShownChanges } from "@iap/documents-pdf-viewer/revisionDiffModel";
import { usePdfDocument } from "@iap/documents-pdf-viewer/usePdfDocument";
import { useRevisionDiff } from "@iap/documents-pdf-viewer/useRevisionDiff";
import { useStoredSetting } from "@iap/frontend-commons/useStoredSetting";

const isBoolean = (value: unknown): value is boolean => typeof value === "boolean";

// PRETOTYPE. How many changes a document has since an older version, as its viewer would show them, counting minor
// ones only where the reader chose to see them there; worked out in the background, both versions opened and compared
// as the viewer does. Where the viewer showing it says how many, that is taken instead, keeping up with its choices.
export default function DocumentChangeCount({ older, newer, shown, children }: {
  older: string;
  newer: string;
  // What the viewer showing the document counts, while it does
  shown?: number;
  children: (count: number | undefined) => ReactNode;
}) {
  const [ strict ] = useStoredSetting("iap.pdfComparison.strict", false, isBoolean);
  const olderPdf = usePdfDocument(shown === undefined ? older : undefined);
  const newerPdf = usePdfDocument(shown === undefined ? newer : undefined);
  const state = useRevisionDiff(olderPdf.pdf, newerPdf.pdf);
  const counted = state.status === "compared" ? getShownChanges(state.changes, strict).length : undefined;
  return children(shown ?? counted);
}
