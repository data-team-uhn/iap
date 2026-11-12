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

import { useEffect, useState } from "react";

import { Autocomplete, TextField } from "@mui/material";

import { useAuthenticatedFetch } from "../reLogin";
import { describeRequestFailure } from "../requestFailure";
import { type ContentField, type ReferenceCandidate } from "./fieldsModel";
import { readCandidates } from "./referenceCandidates";

// The nodes a reference field may point at, sorted by label, read again only when its type or root
// changes. `loadError` says why they could not be read, worded for the person using the UI.
function useCandidates(field: ContentField) {
  const doFetch = useAuthenticatedFetch();
  const [ candidates, setCandidates ] = useState<ReferenceCandidate[]>([]);
  const [ loadError, setLoadError ] = useState<string>();
  const { referenceType, referenceRoot } = field;

  useEffect(() => {
    if (!referenceType) {
      return;
    }
    let current = true;
    readCandidates(doFetch, { referenceType, referenceRoot })
      .then(found => {
        if (current) {
          setCandidates(found);
        }
      })
      .catch((error: unknown) => {
        if (current) {
          setLoadError(describeRequestFailure(error));
        }
      });
    return () => {
      current = false;
    };
  }, [ doFetch, referenceType, referenceRoot ]);

  return { candidates, loadError };
}

interface ReferenceInputProps {
  field: ContentField;
  // The path of the node pointed at, or of each for a field that holds several; empty for none
  value: string | string[];
  disabled: boolean;
  helperText?: string;
  onChange: (value: string | string[]) => void;
}

// Picks the node a reference field points at, among the nodes it may. A field naming no type takes
// any path typed in.
function ReferenceInput({ field, value, disabled, helperText, onChange }: ReferenceInputProps) {
  const { candidates, loadError } = useCandidates(field);
  const chosen = Array.isArray(value) ? value : [ value ].filter(path => path !== "");
  // What is pointed at stays offered when it is no longer a candidate, so that it still shows
  const options = [ ...new Set([ ...candidates.map(candidate => candidate.path), ...chosen ]) ];
  const labelOf = (path: string) => candidates.find(candidate => candidate.path === path)?.label ?? path;

  return (
    <Autocomplete<string, boolean, false, boolean>
      multiple={field.multiple}
      freeSolo={!field.referenceType}
      autoSelect={!field.referenceType}
      options={options}
      getOptionLabel={labelOf}
      value={field.multiple ? chosen : chosen[0] ?? null}
      disabled={disabled}
      onChange={(_event, picked) => onChange(field.multiple ? picked as string[] : picked as string | null ?? "")}
      renderInput={params => (
        <TextField
          {...params}
          label={field.label}
          required={field.mandatory}
          error={!!loadError}
          helperText={loadError ?? helperText}
        />
      )}
    />
  );
}

export default ReferenceInput;
