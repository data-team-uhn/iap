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

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";

import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css";
import { Alert, Box, CircularProgress, Grid, Stack, useMediaQuery, useTheme } from "@mui/material";
import Modeler from "bpmn-js/lib/Modeler";
import NavigatedViewer from "bpmn-js/lib/NavigatedViewer";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, messageOf, RequestError } from "@iap/frontend-commons/requestFailure";
import { useNode } from "@iap/frontend-commons/useNode";

import { spelledOutBooleans } from "./bpmnBooleans";
import panelEditsModule from "./panelEdits";
import PropertiesPanel from "./PropertiesPanel";
import { BPMN_FILE, EMPTY_BPMN } from "./workflowModel";
import { parseWorkflowTypes, WORKFLOW_TYPES_PATH } from "./workflowTypes";

import type { ModdleTypes } from "./bpmnBooleans";
import type { FlowNodeType } from "./workflowTypes";
import type BaseViewer from "bpmn-js/lib/BaseViewer";

interface BpmnEditorProps {
  // The repository path of the wf:WorkflowVersion whose diagram to display.
  versionPath: string;
  // Two different bpmn-js components (Modeler vs. NavigatedViewer) back the two modes.
  editable?: boolean;
  // Only used in edit mode.
  onDirtyChange?: (dirty: boolean) => void;
  // Hands the page the means to serialize the current diagram.
  onReady?: (editor: DiagramEditor | null) => void;
}

// What the page can ask of the canvas it is editing.
export interface DiagramEditor {
  serialize: () => Promise<string>;
}

// One list while the vocabulary is not there, so that the panel does not match elements again for a new empty one
const NO_TYPES: readonly FlowNodeType[] = [];

