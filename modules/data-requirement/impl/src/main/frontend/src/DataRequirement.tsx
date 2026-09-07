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

// Answering a data requirement inside a submission: the one file that knows about both a catalogue
// and a request. Everything under `catalogue/` is handed what to show and reports what was chosen;
// everything in `@iap/submissions` knows nothing of catalogues. This is where the two meet.

import { useCallback, useEffect, useMemo, useState } from "react";

import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import {
  registerRequirementComponent,
  type RequirementComponent,
  type RequirementComponentCandidate,
} from "@iap/submissions/requirementComponents";
import type { FormRequirement } from "@iap/submissions/submissionForm";

import DataCatalogue from "./catalogue/DataCatalogue";
import { fetchCatalogue, saveDataSelection } from "./catalogueApi";

import type { Catalogue } from "./catalogue/types";

/** The resource type the projection reports for a requirement that asks a submitter to choose data. */
export const DATA_REQUIREMENT = "datareq/DataRequirement";

/**
 * What this kind of requirement adds to the form.
 *
 * Declared here rather than on `FormRequirement` itself, which is the point of the registry: the
 * submissions module projects a kind it has never heard of by asking the module that declared it,
 * and it should not grow a field per foreign kind in exchange.
 */
interface DataRequirementProjection extends FormRequirement {
  /** The keys already chosen. Always present, empty meaning nothing has been. */
  fields?: string[];
  /** Where the catalogue version being answered against lives. */
  catalogueVersion?: string;
  /** That version's readable label. */
  catalogueVersionLabel?: string;
}

// A field key cannot contain it, so joining on it cannot make two different selections compare
// equal — where a space would, since ["a", "b"] and ["a b"] join to the same string. The same
// separator, for the same reason, as the answer field's own change check.
//
// Written as the escape and never as the byte itself: a literal NUL makes git treat the file as
// binary, so it renders as `Bin` with no diff for the rest of its life, and plain `grep` skips it
// without saying so.
const SEPARATOR = "\u0000";

/**
 * Whether two selections hold the same fields.
 *
 * Order-insensitive, because a selection is a set: it is stored as a multi-valued property whose
 * order means nothing, so a reordering is not a change somebody made and must not light up the Save
 * button or cost a round trip.
 */
function sameFields(one: readonly string[], other: readonly string[]): boolean {
  return one.length === other.length
    && [ ...one ].sort().join(SEPARATOR) === [ ...other ].sort().join(SEPARATOR);
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * Choosing data for one requirement.
 *
 * **There is a Save button here, where the rest of the editor deliberately has none.** An answer is
 * saved when it is finished — a field left, a box ticked — because for a question those are the same
 * moment. For a selection they are not: ticking a collection is one gesture that moves thirty fields,
 * and a submitter picking their way through a catalogue is mid-thought for as long as it takes. Saving
 * on every tick would be a workflow event each, and each of them able to fail; saving on some timer
 * would pick a moment nobody chose. So the panel that holds the selection holds the control that
 * commits it, which is what its `actions` slot is for.
 */
export const DataSelection: RequirementComponent = ({ path, requirement, disabled, onChanged }) => {
  const projection = requirement as DataRequirementProjection;
  const versionPath = projection.catalogueVersion;
  // Empty rather than absent is the server saying "nothing chosen yet", which is not the same as a
  // selection it could not read; both are reported, and neither is guessed at here
  const saved = useMemo(() => projection.fields ?? [], [ projection.fields ]);

  const [ catalogue, setCatalogue ] = useState<Catalogue>();
  const [ loadError, setLoadError ] = useState<string>();
  const [ chosen, setChosen ] = useState<readonly string[]>(saved);
  // What the last read said was stored, so that a re-read carrying the same selection does not throw
  // away what somebody is part-way through choosing
  const [ seen, setSeen ] = useState<readonly string[]>(saved);
  const [ saving, setSaving ] = useState(false);
  const [ saveError, setSaveError ] = useState<string>();

  // Follows the stored selection by CONTENT, not by identity. Every read of the form builds a fresh
  // array, so an identity check would reset the panel on every save of every other requirement —
  // including one somebody is still choosing in.
  if (!sameFields(seen, saved)) {
    setSeen(saved);
    setChosen(saved);
  }

  useEffect(() => {
    if (!versionPath) {
      return;
    }
    let cancelled = false;
    fetchCatalogue(versionPath)
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
  }, [ versionPath ]);

  const save = useCallback(() => {
    setSaving(true);
    setSaveError(undefined);
    saveDataSelection(path, requirement.name, chosen)
      .then(() => {
        // The request itself has changed, not just this requirement: what it is still missing is
        // recorded on the submission, and the control offering to send it reads that
        onChanged();
      })
      .catch((error: unknown) => { setSaveError(message(error)); })
      .finally(() => { setSaving(false); });
  }, [ path, requirement.name, chosen, onChanged ]);

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
        <Typography variant="caption" color="text.secondary">
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
            <Typography variant="caption" color="text.secondary">
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

// Registering as this module is loaded is the whole reason the extension naming it is not lazy: the
// editor loads the extension point so that candidates are in place before it draws, and a deferred
// asset would register after the form had already decided nothing could draw this kind.
registerRequirementComponent(dataSelectionCandidate);

export default DataSelection;
