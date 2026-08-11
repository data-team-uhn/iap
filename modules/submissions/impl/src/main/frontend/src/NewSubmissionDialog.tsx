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

import { useCallback, useEffect, useState } from "react";

import {
  Alert,
  Box,
  Button,
  CircularProgress,
  DialogActions,
  DialogContent,
  DialogContentText,
  FormControl,
  FormControlLabel,
  Radio,
  RadioGroup,
  Stack,
  TextField,
  Typography,
} from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";

// One serialized node: its own properties, plus its children under their node names. The
// `@path`/`@name` keys are what the serializer's identification step adds, and what tells a
// child node apart from an ordinary property value.
type JsonNode = Record<string, unknown>;

// What the submitter picks: a schema that is open for submissions, together with the version
// their submission will actually answer.
export interface SchemaChoice {
  // The active version's path, which is what raising a submission is asked for
  path: string;
  // The schema's human-readable name
  title: string;
  // The version's own label, shown because two submissions against the same schema can answer
  // different versions of it, and which one applies is not a detail
  version: string;
  description?: string;
}

function childNodes(node: JsonNode): JsonNode[] {
  return Object.values(node)
    .filter((value): value is JsonNode =>
      typeof value === "object" && value !== null && typeof (value as JsonNode)["@path"] === "string");
}

function text(node: JsonNode, key: string): string | undefined {
  const value = node[key];
  return typeof value === "string" ? value : undefined;
}

// The schemas a submission may be raised against: those marked active, each paired with its
// active version. Both halves have to be active — a retired version of a live schema is no more
// open than a live version of a retired one — which is the same rule the server enforces when
// the submission is actually raised, checked here only so that unusable choices are not offered.
export function schemaChoices(tree: JsonNode): SchemaChoice[] {
  return childNodes(tree)
    .filter(schema => schema.active === true)
    .flatMap(schema => {
      const version = childNodes(schema).find(candidate => candidate.active === true);
      if (!version) {
        return [];
      }
      return [ {
        path: version["@path"] as string,
        // A schema's title is mandatory, so the node name is a fallback for content that
        // predates the constraint rather than an expected case
        title: text(schema, "title") ?? text(schema, "@name") ?? "",
        version: text(version, "version") ?? "",
        description: text(version, "description"),
      } ];
    });
}

interface NewSubmissionDialogProps {
  onClose: () => void;
  // Called with the path of the submission that was raised, so the caller can open it
  onCreated: (path: string) => void;
}

// Raising a submission: pick what is being submitted against, name it, and let the workflow
// engine do the rest. The POST goes to the /Submissions homepage rather than to any CRUD
// endpoint — what a POST there means is decided by a system workflow definition — so this
// dialog knows only the two things that definition asks for.
//
// Mounted only while it is open, so that each opening starts from nothing: what is on offer is
// read afresh, and a half-filled attempt is not still sitting there next time.
function NewSubmissionDialog({ onClose, onCreated }: NewSubmissionDialogProps) {
  const [ choices, setChoices ] = useState<SchemaChoice[]>([]);
  const [ loadError, setLoadError ] = useState<string>();
  // Derived rather than toggled inside the effect: the dialog is loading until the fetch it starts
  // on mount has settled, one way or the other
  const [ settled, setSettled ] = useState(false);
  const [ selected, setSelected ] = useState("");
  const [ title, setTitle ] = useState("");
  const [ submitting, setSubmitting ] = useState(false);
  const [ submitError, setSubmitError ] = useState<string>();

  // Depth 2 reaches the schemas and their versions, and dereferencing is switched off because
  // each version references the workflow it freezes — a whole process definition per row, none
  // of which this dialog reads.
  useEffect(() => {
    let cancelled = false;
    fetch("/Schemas.2.-dereference.json")
      .then(response => {
        if (!response.ok) {
          throw new Error(`The list of schemas could not be loaded (${response.status})`);
        }
        return response.json() as Promise<JsonNode>;
      })
      .then(tree => {
        if (!cancelled) {
          setChoices(schemaChoices(tree));
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setLoadError(error instanceof Error ? error.message : String(error));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setSettled(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const submit = useCallback(() => {
    setSubmitting(true);
    setSubmitError(undefined);
    fetch("/Submissions", {
      method: "POST",
      // Trimmed because the name the submission gets is derived from the title, and surrounding
      // whitespace would be carried into it
      body: new URLSearchParams({ title: title.trim(), schemaVersion: selected }),
    })
      .then(async response => {
        if (!response.ok) {
          // The engine answers a refusal with the reason: no applicable workflow, not allowed to
          // raise this, or a payload it will not accept
          const body = (await response.json().catch(() => ({}))) as { error?: string };
          throw new Error(body.error ?? `The submission could not be raised (${response.status})`);
        }
        // The engine answers with a redirect to what it created, so the final URL of the followed
        // request is where the new submission lives
        onCreated(response.redirected ? new URL(response.url).pathname : "");
      })
      .catch((error: unknown) => setSubmitError(error instanceof Error ? error.message : String(error)))
      .finally(() => setSubmitting(false));
  }, [ onCreated, selected, title ]);

  const empty = settled && !loadError && choices.length === 0;

  return (
    <ResponsiveDialog title="New submission" withCloseButton open onClose={onClose}>
      <DialogContent dividers>
        { !settled && (
          <Box sx={{ display: "flex", justifyContent: "center", p: 2 }}>
            <CircularProgress aria-label="Loading the schemas" />
          </Box>
        ) }
        { loadError && <Alert severity="error">{loadError}</Alert> }
        { /* Distinguished from a failed load on purpose: "nothing is open for submissions" is an
             answer, and looks identical to a broken dialog if it is left blank */ }
        { empty && (
          <DialogContentText>
            Nothing is currently open for submissions.
          </DialogContentText>
        ) }
        { choices.length > 0 && (
          <Stack spacing={2}>
            <FormControl>
              <RadioGroup
                aria-label="What is being submitted"
                value={selected}
                onChange={event => setSelected(event.target.value)}
              >
                { choices.map(choice => (
                  <FormControlLabel
                    key={choice.path}
                    value={choice.path}
                    control={<Radio />}
                    label={
                      <>
                        <Typography component="span">{`${choice.title} ${choice.version}`.trim()}</Typography>
                        { choice.description && (
                          <Typography variant="body2" color="text.secondary">{choice.description}</Typography>
                        ) }
                      </>
                    }
                  />
                )) }
              </RadioGroup>
            </FormControl>
            <TextField
              label="Title"
              required
              value={title}
              onChange={event => setTitle(event.target.value)}
              helperText="How you will recognize this request in your list"
            />
            { submitError && <Alert severity="error">{submitError}</Alert> }
          </Stack>
        ) }
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          // Both are required by the workflow that raises the submission, so a request that
          // would certainly be refused is not offered
          disabled={submitting || !selected || !title.trim()}
          onClick={submit}
        >
          Create
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default NewSubmissionDialog;
