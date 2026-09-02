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

// Answering a data requirement inside a submission. This is the glue between the catalogue browser
// under `catalogue/` and the submission editor, neither of which knows about the other.

import { useCallback, useEffect, useMemo, useState } from "react";

import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import {
  registerRequirementComponent,
  type RequirementComponent,
  type RequirementComponentCandidate,
} from "@iap/submissions/requirementComponents";
import type { Requirement } from "@iap/submissions/submissionForm";

import DataCatalogue from "./catalogue/DataCatalogue";
import { fetchCatalogue, saveDataSelection } from "./catalogueApi";

import type { Catalogue } from "./catalogue/types";

/** The resource type the projection reports for a requirement that asks a submitter to choose data. */
export const DATA_REQUIREMENT = "datareq/DataRequirement";

/** What the form projection adds for a data requirement. */
export interface DataRequirementProjection extends Requirement {
  /** The keys of the chosen fields, as `database/collection/field`; empty if none has been chosen. */
  fields?: string[];
  /** The repository path of the catalogue version to choose from; absent if none is published. */
  catalogueVersion?: string;
  /** That catalogue version's label, such as `2026-06`; absent along with the path. */
  catalogueVersionLabel?: string;
}

/** Whether two selections hold the same fields, in any order: a selection is a set. */
function sameFields(one: readonly string[], other: readonly string[]): boolean {
  const sorted = [ ...other ].sort();
  return one.length === other.length && [ ...one ].sort().every((key, index) => key === sorted[index]);
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * Choosing data for one requirement.
 *
 * A Save button commits the selection, rather than each tick saving it. Ticking a collection moves
 * many fields at once, and every save is a workflow event that can fail.
 */
export const DataSelection: RequirementComponent = ({ path, requirement, disabled, onChanged }) => {
  const projection = requirement as DataRequirementProjection;
  const versionPath = projection.catalogueVersion;
  const saved = useMemo(() => projection.fields ?? [], [ projection.fields ]);

  const [ catalogue, setCatalogue ] = useState<Catalogue>();
  const [ loadError, setLoadError ] = useState<string>();
  const [ chosen, setChosen ] = useState<readonly string[]>(saved);
  // The stored selection as of the last read of the form
  const [ seen, setSeen ] = useState<readonly string[]>(saved);
  const [ saving, setSaving ] = useState(false);
  const [ saveError, setSaveError ] = useState<string>();
  const doFetch = useAuthenticatedFetch();

  // Follows the stored selection by content, not identity. Every read of the form builds a fresh
  // array, so an identity check would discard unsaved choices whenever another requirement saves.
  if (!sameFields(seen, saved)) {
    setSeen(saved);
    setChosen(saved);
  }

  useEffect(() => {
    if (!versionPath) {
      return;
    }
    let cancelled = false;
    fetchCatalogue(doFetch, versionPath)
      .then(loaded => {
        if (!cancelled) {
          setCatalogue(loaded);
          setLoadError(undefined);
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setLoadError(message(error));
        }
      });
    return () => { cancelled = true; };
  }, [ doFetch, versionPath ]);

  const save = useCallback(() => {
    setSaving(true);
    setSaveError(undefined);
    saveDataSelection(doFetch, path, requirement.name, chosen)
      .then(() => {
        // What the request still lacks is recorded on the submission, so the form is read again
        onChanged();
      })
      .catch((error: unknown) => { setSaveError(message(error)); })
      .finally(() => { setSaving(false); });
  }, [ doFetch, path, requirement.name, chosen, onChanged ]);

  if (!versionPath) {
    return (
      <Alert severity="warning">
        The catalogue this asks about has published nothing to choose from yet.
      </Alert>
    );
  }

  const dirty = !sameFields(chosen, saved);

  return (
    <DataCatalogue
      catalogue={catalogue}
      loading={!catalogue && !loadError}
      error={loadError ?? null}
      value={chosen}
      onChange={setChosen}
      readOnly={disabled}
      notices={ projection.catalogueVersionLabel && (
        <Typography variant="description">
          Choosing from {projection.catalogueVersionLabel}
        </Typography>
      ) }
      actions={ disabled ? undefined : (
        <Stack spacing={1}>
          { saveError && <Alert severity="error">{saveError}</Alert> }
          <Button
            variant="contained"
            onClick={save}
            disabled={!dirty || saving}
          >
            { saving ? "Saving…" : "Save selection" }
          </Button>
          { dirty && !saving && (
            <Typography variant="description">
              Not saved yet
            </Typography>
          ) }
        </Stack>
      ) }
    />
  );
};

export const dataSelectionCandidate: RequirementComponentCandidate = requirement =>
  requirement.type === DATA_REQUIREMENT ? [ DataSelection, 50 ] : null;

// Registers on load. The extension naming this asset must not be lazy, or the candidate arrives after
// the editor has drawn the form.
registerRequirementComponent(dataSelectionCandidate);

export default DataSelection;
