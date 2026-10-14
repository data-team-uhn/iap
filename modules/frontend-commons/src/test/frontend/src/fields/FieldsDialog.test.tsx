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

import { type ComponentProps } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import FieldsDialog from "@iap/frontend-commons/fields/FieldsDialog";
import type { ContentField, SerializedNode } from "@iap/frontend-commons/fields/fieldsModel";

const field = (name: string, label: string, extra: Partial<ContentField> = {}): ContentField =>
  ({ name, label, kind: "text", multiple: false, mandatory: false, multiline: false, ...extra });

const QUESTION: ContentField[] = [
  field("text", "Question", { mandatory: true, multiline: true, help: "What the submitter reads." }),
  field("dataType", "Answer type", {
    mandatory: true, choices: [ { value: "text", label: "Text" }, { value: "long", label: "Whole number" } ],
  }),
  field("maxAnswers", "Most answers", { kind: "long", help: "0 means unlimited." }),
  field("minValue", "Lowest value", { kind: "double", appliesWhen: { property: "dataType", values: [ "long" ] } }),
  field("displayMode", "Shown as", { choices: [ { value: "list", label: "List" } ] }),
  field("required", "Required", { kind: "boolean", help: "Whether it must be given." }),
  field("done", "Done", { kind: "boolean" }),
  field("rubricTags", "Rubric tags", { multiple: true }),
  field("formats", "Formats", { multiple: true, choices: [ { value: "pdf", label: "PDF" }, { value: "doc", label: "Word" } ] }),
];

const QUESTION_NODE: SerializedNode = {
  "@fields": QUESTION, "text": "Age", "dataType": "text", "maxAnswers": 1, "displayMode": "matrix",
  "rubricTags": [ "age" ], "formats": [ "pdf" ],
};

const WORKFLOW = field("workflow", "Workflow", {
  kind: "reference", referenceType: "wf/WorkflowVersion", referenceRoot: "/Workflows",
});

const response = (body: unknown, status = 200) =>
  Promise.resolve({ ok: status < 400, status, url: "/search.json", json: () => Promise.resolve(body) } as unknown as Response);

const serve = (answer: () => Promise<Response>) => {
  const fetch = vi.fn<(url: string) => Promise<Response>>(answer);
  vi.stubGlobal("fetch", fetch);
  return fetch;
};

const renderDialog = (node: SerializedNode, onSave = vi.fn().mockResolvedValue(undefined), extra?: string,
  props: Partial<ComponentProps<typeof FieldsDialog>> = {}) => {
  const onClose = vi.fn();
  const view = render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <FieldsDialog title="Edit question" node={node} onSave={onSave} onClose={onClose} {...props}>
        {extra}
      </FieldsDialog>
    </ThemeProvider>
  );
  return { onSave, onClose, dialog: screen.getByRole("dialog"), ...view };
};

const save = (dialog: HTMLElement) => fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

const pick = async (dialog: HTMLElement, label: string, option: string) => {
  fireEvent.mouseDown(within(dialog).getByRole("combobox", { name: label }));
  fireEvent.click(await screen.findByRole("option", { name: option }));
};

afterEach(() => vi.unstubAllGlobals());

