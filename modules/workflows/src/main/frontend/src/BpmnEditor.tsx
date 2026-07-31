//
//  Licensed to the Apache Software Foundation (ASF) under one
//  or more contributor license agreements.  See the NOTICE file
//  distributed with this work for additional information
//  regarding copyright ownership.  The ASF licenses this file
//  to you under the Apache License, Version 2.0 (the
//  "License"); you may not use this file except in compliance
//  with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing,
//  software distributed under the License is distributed on an
//  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//  KIND, either express or implied.  See the License for the
//  specific language governing permissions and limitations
//  under the License.
//

import {
  useCallback,
  useLayoutEffect,
  useRef,
  useState
} from "react";

import "bpmn-js/dist/assets/diagram-js.css";
import "bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css";
import {
  Alert,
  Button,
  Checkbox,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Grid,
  List,
  ListItem,
  ListItemButton,
  ListItemText,
  Snackbar,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import Modeler from 'bpmn-js/lib/Modeler';

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import PropertiesPanel from "./PropertiesPanel";
import { WORKFLOWS_ROOT, parseWorkflowList, type WorkflowVersionSummary } from "./workflowModel";

type SnackbarSeverity = "success" | "error" | "warning";

interface SnackbarState {
  open: boolean;
  message: string;
  severity: SnackbarSeverity;
}

// The diagram is an nt:file child of the version node rather than one of its properties, so it is
// fetched and posted on its own path; listing the versions no longer carries every diagram with it.
// The extension earns its keep: Sling types a file from its name, so without it every diagram the
// repository serves would be an untyped binary.
const BPMN_FILE = "bpmn.xml";

function errorText(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

// A multipart part named after the child node, with the type hint that makes the Sling POST servlet
// store it as an nt:file; without the hint the same upload lands as a binary property.
function bpmnUpload(xml: string, body: FormData = new FormData()): FormData {
  body.set(`./${BPMN_FILE}`, new File([xml], BPMN_FILE, { type: "application/xml" }));
  body.set(`./${BPMN_FILE}@TypeHint`, "nt:file");
  return body;
}

const EXAMPLE_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" id="Definitions_07212ml" targetNamespace="http://bpmn.io/schema/bpmn" exporter="bpmn-js (https://demo.bpmn.io)" exporterVersion="18.16.0">
  <bpmn:process id="Process_1ajiizs" isExecutable="false">
    <bpmn:sequenceFlow id="Flow_1bghbvl" sourceRef="StartEvent_0gcwblc" targetRef="Activity_0waxs0q" />
    <bpmn:sequenceFlow id="Flow_1cyttg7" sourceRef="Activity_0waxs0q" targetRef="Event_1q4m3yf" />
    <bpmn:startEvent id="StartEvent_0gcwblc">
      <bpmn:outgoing>Flow_1bghbvl</bpmn:outgoing>
      <bpmn:messageEventDefinition id="MessageEventDefinition_0s9hvhs" />
    </bpmn:startEvent>
    <bpmn:userTask id="Activity_0waxs0q">
      <bpmn:incoming>Flow_1bghbvl</bpmn:incoming>
      <bpmn:outgoing>Flow_1cyttg7</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id="Event_1q4m3yf">
      <bpmn:incoming>Flow_1cyttg7</bpmn:incoming>
      <bpmn:messageEventDefinition id="MessageEventDefinition_06fhigp" />
    </bpmn:endEvent>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1ajiizs">
      <bpmndi:BPMNShape id="Event_15qreer_di" bpmnElement="StartEvent_0gcwblc">
        <dc:Bounds x="152" y="102" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Activity_10t3nsr_di" bpmnElement="Activity_0waxs0q">
        <dc:Bounds x="240" y="80" width="100" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Event_07olk38_di" bpmnElement="Event_1q4m3yf">
        <dc:Bounds x="392" y="102" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Flow_1bghbvl_di" bpmnElement="Flow_1bghbvl">
        <di:waypoint x="188" y="120" />
        <di:waypoint x="240" y="120" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Flow_1cyttg7_di" bpmnElement="Flow_1cyttg7">
        <di:waypoint x="340" y="120" />
        <di:waypoint x="392" y="120" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`

export default function BpmnEditor() {
  // Survives the session expiring mid-edit: the request is re-sent after the user signs back in,
  // so a long editing session does not silently lose a save.
  const fetchUtil = useAuthenticatedFetch();

  const bpmnContainerRef = useRef<HTMLDivElement>(null);
  const [modeler, setModeler] = useState<Modeler | null>(null);

  const [currentPath, setCurrentPath] = useState<string | null>(null);
  const [currentTitle, setCurrentTitle] = useState<string | null>(null);

  const [loadOpen, setLoadOpen] = useState(false);
  const [definitions, setDefinitions] = useState<WorkflowVersionSummary[]>([]);
  const [loadingDefs, setLoadingDefs] = useState(false);

  const [newOpen, setNewOpen] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [newDescription, setNewDescription] = useState("");
  const [newVersion, setNewVersion] = useState("1.0");
  const [newActive, setNewActive] = useState(false);
  const [newXml, setNewXml] = useState(EXAMPLE_BPMN);
  const [creating, setCreating] = useState(false);

  const [saving, setSaving] = useState(false);
  const [snackbar, setSnackbar] = useState<SnackbarState>({ open: false, message: "", severity: "success" });

  useLayoutEffect(() => {
    const container = bpmnContainerRef.current;
    if (!container) return;
    const top = Math.round(container.getBoundingClientRect().top);
    const bpmnModeler = new Modeler({
      container,
      height: `calc(100vh - ${top}px - 100px)`,
    });
    setModeler(bpmnModeler);

    return () => bpmnModeler.destroy();
  }, []);

  const showMessage = useCallback((message: string, severity: SnackbarSeverity = "success") => {
    setSnackbar({ open: true, message, severity });
  }, []);

  const openLoadDialog = useCallback(() => {
    setLoadOpen(true);
    setLoadingDefs(true);
    // Two levels is exactly what this list renders: the definitions, for their titles, and the
    // versions under them. The depth selector both turns child serialization on and stops the
    // traversal there, so a version's own children -- the diagram file, and the parsed flow nodes
    // once those exist -- are left as bare paths instead of being dragged into every listing.
    fetchUtil(`${WORKFLOWS_ROOT}.2.json`)
      .then(r => r.json())
      .then((data: Record<string, unknown>) => setDefinitions(parseWorkflowList(data)))
      .catch(() => showMessage("Failed to load workflow definitions", "error"))
      .finally(() => setLoadingDefs(false));
  }, [fetchUtil, showMessage]);

  const loadDefinition = useCallback(async (def: WorkflowVersionSummary) => {
    let xml: string;
    try {
      const response = await fetchUtil(`${def.path}/${BPMN_FILE}`);
      if (response.status === 404) {
        showMessage(`"${def.title}" v${def.version} has no BPMN XML saved yet`, "warning");
        setLoadOpen(false);
        return;
      }
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      xml = await response.text();
    } catch (err) {
      showMessage(`Failed to load the diagram: ${errorText(err)}`, "error");
      return;
    }
    modeler?.importXML(xml)
      .then(() => {
        setCurrentPath(def.path);
        setCurrentTitle(`${def.title} (v${def.version})`);
        setLoadOpen(false);
        showMessage(`Loaded "${def.title}" v${def.version}`);
      })
      .catch((err: unknown) => showMessage(`Failed to import XML: ${errorText(err)}`, "error"));
  }, [fetchUtil, modeler, showMessage]);

  const save = useCallback(async () => {
    if (!currentPath || !modeler) return;
    setSaving(true);
    try {
      const { xml } = await modeler.saveXML({ format: true });
      if (!xml) throw new Error("Failed to serialize BPMN XML");
      const response = await fetchUtil(currentPath, { method: "POST", body: bpmnUpload(xml) });
      if (response.ok) {
        showMessage(`Saved "${currentTitle}"`);
      } else {
        throw new Error(`HTTP ${response.status}`);
      }
    } catch (err) {
      showMessage(`Save failed: ${(err as Error).message}`, "error");
    } finally {
      setSaving(false);
    }
  }, [currentPath, currentTitle, fetchUtil, modeler, showMessage]);

  const resetNewDialog = useCallback(() => {
    setNewTitle("");
    setNewDescription("");
    setNewVersion("1.0");
    setNewActive(false);
    setNewXml(EXAMPLE_BPMN);
    setNewOpen(false);
  }, []);

  const createDefinition = useCallback(async () => {
    if (!newTitle.trim()) {
      showMessage("Title is required", "warning");
      return;
    }
    if (!newVersion.trim()) {
      showMessage("Version is required", "warning");
      return;
    }
    setCreating(true);
    try {
      const defSlug = newTitle.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-");
      const defBody = new URLSearchParams();
      defBody.set("jcr:primaryType", "wf:WorkflowDefinition");
      defBody.set(":nameHint", defSlug);
      defBody.set("title", newTitle.trim());
      defBody.set("active", String(newActive));
      defBody.set("active@TypeHint", "Boolean");

      const defResponse = await fetchUtil(`${WORKFLOWS_ROOT}/`, { method: "POST", body: defBody });
      if (!defResponse.ok) throw new Error(`HTTP ${defResponse.status}`);

      let defPath = `${WORKFLOWS_ROOT}/${defSlug}`;
      const defLocation = defResponse.headers.get("Location");
      if (defLocation) {
        try { defPath = new URL(defLocation).pathname; } catch { defPath = defLocation; }
      }

      const versionSlug = newVersion.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-");
      const versionBody = new URLSearchParams();
      versionBody.set("jcr:primaryType", "wf:WorkflowVersion");
      versionBody.set(":nameHint", versionSlug);
      versionBody.set("version", newVersion.trim());
      if (newDescription.trim()) versionBody.set("description", newDescription.trim());
      versionBody.set("active", String(newActive));
      versionBody.set("active@TypeHint", "Boolean");

      const versionResponse = await fetchUtil(`${defPath}/`, { method: "POST", body: versionBody });
      if (!versionResponse.ok) throw new Error(`HTTP ${versionResponse.status}`);

      let versionPath = `${defPath}/${versionSlug}`;
      const versionLocation = versionResponse.headers.get("Location");
      if (versionLocation) {
        try { versionPath = new URL(versionLocation).pathname; } catch { versionPath = versionLocation; }
      }

      if (newXml.trim()) {
        // A request of its own rather than one multipart create: Sling creates the node a file
        // part's path implies before it applies jcr:primaryType, so sending the diagram together
        // with the version's own properties leaves a sling:Folder behind instead of a
        // wf:WorkflowVersion.
        const diagramResponse = await fetchUtil(versionPath, { method: "POST", body: bpmnUpload(newXml.trim()) });
        if (!diagramResponse.ok) throw new Error(`HTTP ${diagramResponse.status}`);
      }

      if (newXml.trim() && modeler) {
        await modeler.importXML(newXml.trim());
      }
      setCurrentPath(versionPath);
      setCurrentTitle(`${newTitle.trim()} (v${newVersion.trim()})`);
      showMessage(`Created "${newTitle.trim()}" v${newVersion.trim()}`);
      resetNewDialog();
    } catch (err) {
      showMessage(`Create failed: ${(err as Error).message}`, "error");
    } finally {
      setCreating(false);
    }
  }, [
    newTitle,
    newDescription,
    newVersion,
    newActive,
    newXml,
    fetchUtil,
    modeler,
    showMessage,
    resetNewDialog
  ]);

  return (
    <Stack>
      <Stack direction="row" spacing={1} sx={{ p: 1, alignItems: "center", borderBottom: 1, borderColor: "divider" }}>
        <Button variant="outlined" size="small" onClick={openLoadDialog}>Load</Button>
        <Button variant="contained" size="small" onClick={() => void save()} disabled={!currentPath || saving}>
          {saving ? <CircularProgress size={16} /> : "Save"}
        </Button>
        <Button variant="outlined" size="small" onClick={() => setNewOpen(true)}>New</Button>
        {currentTitle && (
          <Typography variant="body2" color="text.secondary" sx={{ ml: 1 }}>
            Editing: <strong>{currentTitle}</strong>
          </Typography>
        )}
      </Stack>

      <Grid container spacing={4} >
        <Grid size={{ xs:12, xl:10 }} ref={bpmnContainerRef} />
        <Grid size={{ xs:12, xl:2 }} >
          <PropertiesPanel modeler={modeler} />
        </Grid>
      </Grid>

      {/* Load dialog */}
      <Dialog open={loadOpen} onClose={() => setLoadOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Load Workflow Definition</DialogTitle>
        <DialogContent dividers>
          {loadingDefs ? (
            <Stack sx={{ py: 2, alignItems: "center" }}><CircularProgress /></Stack>
          ) : definitions.length === 0 ? (
            <Typography>No workflow definitions found at {WORKFLOWS_ROOT}.</Typography>
          ) : (
            <List disablePadding>
              {definitions.map(def => (
                <ListItem key={def.path} disablePadding>
                  <ListItemButton onClick={() => void loadDefinition(def)}>
                    <ListItemText
                      primary={def.title}
                      secondary={[`v${def.version}`, def.description].filter(Boolean).join(" · ") || null}
                    />
                  </ListItemButton>
                </ListItem>
              ))}
            </List>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setLoadOpen(false)}>Cancel</Button>
        </DialogActions>
      </Dialog>

      {/* New definition dialog */}
      <Dialog open={newOpen} onClose={resetNewDialog} maxWidth="md" fullWidth>
        <DialogTitle>New Workflow Definition</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField
              label="Title"
              required
              fullWidth
              value={newTitle}
              onChange={e => setNewTitle(e.target.value)}
            />
            <TextField
              label="Description"
              fullWidth
              multiline
              rows={2}
              value={newDescription}
              onChange={e => setNewDescription(e.target.value)}
            />
            <TextField
              label="Version"
              required
              fullWidth
              value={newVersion}
              onChange={e => setNewVersion(e.target.value)}
              placeholder="e.g. 1.0"
            />
            <FormControlLabel
              control={<Checkbox checked={newActive} onChange={e => setNewActive(e.target.checked)} />}
              label="Active"
            />
            <TextField
              label="BPMN XML"
              fullWidth
              multiline
              rows={12}
              placeholder="Paste BPMN 2.0 XML here…"
              value={newXml}
              onChange={e => setNewXml(e.target.value)}
              slotProps={{ input: { style: { fontFamily: "monospace", fontSize: "12px" } } }}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={resetNewDialog}>Cancel</Button>
          <Button onClick={() => void createDefinition()} variant="contained" disabled={creating}>
            {creating ? <CircularProgress size={20} /> : "Create"}
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={snackbar.open}
        autoHideDuration={4000}
        onClose={() => setSnackbar(s => ({ ...s, open: false }))}
      >
        <Alert severity={snackbar.severity} onClose={() => setSnackbar(s => ({ ...s, open: false }))}>
          {snackbar.message}
        </Alert>
      </Snackbar>
    </Stack>
  );
}