// The BPMN canvas for one workflow version, with the properties panel beside it.
export default function BpmnEditor({ versionPath, editable = false, onDirtyChange, onReady }: BpmnEditorProps) {
  const fetchUtil = useAuthenticatedFetch();

  const bpmnContainerRef = useRef<HTMLDivElement>(null);
  const [ viewer, setViewer ] = useState<BaseViewer | null>(null);
  // Store a failure with the diagram that had the failure so it is not displayed if a new diagram is loaded.
  const [ failure, setFailure ] = useState<{ path: string; message: string }>();
  // loading until the diagram for the current versionPath has been fetched — no separate flag to keep in sync.
  const [ loadedPath, setLoadedPath ] = useState<string>();
  const loading = loadedPath !== versionPath;
  // What each element in the diagram means, and what may be edited on it. Read each time an editor opens,
  // since the vocabulary can be edited on a running server.
  const vocabulary = useNode(WORKFLOW_TYPES_PATH, "doc", parseWorkflowTypes);
  const types = vocabulary.value ?? NO_TYPES;
  const vocabularyFailure = vocabulary.loadError === undefined ? undefined
    : `The workflow node types could not be loaded. ${vocabulary.loadError}`;
  // The canvas's height, once measured, which the properties panel takes as its own where it sits beside it
  const [ height, setHeight ] = useState<string>();
  const sideBySide = useMediaQuery(useTheme().breakpoints.up("xl"));

  // useLayoutEffect so the container is measured before paint.
  useLayoutEffect(() => {
    const container = bpmnContainerRef.current;
    if (!container) {
      return undefined;
    }
    const top = Math.round(container.getBoundingClientRect().top);
    const options = { container, height: `calc(100vh - ${top}px - 100px)` };
    setHeight(options.height);
    const instance: BaseViewer = editable
      ? new Modeler({ ...options, additionalModules: [ panelEditsModule ] })
      : new NavigatedViewer(options);
    setViewer(instance);

    return () => instance.destroy();
  }, [editable]);

  // Load the XML diagram into the modeler or viewer.
  useEffect(() => {
    if (!viewer) {
      return undefined;
    }

    let cancelled = false;
    // The fetch itself isn't aborted on cleanup, so each step after it checks that this load is still
    // wanted: a superseded one imports nothing into the canvas, and reports nothing about it.
    fetchUtil(`${versionPath}/${BPMN_FILE}`)
      .then(response => {
        if (cancelled) {
          return undefined;
        }
        if (response.status === 404) {
          // A version with no saved XML yet isn't an error, load an empty canvas to start from.
          return viewer.importXML(EMPTY_BPMN);
        }
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        return response.text().then(xml => cancelled ? undefined
          : viewer.importXML(spelledOutBooleans(xml, viewer.get<ModdleTypes>("moddle"))));
      })
      .then(() => {
        if (!cancelled) {
          setFailure(undefined);
        }
      })
      .catch((refusal: unknown) => {
        if (!cancelled) {
          // Failed request and failed parse both already have a usable message, so they're handled the same way.
          setFailure({
            path: versionPath,
            message: refusal instanceof RequestError ? describeRequestFailure(refusal) : messageOf(refusal),
          });
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoadedPath(versionPath);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [viewer, versionPath, fetchUtil]);

  // The canvas owns diagram serialization; the page just gets the function to call it.
  const serialize = useCallback(async (): Promise<string> => {
    const { xml } = await (viewer as Modeler).saveXML({ format: true });
    if (xml == undefined) {
      throw new Error("The process could not be serialized.");
    }
    return xml;
  }, [viewer]);

  // Provide the parent element with the serialize function
  useEffect(() => {
    if (!onReady) {
      return undefined;
    }
    onReady(viewer && editable && !vocabularyFailure ? { serialize } : null);
    return () => onReady(null);
  }, [viewer, editable, vocabularyFailure, serialize, onReady]);

  // Notify the parent on every command-stack change — only the Modeler has one.
  useEffect(() => {
    if (!viewer || !editable || !onDirtyChange) {
      return undefined;
    }
    const changed = () => onDirtyChange(true);
    viewer.on("commandStack.changed", changed);
    return () => viewer.off("commandStack.changed", changed);
  }, [viewer, editable, onDirtyChange]);

  // A value typed and not yet written counts as unsaved, so leaving the page asks first.
  const drafted = useCallback(() => onDirtyChange?.(true), [onDirtyChange]);

  // A failure recorded against another version says nothing about this one, and is already on its way out.
  // Viewing needs no vocabulary, so without one the diagram is still shown, with a warning.
  const error = (editable ? vocabularyFailure : undefined)
    ?? (failure?.path === versionPath ? failure.message : undefined);
  const showDiagram = !loading && error === undefined;

  return (
    <Stack spacing={2}>
      { !editable && vocabularyFailure !== undefined && <Alert severity="warning">{vocabularyFailure}</Alert> }
      <Grid container spacing={4}>
        {/* The canvas keeps its place even while covered: bpmn-js sizes itself against the element it is
            given, so taking that element out of the layout would leave it measured against nothing */}
        <Grid size={{ xs: 12, xl: 10 }} sx={{ position: "relative" }}>
          {/* The Bpmn modeler/viewer does not currently support dark mode: force a white background */}
          <Box
            ref={bpmnContainerRef}
            aria-hidden={!showDiagram}
            sx={{ bgcolor: "white", visibility: showDiagram ? "visible" : "hidden" }}
          />
          { !showDiagram && (
            <Box sx={{
              position: "absolute",
              inset: 0,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              p: 2,
            }}>
              { error === undefined
                ? <CircularProgress size={24} />
                : <Alert severity="error">{error}</Alert> }
            </Box>
          )}
        </Grid>
        {/* Below xl the panel stacks under the canvas and the page scrolls. Beside it, the panel scrolls on its
            own, so a long form does not push the canvas out of view */}
        <Grid size={{ xs: 12, xl: 2 }} sx={sideBySide ? { height, overflowY: "auto" } : undefined}>
          <PropertiesPanel viewer={viewer} types={types} readOnly={!editable} onDraft={drafted} />
        </Grid>
      </Grid>
    </Stack>
  );
}
