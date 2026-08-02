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

import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { appTheme } from "@iap/frontend-commons/appTheme";
import BpmnEditor from "@iap/workflows/BpmnEditor";

// bpmn-js drives an SVG canvas that jsdom cannot lay out, and the editor only ever talks to it
// through importXML/saveXML/destroy/on. Standing in for the modeler keeps these tests about the
// editor's own behaviour -- the JCR round-trip and the dialogs -- rather than about bpmn-js.
// Hoisted so the stand-in class exists by the time the mocked module is first imported, and so
// the tests and the mock factory share one list of the instances handed to the editor.
const { ModelerMock, modelerInstances } = vi.hoisted(() => {
  class ModelerMock {
    importXML = vi.fn().mockResolvedValue({});
    saveXML = vi.fn().mockResolvedValue({ xml: "<saved/>" });
    destroy = vi.fn();
    on = vi.fn();
    get = vi.fn();

    constructor(public options: unknown) {
      instances.push(this);
    }
  }
  const instances: ModelerMock[] = [];
  return { ModelerMock, modelerInstances: instances };
});

vi.mock("bpmn-js/lib/Modeler", () => ({ default: ModelerMock }));

const WORKFLOW_XML = "<bpmn:definitions/>";

// A /Workflows.deep.json payload holding one definition with one version, plus the kinds of entries
// the editor has to skip: a null, a non-node string, and nodes of unrelated primary types.
const workflowsJson = (overrides: Record<string, unknown> = {}) => ({
  "jcr:primaryType": "sling:Folder",
  danglingEntry: null,
  plainProperty: "not a node",
  unrelated: { "jcr:primaryType": "nt:unstructured" },
  approval: {
    "jcr:primaryType": "wf:WorkflowDefinition",
    title: "Approval",
    notAVersion: { "jcr:primaryType": "nt:unstructured" },
    v1: {
      "jcr:primaryType": "wf:WorkflowVersion",
      version: "1.0",
      description: "First cut",
    },
  },
  ...overrides,
});

const jsonResponse = (body: unknown) => ({
  ok: true,
  status: 200,
  url: "",
  json: () => Promise.resolve(body),
  headers: new Headers(),
}) as unknown as Response;

const postResponse = (init: { ok?: boolean; status?: number; location?: string } = {}) => ({
  ok: init.ok ?? true,
  status: init.status ?? 201,
  url: "",
  headers: new Headers(init.location ? { Location: init.location } : {}),
}) as unknown as Response;

// The diagram lives in an nt:file child, so loading one is a second request returning the raw XML.
const fileResponse = (xml: string) => ({
  ok: true,
  status: 200,
  url: "",
  text: () => Promise.resolve(xml),
  headers: new Headers(),
}) as unknown as Response;

const missingFileResponse = () => ({
  ok: false,
  status: 404,
  url: "",
  text: () => Promise.resolve(""),
  headers: new Headers(),
}) as unknown as Response;

// The part the Sling POST servlet stores as the nt:file child, read back out of a multipart body.
const uploadedBpmn = async (body: FormData) => {
  const part = body.get("./bpmn.xml");
  return part instanceof File ? await part.text() : part;
};

const renderEditor = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <BpmnEditor />
  </ThemeProvider>
);

