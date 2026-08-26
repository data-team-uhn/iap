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
import { Alert, CircularProgress, Grid, Stack } from "@mui/material";
import Modeler from "bpmn-js/lib/Modeler";
import NavigatedViewer from "bpmn-js/lib/NavigatedViewer";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, messageOf, RequestError } from "@iap/frontend-commons/requestFailure";

import PropertiesPanel from "./PropertiesPanel";
import { BPMN_FILE } from "./workflowModel";

import type BaseViewer from "bpmn-js/lib/BaseViewer";

interface BpmnEditorProps {
  // The repository path of the wf:WorkflowVersion whose diagram to display.
  versionPath: string;
  // When false — the default — the diagram is displayed through a viewer that has no palette, no
  // context pad and no editing behaviours at all, rather than an editor with its controls disabled:
  // there is then nothing to disable, and nothing that could write.
  editable?: boolean;
  // Told whether the diagram has changes that have not been saved, so that the page around the
  // canvas can offer to save them and warn before they are lost. Only ever called in edit mode.
  onDirtyChange?: (dirty: boolean) => void;
  // Handed a function that serializes what is currently drawn, once the canvas is ready — the page
  // owns the Save button, this owns the diagram. Null while there is nothing to save.
  onReady?: (serialize: (() => Promise<string>) | null) => void;
}

// The BPMN canvas for one workflow version, with the properties panel beside it.
//
// Which bpmn-js class backs it is the whole of the difference between the two modes: a Modeler can
// draw and a NavigatedViewer can only pan and zoom, so a read-only diagram is not an editor being
// trusted to behave — it is a component with no editing in it.
export default function BpmnEditor({ versionPath, editable = false, onDirtyChange, onReady }: BpmnEditorProps) {
  // Survives the session expiring mid-edit: the request is re-sent after the user signs back in, so
  // a long editing session does not silently lose a save.
  const fetchUtil = useAuthenticatedFetch();

  const bpmnContainerRef = useRef<HTMLDivElement>(null);
  const [ viewer, setViewer ] = useState<BaseViewer | null>(null);
  const [ error, setError ] = useState<string>();
  // Loading is derived rather than toggled: the canvas is loading until the fetch for the diagram it
  // is currently showing has settled, one way or the other. A version opened after another one is
  // then loading again without anything having to remember to say so.
  const [ loadedPath, setLoadedPath ] = useState<string>();
  const loading = loadedPath !== versionPath;

  useLayoutEffect(() => {
    const container = bpmnContainerRef.current;
    if (!container) {
      return undefined;
    }
    const top = Math.round(container.getBoundingClientRect().top);
    const options = { container, height: `calc(100vh - ${top}px - 100px)` };
    const instance: BaseViewer = editable ? new Modeler(options) : new NavigatedViewer(options);
    setViewer(instance);

    return () => instance.destroy();
  }, [editable]);

  // The diagram, loaded once the canvas exists to import it into. A version with no diagram saved
  // yet is not an error: it is a canvas to start drawing on, or an empty one to look at — so a 404
  // leaves the canvas as it is and reports nothing.
  useEffect(() => {
    if (!viewer) {
      return undefined;
    }
    let cancelled = false;
    fetchUtil(`${versionPath}/${BPMN_FILE}`)
      .then(response => {
        if (response.status === 404) {
          return undefined;
        }
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        return response.text().then(xml => viewer.importXML(xml));
      })
      .then(() => {
        if (!cancelled) {
          setError(undefined);
        }
      })
      .catch((failure: unknown) => {
        if (!cancelled) {
          // An unreadable response and an unparseable diagram are both "this cannot be displayed",
          // and both are already worded: one by the server, one by bpmn-js
          setError(failure instanceof RequestError ? describeRequestFailure(failure) : messageOf(failure));
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

  // Serializing is the canvas's own job, so the page that owns the Save button is handed the means
  // rather than the modeler
  const serialize = useCallback(async (): Promise<string> => {
    const { xml } = await (viewer as Modeler).saveXML({ format: true });
    if (xml == undefined) {
      throw new Error("The diagram could not be serialized.");
    }
    return xml;
  }, [viewer]);

  useEffect(() => {
    if (!onReady) {
      return undefined;
    }
    onReady(viewer && editable ? serialize : null);
    return () => onReady(null);
  }, [viewer, editable, serialize, onReady]);

  // What "unsaved" means, in bpmn-js terms: any command that went through the command stack. Only
  // the Modeler has one, so this is the one behaviour that is genuinely mode-specific.
  useEffect(() => {
    if (!viewer || !editable || !onDirtyChange) {
      return undefined;
    }
    const changed = () => onDirtyChange(true);
    viewer.on("commandStack.changed", changed);
    return () => viewer.off("commandStack.changed", changed);
  }, [viewer, editable, onDirtyChange]);

  return (
    <Stack>
      { error && <Alert severity="error" sx={{ mb: 1 }}>{error}</Alert> }
      { loading && <CircularProgress size={24} sx={{ display: "block", mx: "auto", my: 2 }} /> }
      <Grid container spacing={4}>
        {/* The Bpmn editor does not currently support dark mode: force a white background */}
        <Grid size={{ xs: 12, xl: 10 }} sx={{ bgcolor: "white" }} ref={bpmnContainerRef} />
        <Grid size={{ xs: 12, xl: 2 }}>
          <PropertiesPanel viewer={viewer} readOnly={!editable} />
        </Grid>
      </Grid>
    </Stack>
  );
}
