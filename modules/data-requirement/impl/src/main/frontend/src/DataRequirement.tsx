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

// Answering a data requirement inside a submission.

import { lazy, Suspense, useRef, useState } from "react";

import Alert from "@mui/material/Alert";
import CircularProgress from "@mui/material/CircularProgress";
import Typography from "@mui/material/Typography";

import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useNode } from "@iap/frontend-commons/useNode";
import type { Enqueue } from "@iap/frontend-commons/useWorkQueue";
import {
  registerRequirementComponent,
  type RequirementComponent,
  type RequirementComponentCandidate,
  type RequirementComponentProps,
} from "@iap/submissions/requirementComponents";
import SaveStatus, { type SaveState } from "@iap/submissions/SaveStatus";
import type { Requirement } from "@iap/submissions/submissionForm";

import { CATALOGUE_SELECTORS, readCatalogue, saveDataSelection } from "./catalogueApi";

// Loaded only where a data requirement is drawn, so an editor without one does not fetch it
const DataCatalogue = lazy(() => import("./catalogue/DataCatalogue"));

/** The resource type the submission form reports for a data requirement. */
export const DATA_REQUIREMENT_TYPE = "datareq/DataRequirement";

/** What a data requirement adds to its entry in the submission form. */
export interface DataRequirementProjection extends Requirement {
  /** The keys of the chosen fields, as `database/collection/field`; empty if none has been chosen. */
  fields?: string[];
  /** The repository path of the catalogue version to choose from; absent if none is published. */
  catalogueVersion?: string;
  /** That catalogue version's label, such as `2026-06`; absent when `catalogueVersion` is. */
  catalogueVersionLabel?: string;
}

/** Whether two selections hold the same fields, in any order: a selection is a set. */
function sameFields(one: readonly string[], other: readonly string[]): boolean {
  if (one.length !== other.length) {
    return false;
  }
  const held = new Set(one);
  return other.every(key => held.has(key));
}

/** What `useAutosave` gives a component: the selection to show, how to change it, and how its save is going. */
interface Autosave {
  chosen: readonly string[];
  change: (fields: string[]) => void;
  state: SaveState;
  error?: string;
}

/**
 * Saves a selection as it changes, through the editor's queue, so a step that completes the submission
 * waits for it. Changes made while a save waits in the queue go out with that save, so ticking quickly
 * sends one request rather than one a tick.
 */
function useAutosave(stored: readonly string[], save: (fields: readonly string[]) => Promise<void>,
  queueSave: Enqueue, onChanged: () => void): Autosave {
  const [ chosen, setChosen ] = useState(stored);
  // The stored selection as of the last read of the form
  const [ seen, setSeen ] = useState(stored);
  // How many saves are queued or running
  const [ unsettled, setUnsettled ] = useState(0);
  const [ outcome, setOutcome ] = useState<{ state: SaveState; error?: string }>({ state: "idle" });
  // What the save waiting in the queue sends when it starts, and whether there is one
  const next = useRef(stored);
  const waiting = useRef(false);

  // Compares the stored selection by content, not identity: every read of the form builds a new
  // array. While a save is queued or running, the shown choices stay, since the stored selection does
  // not hold them yet.
  if (!sameFields(seen, stored)) {
    setSeen(stored);
    if (unsettled === 0) {
      setChosen(stored);
    }
  }

  const change = (fields: string[]) => {
    setChosen(fields);
    next.current = fields;
    if (waiting.current) {
      return;
    }
    waiting.current = true;
    setUnsettled(count => count + 1);
    void queueSave(() => {
      waiting.current = false;
      return save(next.current);
    })
      // Two handlers, not a catch, so that a failure to read the form again is not reported as the save failing
      .then(() => {
        setOutcome({ state: "saved" });
        onChanged();
      }, (error: unknown) => { setOutcome({ state: "failed", error: messageOf(error) }); })
      .finally(() => { setUnsettled(count => count - 1); });
  };

  return { chosen, change, state: unsettled > 0 ? "saving" : outcome.state, error: outcome.error };
}

/** Choosing data from the catalogue version a requirement offers. */
function CatalogueSelection({ path, requirement, disabled, queueSave, onChanged, versionPath }:
RequirementComponentProps & { versionPath: string }) {
  const projection = requirement as DataRequirementProjection;
  const { value: catalogue, loadError, doFetch } = useNode(versionPath, CATALOGUE_SELECTORS, readCatalogue);
  const { chosen, change, state, error } = useAutosave(projection.fields ?? [],
    fields => saveDataSelection(doFetch, path, requirement.name, fields), queueSave, onChanged);

  return (
    <Suspense fallback={<CircularProgress aria-label="Loading the catalogue" />}>
      <DataCatalogue
        catalogue={catalogue}
        error={loadError}
        value={chosen}
        onChange={change}
        readOnly={disabled}
        notices={ projection.catalogueVersionLabel && (
          <Typography variant="description">
            Choosing from {projection.catalogueVersionLabel}
          </Typography>
        ) }
        actions={ disabled ? undefined : <SaveStatus state={state} error={error} /> }
      />
    </Suspense>
  );
}

/** Choosing data for one requirement. Each change is saved as it is made. */
export const DataSelection: RequirementComponent = props => {
  const versionPath = (props.requirement as DataRequirementProjection).catalogueVersion;
  if (!versionPath) {
    return (
      <Alert severity="warning">
        The catalogue this asks about has published nothing to choose from yet.
      </Alert>
    );
  }
  return <CatalogueSelection {...props} versionPath={versionPath} />;
};

export const dataSelectionCandidate: RequirementComponentCandidate = requirement =>
  requirement.type === DATA_REQUIREMENT_TYPE ? [ DataSelection, 50 ] : null;

// Registers the component when this module loads
registerRequirementComponent(dataSelectionCandidate);

export default DataSelection;
