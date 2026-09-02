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

import { useCatalogue } from "../useCatalogue";
import { useSelection } from "../useSelection";
import { CountPill, WideScreenCode } from "./badges";
import FieldRow from "./FieldRow";
import GroupRow from "./GroupRow";

import type { VisibleCollection } from "../useCatalogueFilter";

interface CollectionNodeProps {
  collection: VisibleCollection;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Whether a search is narrowing the fields below. */
  searching: boolean;
}

/** One collection, and the fields under it. */
export default function CollectionNode({ collection, open, onOpenChange, searching }: CollectionNodeProps) {
  const { display } = useCatalogue();
  const { selected, toggleField, readOnly } = useSelection();
  const visibleKeys = useMemo(() => collection.fields.map(field => field.key), [ collection.fields ]);

  return (
    <GroupRow
      label={collection.label}
      kind="collection"
      keys={visibleKeys}
      open={open}
      onOpenChange={onOpenChange}
      details={(
        <>
          {display.tree.collection.showIdentifier && <WideScreenCode name={collection.identifier} />}
          <CountPill
            label={searching
              ? `${String(collection.fields.length)} of ${String(collection.totalFieldCount)}`
              : String(collection.totalFieldCount)}
          />
        </>
      )}
    >
      {collection.fields.map(field => (
        <FieldRow
          key={field.key}
          field={field}
          selected={selected.has(field.key)}
          display={display.tree.field}
          onToggle={toggleField}
          readOnly={readOnly}
        />
      ))}
    </GroupRow>
  );
}
