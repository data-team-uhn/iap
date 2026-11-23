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
// The passages the viewer lists and steps through: their order and names, the one the card shows, and what
// moves the selection from one to another.
import { useCallback, useMemo, useState } from "react";

import { useSwipe } from "@iap/frontend-commons/useSwipe";

import { orderMarks } from "./marksModel";
import { CHANGES, nameMarks, type MarkName } from "./pdfViewerModel";

import type { Mark } from "./marksModel";
import type { MarkPlaces } from "./useMarkPlaces";

// Returns the listed marks in order, each with a name and its number among the marks of its sort. It also
// returns the mark on the card above the document. Changes are called changes, and the caller's marks are
// called by `markName`. Without `changesListed`, the changes are left out, as when the versions are side by
// side and the comparison's own controls step through them.
export function usePassages({ marks, places, changeIds, changesListed, markName, selectedId, onSelect, showMark }: {
  marks: Mark[];
  places: MarkPlaces;
  // The ids of the marks that are changes.
  changeIds: Set<string>;
  changesListed: boolean;
  markName: MarkName;
  selectedId: string | undefined;
  onSelect: (id: string) => void;
  // Takes the reader to a mark.
  showMark: (id: string) => void;
}) {
  const ordered = useMemo(() => orderMarks(marks, places), [marks, places]);
  const listed = useMemo(
    () => (changesListed ? ordered : ordered.filter(mark => !changeIds.has(mark.id))),
    [ordered, changeIds, changesListed],
  );
  const naming = useMemo(
    () => nameMarks(listed, mark => (changeIds.has(mark.id) ? CHANGES : markName)),
    [listed, changeIds, markName],
  );
  const selectedAt = listed.findIndex(mark => mark.id === selectedId);
  const selected = selectedAt < 0 ? undefined : listed[selectedAt];
  // When the selected mark is not listed, such as a change while the versions are side by side, the card
  // keeps the last listed mark that was selected.
  const [cardId, setCardId] = useState<string | undefined>(undefined);
  if (selected !== undefined && selected.id !== cardId) {
    setCardId(selected.id);
  }
  const cardAt = selected === undefined ? Math.max(0, listed.findIndex(mark => mark.id === cardId)) : selectedAt;
  // Selects a mark and takes the reader to it.
  const showSelected = useCallback((id: string) => {
    onSelect(id);
    showMark(id);
  }, [onSelect, showMark]);
  const stepTo = (index: number) => showSelected(listed[index].id);
  // Swiping the card steps through the marks listed, stopping at either end.
  const swipeRef = useSwipe(
    cardAt > 0 ? () => stepTo(cardAt - 1) : undefined,
    cardAt < listed.length - 1 ? () => stepTo(cardAt + 1) : undefined,
  );

  return {
    listed,
    naming,
    // How many sorts of mark are listed, such as comments and changes.
    sorts: new Set(Array.from(naming.values(), each => each.name)).size,
    selected,
    selectedNaming: selected === undefined ? undefined : naming.get(selected.id),
    cardAt,
    onCard: listed.at(cardAt),
    several: listed.length > 1,
    showSelected,
    stepTo,
    swipeRef,
  };
}
