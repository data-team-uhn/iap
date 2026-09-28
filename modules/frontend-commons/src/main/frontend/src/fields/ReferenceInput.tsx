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
import { describeRequestFailure, RequestError } from "../requestFailure";
import {
  candidateOf, type ContentField, type ReferenceCandidate, referenceQuery, type SerializedNode,
} from "./fieldsModel";

// The nodes a reference field may point at, read once, sorted by what they are offered as
function useCandidates(field: ContentField) {
  const doFetch = useAuthenticatedFetch();
  const [ candidates, setCandidates ] = useState<ReferenceCandidate[]>([]);
  const [ loadError, setLoadError ] = useState<string>();
  const query = referenceQuery(field);
  const root = field.referenceRoot;

  useEffect(() => {
    if (!query) {
      return;
    }
    let current = true;
    doFetch(`/search.json?${new URLSearchParams({ query, limit: "1000" }).toString()}`)
      .then(response => {
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        return response.json() as Promise<{ rows?: SerializedNode[] }>;
      })
      .then(result => {
        if (current) {
          setCandidates((result.rows ?? [])
            .map(row => candidateOf(row, root))
            .filter((candidate): candidate is ReferenceCandidate => candidate !== undefined)
            .sort((a, b) => a.label.localeCompare(b.label)));
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
  }, [ doFetch, query, root ]);

  return { candidates, loadError };
}

interface ReferenceInputProps {
  field: ContentField;
  // The path of each node pointed at
  value: string | string[];
  disabled: boolean;
  helperText?: string;
  onChange: (value: string | string[]) => void;
}

// Picks what a reference field points at among the nodes it may. A field naming no type takes any path.
function ReferenceInput({ field, value, disabled, helperText, onChange }: ReferenceInputProps) {
  const { candidates, loadError } = useCandidates(field);
  const chosen = Array.isArray(value) ? value : [ value ].filter(path => path !== "");
  // What is pointed at stays offered even when it is no longer a candidate, so it still shows
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