describe("BpmnEditor", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    modelerInstances.length = 0;
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  // Walks the load dialog as far as the list of versions, which most of the loading tests need.
  const openLoadDialog = async (user: ReturnType<typeof userEvent.setup>, body: unknown = workflowsJson()) => {
    fetchMock.mockResolvedValueOnce(jsonResponse(body));
    await user.click(screen.getByRole("button", { name: "Load" }));
    return screen.getByRole("dialog");
  };

  // An open MUI dialog marks the rest of the app aria-hidden, so the toolbar is unreachable by role
  // until the closing dialog has finished its exit transition and unmounted.
  const waitForDialogToClose = () => waitFor(() => {
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  // Fills in the "New" dialog, leaving each field's current value when not overridden. Pastes
  // rather than types: every keystroke re-renders the dialog around a twelve-row textarea holding
  // the example diagram, which is slow enough under coverage to blow the per-test timeout.
  const fillNewDialog = async (
    user: ReturnType<typeof userEvent.setup>,
    values: { title?: string; description?: string; version?: string; xml?: string; active?: boolean },
  ) => {
    await user.click(screen.getByRole("button", { name: "New" }));
    const dialog = screen.getByRole("dialog");
    const fill = async (name: RegExp, value: string) => {
      const field = within(dialog).getByRole("textbox", { name });
      await user.clear(field);
      if (value) {
        await user.click(field);
        await user.paste(value);
      }
    };
    if (values.title !== undefined) {
      await fill(/Title/, values.title);
    }
    if (values.description !== undefined) {
      await fill(/Description/, values.description);
    }
    if (values.version !== undefined) {
      await fill(/Version/, values.version);
    }
    if (values.xml !== undefined) {
      await fill(/BPMN XML/, values.xml);
    }
    if (values.active) {
      await user.click(within(dialog).getByRole("checkbox", { name: "Active" }));
    }
    return dialog;
  };

  describe("the modeler", () => {
    it("attaches a modeler to its container, and tears it down on unmount", () => {
      const { unmount } = renderEditor();

      expect(modelerInstances).toHaveLength(1);
      const options = modelerInstances[0].options as { container: HTMLElement; height: string };
      expect(options.container).toBeInstanceOf(HTMLElement);
      // Sized against the container's own offset so the canvas fills the remaining viewport
      expect(options.height).toMatch(/^calc\(100vh - \d+px - 100px\)$/);

      unmount();

      expect(modelerInstances[0].destroy).toHaveBeenCalled();
    });

    it("starts with nothing to save", () => {
      renderEditor();

      expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
      expect(screen.queryByText(/Editing:/)).not.toBeInTheDocument();
    });
  });

  describe("loading a definition", () => {
    it("lists every version of every workflow definition, ignoring unrelated nodes", async () => {
      const user = userEvent.setup();
      renderEditor();

      const dialog = await openLoadDialog(user);

      expect(fetchMock).toHaveBeenCalledWith("/Workflows.deep.json", undefined);
      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      expect(within(dialog).getByText("v1.0 · First cut")).toBeInTheDocument();
      expect(within(dialog).getAllByRole("button")).toHaveLength(2); // the one version, plus Cancel
    });

    it("falls back to the definition key and omits absent details", async () => {
      const user = userEvent.setup();
      renderEditor();

      const dialog = await openLoadDialog(user, {
        untitled: {
          "jcr:primaryType": "wf:WorkflowDefinition",
          v1: { "jcr:primaryType": "wf:WorkflowVersion" },
        },
      });

      await waitFor(() => { expect(within(dialog).getByText("untitled")).toBeInTheDocument(); });
      expect(within(dialog).queryByText(/·/)).not.toBeInTheDocument();
    });

    // Without the "deep" selector the serializer answers with the homepage's own properties only,
    // so the dialog would always be empty however many definitions exist.
    it("says so when there is nothing to load", async () => {
      const user = userEvent.setup();
      renderEditor();

      const dialog = await openLoadDialog(user, { "jcr:primaryType": "sling:Folder" });

      await waitFor(() => {
        expect(within(dialog).getByText("No workflow definitions found at /Workflows.")).toBeInTheDocument();
      });
    });

    it("reports a failure to list the definitions", async () => {
      const user = userEvent.setup();
      renderEditor();

      fetchMock.mockRejectedValueOnce(new Error("network down"));
      await user.click(screen.getByRole("button", { name: "Load" }));

      expect(await screen.findByText("Failed to load workflow definitions")).toBeInTheDocument();
    });

    it("reports a request that failed with something other than an Error", async () => {
      const user = userEvent.setup();
      renderEditor();

      fetchMock.mockRejectedValueOnce("connection reset");
      await user.click(screen.getByRole("button", { name: "Load" }));

      expect(await screen.findByText("Failed to load workflow definitions")).toBeInTheDocument();
    });

    it("imports the selected version and starts editing it", async () => {
      const user = userEvent.setup();
      renderEditor();

      const dialog = await openLoadDialog(user);
      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      fetchMock.mockResolvedValueOnce(fileResponse(WORKFLOW_XML));
      await user.click(within(dialog).getByText("Approval"));

      await waitFor(() => { expect(modelerInstances[0].importXML).toHaveBeenCalledWith(WORKFLOW_XML); });
      expect(fetchMock).toHaveBeenLastCalledWith("/Workflows/approval/v1/bpmn.xml", undefined);
      expect(await screen.findByText('Loaded "Approval" v1.0')).toBeInTheDocument();
      expect(screen.getByText("Approval (v1.0)")).toBeInTheDocument();
      await waitForDialogToClose();
      expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
    });

    it("refuses a version that has no diagram saved yet", async () => {
      const user = userEvent.setup();
      renderEditor();

      const dialog = await openLoadDialog(user);
      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      // No nt:file child, so Sling answers the file's path with a 404
      fetchMock.mockResolvedValueOnce(missingFileResponse());
      await user.click(within(dialog).getByText("Approval"));

      expect(await screen.findByText('"Approval" v1.0 has no BPMN XML saved yet')).toBeInTheDocument();
      expect(modelerInstances[0].importXML).not.toHaveBeenCalled();
      await waitForDialogToClose();
    });

    it("reports a diagram the repository refuses to hand over", async () => {
      const user = userEvent.setup();
      renderEditor();

      const dialog = await openLoadDialog(user);
      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      fetchMock.mockResolvedValueOnce({ ok: false, status: 403, url: "", headers: new Headers() } as Response);
      await user.click(within(dialog).getByText("Approval"));

      expect(await screen.findByText("Failed to load the diagram: HTTP 403")).toBeInTheDocument();
      expect(modelerInstances[0].importXML).not.toHaveBeenCalled();
    });

    it("reports a diagram that could not be fetched at all", async () => {
      const user = userEvent.setup();
      renderEditor();

      const dialog = await openLoadDialog(user);
      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      fetchMock.mockRejectedValueOnce(new Error("network down"));
      await user.click(within(dialog).getByText("Approval"));

      expect(await screen.findByText("Failed to load the diagram: network down")).toBeInTheDocument();
      expect(modelerInstances[0].importXML).not.toHaveBeenCalled();
    });

    it("reports a diagram that will not import", async () => {
      const user = userEvent.setup();
      renderEditor();
      modelerInstances[0].importXML.mockRejectedValueOnce(new Error("malformed"));

      const dialog = await openLoadDialog(user);
      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      fetchMock.mockResolvedValueOnce(fileResponse(WORKFLOW_XML));
      await user.click(within(dialog).getByText("Approval"));

      expect(await screen.findByText("Failed to import XML: malformed")).toBeInTheDocument();
    });

    it("reports an import that failed with something other than an Error", async () => {
      const user = userEvent.setup();
      renderEditor();
      // bpmn-js rejects with a plain descriptor in some failure modes
      modelerInstances[0].importXML.mockRejectedValueOnce("not an Error");

      const dialog = await openLoadDialog(user);
      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      fetchMock.mockResolvedValueOnce(fileResponse(WORKFLOW_XML));
      await user.click(within(dialog).getByText("Approval"));

      expect(await screen.findByText("Failed to import XML: not an Error")).toBeInTheDocument();
    });

    it("lists a version that declares neither a number nor a description", async () => {
      const user = userEvent.setup();
      renderEditor();

      const dialog = await openLoadDialog(user, {
        approval: {
          "jcr:primaryType": "wf:WorkflowDefinition",
          title: "Approval",
          v1: { "jcr:primaryType": "wf:WorkflowVersion" },
        },
      });

      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      // A bare "v" is all that is left to show; the entry is still selectable
      expect(within(dialog).getByText("v")).toBeInTheDocument();
    });

    it("can be dismissed without loading anything", async () => {
      const user = userEvent.setup();
      renderEditor();

      const dialog = await openLoadDialog(user);
      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

      await waitForDialogToClose();
      expect(modelerInstances[0].importXML).not.toHaveBeenCalled();
    });

    it("can be dismissed with the escape key", async () => {
      const user = userEvent.setup();
      renderEditor();

      const dialog = await openLoadDialog(user);
      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      await user.keyboard("{Escape}");

      await waitForDialogToClose();
      expect(modelerInstances[0].importXML).not.toHaveBeenCalled();
    });
  });

  describe("saving", () => {
    // Everything here needs a definition open first, since Save is disabled until then.
    const loadApproval = async (user: ReturnType<typeof userEvent.setup>) => {
      const dialog = await openLoadDialog(user);
      await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
      fetchMock.mockResolvedValueOnce(fileResponse(WORKFLOW_XML));
      await user.click(within(dialog).getByText("Approval"));
      await screen.findByText('Loaded "Approval" v1.0');
      await waitForDialogToClose();
    };

    it("posts the serialized diagram back to the version it came from", async () => {
      const user = userEvent.setup();
      renderEditor();
      await loadApproval(user);

      fetchMock.mockResolvedValueOnce(postResponse({ ok: true, status: 200 }));
      await user.click(screen.getByRole("button", { name: "Save" }));

      expect(await screen.findByText('Saved "Approval (v1.0)"')).toBeInTheDocument();
      const [url, init] = fetchMock.mock.calls.at(-1) as [string, RequestInit];
      expect(url).toBe("/Workflows/approval/v1");
      expect(init.method).toBe("POST");
      const body = init.body as FormData;
      expect(await uploadedBpmn(body)).toBe("<saved/>");
      // Without the hint the same part would be stored as a binary property, not an nt:file
      expect(body.get("./bpmn.xml@TypeHint")).toBe("nt:file");
    });

    it("reports a rejected save", async () => {
      const user = userEvent.setup();
      renderEditor();
      await loadApproval(user);

      fetchMock.mockResolvedValueOnce(postResponse({ ok: false, status: 403 }));
      await user.click(screen.getByRole("button", { name: "Save" }));

      expect(await screen.findByText("Save failed: HTTP 403")).toBeInTheDocument();
    });

    it("reports a diagram that will not serialize", async () => {
      const user = userEvent.setup();
      renderEditor();
      await loadApproval(user);

      modelerInstances[0].saveXML.mockResolvedValueOnce({ xml: undefined });
      await user.click(screen.getByRole("button", { name: "Save" }));

      expect(await screen.findByText("Save failed: Failed to serialize BPMN XML")).toBeInTheDocument();
      // The failed save must not have been posted anywhere: only the listing and the diagram it loaded
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });
  });

  describe("creating a definition", () => {
    it("requires a title", async () => {
      const user = userEvent.setup();
      renderEditor();

      await fillNewDialog(user, {});
      await user.click(screen.getByRole("button", { name: "Create" }));

      expect(await screen.findByText("Title is required")).toBeInTheDocument();
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it("requires a version", async () => {
      const user = userEvent.setup();
      renderEditor();

      await fillNewDialog(user, { title: "Consent Review", version: "" });
      await user.click(screen.getByRole("button", { name: "Create" }));

      expect(await screen.findByText("Version is required")).toBeInTheDocument();
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it("creates the definition and its first version, then edits it", async () => {
      const user = userEvent.setup();
      renderEditor();

      await fillNewDialog(user, {
        title: "Consent Review",
        description: "Reviews consent",
        version: "2.1",
        xml: WORKFLOW_XML,
        active: true,
      });
      fetchMock
        .mockResolvedValueOnce(postResponse())
        .mockResolvedValueOnce(postResponse())
        .mockResolvedValueOnce(postResponse({ ok: true, status: 200 }));
      await user.click(screen.getByRole("button", { name: "Create" }));

      expect(await screen.findByText('Created "Consent Review" v2.1')).toBeInTheDocument();

      const [defUrl, defInit] = fetchMock.mock.calls[0] as [string, RequestInit];
      const defBody = defInit.body as URLSearchParams;
      expect(defUrl).toBe("/Workflows/");
      expect(defBody.get("jcr:primaryType")).toBe("wf:WorkflowDefinition");
      // Titles become slugs, so the created node gets a sane JCR name
      expect(defBody.get(":nameHint")).toBe("consent-review");
      expect(defBody.get("title")).toBe("Consent Review");
      expect(defBody.get("active")).toBe("true");
      expect(defBody.get("active@TypeHint")).toBe("Boolean");

      const [versionUrl, versionInit] = fetchMock.mock.calls[1] as [string, RequestInit];
      const versionBody = versionInit.body as URLSearchParams;
      expect(versionUrl).toBe("/Workflows/consent-review/");
      expect(versionBody.get("jcr:primaryType")).toBe("wf:WorkflowVersion");
      expect(versionBody.get(":nameHint")).toBe("2-1");
      expect(versionBody.get("version")).toBe("2.1");
      expect(versionBody.get("description")).toBe("Reviews consent");

      // The diagram follows in a request of its own, onto the version that was just created:
      // sending it with the version's properties would leave a sling:Folder behind, since Sling
      // creates the node a file part implies before it applies jcr:primaryType.
      const [diagramUrl, diagramInit] = fetchMock.mock.calls[2] as [string, RequestInit];
      expect(diagramUrl).toBe("/Workflows/consent-review/2-1");
      expect(await uploadedBpmn(diagramInit.body as FormData)).toBe(WORKFLOW_XML);
      expect((diagramInit.body as FormData).get("./bpmn.xml@TypeHint")).toBe("nt:file");

      // The new version is imported into the canvas and becomes the save target
      expect(modelerInstances[0].importXML).toHaveBeenCalledWith(WORKFLOW_XML);
      expect(screen.getByText("Consent Review (v2.1)")).toBeInTheDocument();
      await waitForDialogToClose();
      expect(screen.getByRole("button", { name: "Save" })).toBeEnabled();
    });

    it("omits an empty description and diagram, and skips the import", async () => {
      const user = userEvent.setup();
      renderEditor();

      await fillNewDialog(user, { title: "Bare", version: "1.0", xml: "" });
      fetchMock
        .mockResolvedValueOnce(postResponse())
        .mockResolvedValueOnce(postResponse());
      await user.click(screen.getByRole("button", { name: "Create" }));

      await screen.findByText('Created "Bare" v1.0');
      const versionBody = (fetchMock.mock.calls[1] as [string, RequestInit])[1].body as URLSearchParams;
      expect(versionBody.has("description")).toBe(false);
      // Nothing to upload, so the definition and the version are the only requests
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(modelerInstances[0].importXML).not.toHaveBeenCalled();
    });

    it("prefers the paths Sling reports over the slugs it guessed", async () => {
      const user = userEvent.setup();
      renderEditor();

      await fillNewDialog(user, { title: "Consent Review", version: "2.1" });
      fetchMock
        .mockResolvedValueOnce(postResponse({ location: "http://localhost:8080/Workflows/consent-review_2" }))
        .mockResolvedValueOnce(postResponse({ location: "/Workflows/consent-review_2/2-1_3" }))
        .mockResolvedValueOnce(postResponse({ ok: true, status: 200 }));
      await user.click(screen.getByRole("button", { name: "Create" }));

      await screen.findByText('Created "Consent Review" v2.1');
      // An absolute Location is reduced to its path, a relative one is taken as-is
      expect((fetchMock.mock.calls[1] as [string])[0]).toBe("/Workflows/consent-review_2/");
      // ...and the diagram goes to the version Sling actually created
      expect((fetchMock.mock.calls[2] as [string])[0]).toBe("/Workflows/consent-review_2/2-1_3");

      await waitForDialogToClose();
      fetchMock.mockResolvedValueOnce(postResponse({ ok: true, status: 200 }));
      await user.click(screen.getByRole("button", { name: "Save" }));

      await screen.findByText('Saved "Consent Review (v2.1)"');
      expect((fetchMock.mock.calls.at(-1) as [string])[0]).toBe("/Workflows/consent-review_2/2-1_3");
    });

    it("takes a relative definition Location as the path it already is", async () => {
      const user = userEvent.setup();
      renderEditor();

      await fillNewDialog(user, { title: "Consent Review", version: "2.1" });
      fetchMock
        .mockResolvedValueOnce(postResponse({ location: "/Workflows/consent-review_4" }))
        .mockResolvedValueOnce(postResponse())
        .mockResolvedValueOnce(postResponse({ ok: true, status: 200 }));
      await user.click(screen.getByRole("button", { name: "Create" }));

      await screen.findByText('Created "Consent Review" v2.1');
      expect((fetchMock.mock.calls[1] as [string])[0]).toBe("/Workflows/consent-review_4/");
    });

    it("reports a rejected definition", async () => {
      const user = userEvent.setup();
      renderEditor();

      await fillNewDialog(user, { title: "Consent Review", version: "1.0" });
      fetchMock.mockResolvedValueOnce(postResponse({ ok: false, status: 409 }));
      await user.click(screen.getByRole("button", { name: "Create" }));

      expect(await screen.findByText("Create failed: HTTP 409")).toBeInTheDocument();
      // The definition failed, so no version should have been attempted
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it("reports a rejected diagram", async () => {
      const user = userEvent.setup();
      renderEditor();

      await fillNewDialog(user, { title: "Consent Review", version: "1.0" });
      fetchMock
        .mockResolvedValueOnce(postResponse())
        .mockResolvedValueOnce(postResponse())
        .mockResolvedValueOnce(postResponse({ ok: false, status: 403 }));
      await user.click(screen.getByRole("button", { name: "Create" }));

      expect(await screen.findByText("Create failed: HTTP 403")).toBeInTheDocument();
      expect(modelerInstances[0].importXML).not.toHaveBeenCalled();
    });

    it("reports a rejected version", async () => {
      const user = userEvent.setup();
      renderEditor();

      await fillNewDialog(user, { title: "Consent Review", version: "1.0" });
      fetchMock
        .mockResolvedValueOnce(postResponse())
        .mockResolvedValueOnce(postResponse({ ok: false, status: 503 }));
      await user.click(screen.getByRole("button", { name: "Create" }));

      expect(await screen.findByText("Create failed: HTTP 503")).toBeInTheDocument();
    });

    it("discards what was typed when cancelled", async () => {
      const user = userEvent.setup();
      renderEditor();

      await fillNewDialog(user, { title: "Abandoned", version: "9.9" });
      await user.click(screen.getByRole("button", { name: "Cancel" }));
      await waitFor(() => { expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); });

      await user.click(screen.getByRole("button", { name: "New" }));
      const dialog = screen.getByRole("dialog");
      expect(within(dialog).getByRole("textbox", { name: /Title/ })).toHaveValue("");
      expect(within(dialog).getByRole("textbox", { name: /Version/ })).toHaveValue("1.0");
      expect(within(dialog).getByRole("checkbox", { name: "Active" })).not.toBeChecked();
    });
  });

  it("lets a message be dismissed", async () => {
    const user = userEvent.setup();
    renderEditor();

    // Via a path that closes its dialog, so the alert's own Close button is not left behind an
    // aria-hidden modal
    const dialog = await openLoadDialog(user);
    await waitFor(() => { expect(within(dialog).getByText("Approval")).toBeInTheDocument(); });
    fetchMock.mockResolvedValueOnce(missingFileResponse());
    await user.click(within(dialog).getByText("Approval"));
    const message = await screen.findByText('"Approval" v1.0 has no BPMN XML saved yet');
    await waitForDialogToClose();

    await user.click(screen.getByRole("button", { name: "Close" }));

    await waitFor(() => { expect(message).not.toBeInTheDocument(); });
  });

  // fetchUtil() treats 401 and 500 as "the session or the server is gone", logs, and then neither
  // resolves nor rejects, so every caller is left awaiting a promise that never settles. These
  // tests pin that down as it stands today; see the note in the commit message.
  describe("a request that never settles", () => {
    it("leaves the editor waiting when the server returns 500", async () => {
      const user = userEvent.setup();
      const log = vi.spyOn(console, "log").mockImplementation(() => { /* keep the output quiet */ });
      renderEditor();

      fetchMock.mockResolvedValueOnce({ ok: false, status: 500, url: "", headers: new Headers() } as unknown as Response);
      await user.click(screen.getByRole("button", { name: "Load" }));

      await waitFor(() => { expect(log).toHaveBeenCalledWith("Error fetching: 500"); });
      // Still spinning: no list, no failure message
      const dialog = screen.getByRole("dialog");
      expect(within(dialog).getByRole("progressbar")).toBeInTheDocument();
      expect(screen.queryByText("Failed to load workflow definitions")).not.toBeInTheDocument();
    });

    it("leaves the editor waiting when the response redirects to the login page", async () => {
      const user = userEvent.setup();
      const log = vi.spyOn(console, "log").mockImplementation(() => { /* keep the output quiet */ });
      renderEditor();

      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        url: `${window.location.origin}/login?resource=/Workflows`,
        headers: new Headers(),
      } as unknown as Response);
      await user.click(screen.getByRole("button", { name: "Load" }));

      await waitFor(() => { expect(log).toHaveBeenCalledWith("Requested relogin"); });
      expect(within(screen.getByRole("dialog")).getByRole("progressbar")).toBeInTheDocument();
    });
  });
});
