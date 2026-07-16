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

function fetchUtil(url, fetchArgs) {
  return new Promise(function(resolve, reject) {
    function fetchFunc() {
      fetch(url, fetchArgs)
        .then((response) => {
          if (response.status == 401 || response.status == 500) {
            console.log("Error fetching: " + response.status);
          } else if (response.ok && response.url.startsWith(window.location.origin + "/login")) {
            console.log("Requested relogin");
          } else {
            resolve(response);
          }
        })
        .catch((err) => {reject(err)});
    }
    fetchFunc();
  });
}

const WORKFLOWS_PATH = "/Workflows";

function extractVersions(defKey, defNode) {
  return Object.entries(defNode)
    .filter(([, v]) => v && typeof v === "object" && v["jcr:primaryType"] === "wf:WorkflowVersion")
    .map(([versionKey, versionNode]) => ({
      name: `${defKey}/${versionKey}`,
      path: `${WORKFLOWS_PATH}/${defKey}/${versionKey}`,
      title: defNode.title || defKey,
      version: versionNode.version || "",
      description: versionNode.description || "",
      bpmnXml: versionNode.bpmnXml || "",
    }));
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
  const bpmnContainerRef = useRef();
  const [modeler, setModeler] = useState(null);

  const [currentPath, setCurrentPath] = useState(null);
  const [currentTitle, setCurrentTitle] = useState(null);

  const [loadOpen, setLoadOpen] = useState(false);
  const [definitions, setDefinitions] = useState([]);
  const [loadingDefs, setLoadingDefs] = useState(false);

  const [newOpen, setNewOpen] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [newDescription, setNewDescription] = useState("");
  const [newVersion, setNewVersion] = useState("1.0");
  const [newActive, setNewActive] = useState(false);
  const [newXml, setNewXml] = useState(EXAMPLE_BPMN);
  const [creating, setCreating] = useState(false);

  const [saving, setSaving] = useState(false);
  const [snackbar, setSnackbar] = useState({ open: false, message: "", severity: "success" });

  useLayoutEffect(() => {
    const container = bpmnContainerRef.current;
    const top = Math.round(container.getBoundingClientRect().top);
    const bpmnModeler = new Modeler({
      container,
      height: `calc(100vh - ${top}px - 100px)`,
    });
    setModeler(bpmnModeler);

    return () => bpmnModeler.destroy();
  }, []);

  const showMessage = useCallback((message, severity = "success") => {
    setSnackbar({ open: true, message, severity });
  }, []);

  const openLoadDialog = useCallback(() => {
    setLoadOpen(true);
    setLoadingDefs(true);
    fetchUtil(`${WORKFLOWS_PATH}.2.json`)
      .then(r => r.json())
      .then(data => {
        const defs = Object.entries(data)
          .filter(([, v]) => v && typeof v === "object" && v["jcr:primaryType"] === "wf:WorkflowDefinition")
          .flatMap(([defKey, defNode]) => extractVersions(defKey, defNode));
        setDefinitions(defs);
      })
      .catch(() => showMessage("Failed to load workflow definitions", "error"))
      .finally(() => setLoadingDefs(false));
  }, [showMessage]);

  const loadDefinition = useCallback((def) => {
    if (!def.bpmnXml) {
      showMessage(`"${def.title}" v${def.version} has no BPMN XML saved yet`, "warning");
      setLoadOpen(false);
      return;
    }
    modeler.importXML(def.bpmnXml)
      .then(() => {
        setCurrentPath(def.path);
        setCurrentTitle(`${def.title} (v${def.version})`);
        setLoadOpen(false);
        showMessage(`Loaded "${def.title}" v${def.version}`);
      })
      .catch(err => showMessage(`Failed to import XML: ${err.message}`, "error"));
  }, [modeler, showMessage]);

  const save = useCallback(async () => {
    if (!currentPath || !modeler) return;
    setSaving(true);
    try {
      const { xml } = await modeler.saveXML({ format: true });
      const body = new URLSearchParams({ bpmnXml: xml });
      const response = await fetchUtil(currentPath, { method: "POST", body });
      if (response.ok) {
        showMessage(`Saved "${currentTitle}"`);
      } else {
        throw new Error(`HTTP ${response.status}`);
      }
    } catch (err) {
      showMessage(`Save failed: ${err.message}`, "error");
    } finally {
      setSaving(false);
    }
  }, [currentPath, currentTitle, modeler, showMessage]);

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

      const defResponse = await fetchUtil(`${WORKFLOWS_PATH}/`, { method: "POST", body: defBody });
      if (!defResponse.ok) throw new Error(`HTTP ${defResponse.status}`);

      let defPath = `${WORKFLOWS_PATH}/${defSlug}`;
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
      if (newXml.trim()) versionBody.set("bpmnXml", newXml.trim());

      const versionResponse = await fetchUtil(`${defPath}/`, { method: "POST", body: versionBody });
      if (!versionResponse.ok) throw new Error(`HTTP ${versionResponse.status}`);

      let versionPath = `${defPath}/${versionSlug}`;
      const versionLocation = versionResponse.headers.get("Location");
      if (versionLocation) {
        try { versionPath = new URL(versionLocation).pathname; } catch { versionPath = versionLocation; }
      }

      if (newXml.trim() && modeler) {
        await modeler.importXML(newXml.trim());
      }
      setCurrentPath(versionPath);
      setCurrentTitle(`${newTitle.trim()} (v${newVersion.trim()})`);
      showMessage(`Created "${newTitle.trim()}" v${newVersion.trim()}`);
      resetNewDialog();
    } catch (err) {
      showMessage(`Create failed: ${err.message}`, "error");
    } finally {
      setCreating(false);
    }
  }, [
    newTitle,
    newDescription,
    newVersion,
    newActive,
    newXml,
    modeler,
    showMessage,
    resetNewDialog
  ]);

  return (
    <Stack>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ p: 1, borderBottom: 1, borderColor: "divider" }}>
        <Button variant="outlined" size="small" onClick={openLoadDialog}>Load</Button>
        <Button variant="contained" size="small" onClick={save} disabled={!currentPath || saving}>
          {saving ? <CircularProgress size={16} /> : "Save"}
        </Button>
        <Button variant="outlined" size="small" onClick={() => setNewOpen(true)}>New</Button>
        {currentTitle && (
          <Typography variant="body2" color="text.secondary" sx={{ ml: 1 }}>
            Editing: <strong>{currentTitle}</strong>
          </Typography>
        )}
      </Stack>

      <div ref={bpmnContainerRef} />

      {/* Load dialog */}
      <Dialog open={loadOpen} onClose={() => setLoadOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Load Workflow Definition</DialogTitle>
        <DialogContent dividers>
          {loadingDefs ? (
            <Stack alignItems="center" sx={{ py: 2 }}><CircularProgress /></Stack>
          ) : definitions.length === 0 ? (
            <Typography>No workflow definitions found at {WORKFLOWS_PATH}.</Typography>
          ) : (
            <List disablePadding>
              {definitions.map(def => (
                <ListItem key={def.path} disablePadding>
                  <ListItemButton onClick={() => loadDefinition(def)}>
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
          <Button onClick={createDefinition} variant="contained" disabled={creating}>
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