describe("FieldsDialog", () => {
  it("offers an input for each field that applies, with its help", () => {
    const { dialog } = renderDialog(QUESTION_NODE);

    expect(within(dialog).getByRole("textbox", { name: "Question" })).toHaveValue("Age");
    expect(within(dialog).getByText("What the submitter reads.")).toBeInTheDocument();
    expect(within(dialog).getByRole("combobox", { name: "Answer type" })).toHaveTextContent("Text");
    expect(within(dialog).getByRole("textbox", { name: "Most answers" })).toHaveAttribute("inputmode", "numeric");
    expect(within(dialog).queryByLabelText("Lowest value")).not.toBeInTheDocument();
    expect(within(dialog).getByRole("switch", { name: "Required" })).not.toBeChecked();
    expect(within(dialog).getByText("Whether it must be given.")).toBeInTheDocument();
    expect(within(dialog).getByText("age")).toBeInTheDocument();
    expect(within(dialog).getByText("PDF")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("shows what the caller asks along with the fields", () => {
    const { dialog } = renderDialog(QUESTION_NODE, undefined, "Where it goes");

    expect(within(dialog).getByText("Where it goes")).toBeInTheDocument();
  });

  it("shows what belongs with the first field right after it, as it is entered", () => {
    const { dialog } = renderDialog(QUESTION_NODE, undefined, undefined, {
      afterFirstField: (value, working) => <span>{`Named after ${String(value)}${working ? ", saving" : ""}`}</span>,
    });
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Question" }), { target: { value: "Your age" } });

    const after = within(dialog).getByText("Named after Your age");
    // Between the first field and the second
    expect(within(dialog).getByRole("textbox", { name: "Question" }).compareDocumentPosition(after))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(after.compareDocumentPosition(within(dialog).getByRole("combobox", { name: "Answer type" })))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    // And told when a save is under way
    save(dialog);
    expect(within(dialog).getByText("Named after Your age, saving")).toBeInTheDocument();
  });

  it("shows a field once what it depends on allows it", async () => {
    const { dialog, onSave } = renderDialog(QUESTION_NODE);

    await pick(dialog, "Answer type", "Whole number");
    const bound = within(dialog).getByRole("textbox", { name: "Lowest value" });
    expect(bound).toHaveAttribute("inputmode", "decimal");
    fireEvent.change(bound, { target: { value: "18.5" } });
    save(dialog);

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ dataType: "long", minValue: 18.5 }));
  });

  it("keeps a save back while a value is not of the field's kind", () => {
    const { dialog } = renderDialog(QUESTION_NODE);

    fireEvent.change(within(dialog).getByRole("textbox", { name: "Most answers" }), { target: { value: "many" } });

    expect(within(dialog).getByText("Enter a whole number.")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("keeps a value no longer among the choices, and lets an optional one be cleared", async () => {
    const { dialog, onSave } = renderDialog(QUESTION_NODE);

    expect(within(dialog).getByRole("combobox", { name: "Shown as" })).toHaveTextContent("matrix");
    await pick(dialog, "Shown as", "None");
    save(dialog);

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ displayMode: null }));
  });

  it("sends switches, typed lists and picked lists as they are edited", async () => {
    const { dialog, onSave, onClose } = renderDialog(QUESTION_NODE);

    fireEvent.click(within(dialog).getByRole("switch", { name: "Required" }));
    const tags = within(dialog).getByRole("combobox", { name: "Rubric tags" });
    fireEvent.change(tags, { target: { value: "adult" } });
    fireEvent.keyDown(tags, { key: "Enter" });
    await pick(dialog, "Formats", "Word");
    save(dialog);

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({
      required: true, rubricTags: [ "age", "adult" ], formats: [ "pdf", "doc" ],
    }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("reports a refused save and stays open", async () => {
    const { dialog, onClose } = renderDialog(QUESTION_NODE, vi.fn().mockRejectedValue(new Error("Refused")));

    fireEvent.change(within(dialog).getByRole("textbox", { name: "Question" }), { target: { value: "Age?" } });
    save(dialog);
    expect(within(dialog).getByRole("button", { name: "Saving…" })).toBeDisabled();

    expect(await within(dialog).findByText("Refused")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("closes without saving when cancelled", () => {
    const { dialog, onSave, onClose } = renderDialog(QUESTION_NODE);

    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalled();
    expect(onSave).not.toHaveBeenCalled();
  });

  it("points a reference at one of the nodes it may", async () => {
    const fetch = serve(() => response({ rows: [
      { "@path": "/Workflows/review/v1" }, { "@path": "/Workflows/fast/v2", "title": "Fast track" }, { "title": "?" },
    ] }));
    const { dialog, onSave } = renderDialog({
      "@fields": [ WORKFLOW ], "workflow": { "@path": "/SystemWorkflows/old/v1" },
    });

    expect(within(dialog).getByRole("combobox", { name: "Workflow" })).toHaveValue("/SystemWorkflows/old/v1");
    fireEvent.mouseDown(within(dialog).getByRole("combobox", { name: "Workflow" }));
    await screen.findByRole("option", { name: "Fast track" });
    const options = screen.getAllByRole("option");
    expect(options.map(option => option.textContent)).toEqual([ "Fast track", "review/v1", "/SystemWorkflows/old/v1" ]);
    fireEvent.click(options[0]);
    save(dialog);

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ workflow: "/Workflows/fast/v2" }));
    expect(new URL(fetch.mock.calls[0][0], "http://localhost").searchParams.get("query"))
      .toContain("isdescendantnode(n, '/Workflows')");
  });

  it("points a reference naming several nodes, or none", async () => {
    serve(() => response({ rows: [ { "@path": "/Workflows/review/v1" } ] }));
    const { dialog, onSave } = renderDialog({ "@fields": [ { ...WORKFLOW, multiple: true } ] });

    await pick(dialog, "Workflow", "review/v1");
    save(dialog);

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ workflow: [ "/Workflows/review/v1" ] }));
  });

  it("takes any path for a reference naming no type", async () => {
    const fetch = serve(() => response({}));
    const { dialog, onSave } = renderDialog({ "@fields": [ field("link", "Link", { kind: "reference" }) ] });

    const link = within(dialog).getByRole("combobox", { name: "Link" });
    fireEvent.change(link, { target: { value: "/Anywhere" } });
    fireEvent.keyDown(link, { key: "Enter" });
    save(dialog);

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ link: "/Anywhere" }));
    expect(fetch).not.toHaveBeenCalled();
  });

  it("clears a reference", async () => {
    serve(() => response({ rows: [ { "@path": "/Workflows/review/v1" } ] }));
    const { dialog, onSave } = renderDialog({ "@fields": [ WORKFLOW ], "workflow": "/Workflows/review/v1" });

    fireEvent.change(within(dialog).getByRole("combobox", { name: "Workflow" }), { target: { value: "" } });
    save(dialog);

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ workflow: null }));
  });

  it("offers nothing when no node may be pointed at", async () => {
    serve(() => response({}));
    const { dialog } = renderDialog({ "@fields": [ WORKFLOW ] });

    fireEvent.mouseDown(within(dialog).getByRole("combobox", { name: "Workflow" }));

    expect(await screen.findByText("No options")).toBeInTheDocument();
  });

  it("says when the nodes a reference may point at cannot be read", async () => {
    serve(() => response({}, 403));
    const { dialog } = renderDialog({ "@fields": [ WORKFLOW ] });

    expect(await within(dialog).findByText(/permission/)).toBeInTheDocument();
  });

  it("stops listening for the nodes once it is gone", async () => {
    let answer: (value: Response) => void = () => undefined;
    let fail: (error: Error) => void = () => undefined;
    serve(() => new Promise<Response>(resolve => { answer = resolve; }));
    renderDialog({ "@fields": [ WORKFLOW ] }).unmount();
    answer({ ok: true, status: 200, url: "/search.json", json: () => Promise.resolve({ rows: [] }) } as unknown as Response);

    serve(() => new Promise<Response>((_resolve, reject) => { fail = reject; }));
    renderDialog({ "@fields": [ WORKFLOW ] }).unmount();
    fail(new Error("gone"));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});
