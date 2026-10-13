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

import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import ElementProperties from "@iap/workflows/ElementProperties";
import { IAP_NS } from "@iap/workflows/moddle";
import type { ModdleElement } from "@iap/workflows/moddle";
import type { FlowNodeType } from "@iap/workflows/workflowTypes";

import { createCanvas } from "./canvas.fixture";
import { Themed } from "./parameterField.fixture";
import {
  bpmnElement,
  carrying,
  COLOUR,
  iapElement,
  moddleElement,
  parameter,
  RETIRE_ACTIVE_VERSIONS,
  SERVICE_TASK,
  SET_STATE,
  type,
} from "./vocabulary.fixture";

import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type { Element } from "bpmn-js/lib/model/Types";

// Renaming and every parameter edit go through the canvas's `modeling` service, and are read back
// through the document its `moddle` service builds elements with. What is still pending is written
// through its command stack as the selection changes, unless its registry or its events say the
// element has gone.
const createViewer = () => {
  const definitions: ModdleElement = { $attrs: { "xmlns:iap": IAP_NS }, set: vi.fn() };
  const moddle = {
    create: vi.fn((type: string, attributes: Record<string, unknown> = {}) => ({ $type: type, ...attributes })),
    createAny: vi.fn((type: string, uri: string, attributes: Record<string, unknown> = {}) => {
      const [ prefix, name ] = type.split(":");
      return moddleElement(name, { ...attributes, set: vi.fn() }, { prefix, uri });
    }),
  };
  const canvas = createCanvas({ moddle }, definitions);
  return { ...canvas, moddle, definitions, destroy: () => canvas.fire("diagram.destroy") };
};

const VOCABULARY = [ SET_STATE, RETIRE_ACTIVE_VERSIONS, SERVICE_TASK ];

// The `iap:setState` element of a service task, as moddle hands it over: an unprefixed
// attribute for the single value, one namespaced child per value of the list.
const setState = (toState: string, fromStates: string[]): ModdleElement => iapElement("setState", {
  toState,
  $children: fromStates.map(state => iapElement("fromStates", { $body: state })),
  set: vi.fn(),
});

const task = (name: string, extras: Partial<ModdleElement> = {}) =>
  ({ id: "promote", businessObject: bpmnElement("ServiceTask", { name, ...extras }) }) as unknown as Element;

// MUI appends an asterisk to a required field's label, so a parameter that has to be given a value is
// labelled "To state *" rather than "To state".
const field = (label: string) => screen.getByLabelText(new RegExp(`^${label}`));

const noField = (label: string) => screen.queryByLabelText(new RegExp(`^${label}`));

// Rendered for an element on the canvas, as a selected one always is.
const renderProperties = (
  element: Element,
  viewer: BaseViewer,
  props: { readOnly?: boolean; types?: readonly FlowNodeType[]; revision?: number; onDraft?: () => void } = {}
) => {
  viewer.get<{ add: (element: Element) => void }>("elementRegistry").add(element);
  return render(<ElementProperties element={element} viewer={viewer} {...props} />, { wrapper: Themed });
};

describe("ElementProperties", () => {
  it("shows the element's identifier and name", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "Task_1", businessObject: { name: "Review" } } as unknown as Element, viewer);

    expect(screen.getByText("Identifier: Task_1")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toHaveValue("Review");
  });

  it("leaves the name empty for an element that has none", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "Task_1", businessObject: {} } as unknown as Element, viewer);

    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("leaves the name empty for an element with no business object at all", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "Task_1" } as unknown as Element, viewer);

    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("edits an element on a canvas with no document open", () => {
    // Nothing has been imported, so no document says which prefix binds the IAP namespace. The panel
    // reads the one a diagram of ours is saved with.
    const { viewer } = createViewer();
    vi.spyOn(viewer, "getDefinitions").mockReturnValue(undefined);

    renderProperties(task("Promote", { $attrs: { "iap:hostTag": "promoted" } }), viewer,
      { types: VOCABULARY });

    expect(screen.getByText("Identifier: promote")).toBeInTheDocument();
    expect(field("Host tag")).toHaveValue("promoted");
  });

  it("shows the name as text, with nothing to type into, when read-only", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "Task_1", businessObject: { name: "Review" } } as unknown as Element, viewer,
      { readOnly: true });

    expect(screen.getByText("Identifier: Task_1")).toBeInTheDocument();
    expect(screen.getByText("Name: Review")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("renames the element as one step to undo once the name is left", async () => {
    const user = userEvent.setup();
    const { viewer, updateLabel, commandStack, writeThrough } = createViewer();
    writeThrough();
    const element = { id: "Task_1", businessObject: {} } as unknown as Element;
    renderProperties(element, viewer);

    await user.type(screen.getByRole("textbox"), "Hi");
    expect(updateLabel).not.toHaveBeenCalled();
    expect(screen.getByRole("textbox")).toHaveValue("Hi");
    await user.tab();

    expect(updateLabel).toHaveBeenCalledTimes(1);
    expect(updateLabel).toHaveBeenCalledWith(element, "Hi");
    commandStack.undo();
    expect((element.businessObject as ModdleElement).name).toBeUndefined();
    expect(commandStack.canUndo()).toBe(false);
  });

  it("renames the element when Enter is pressed in the name", async () => {
    const user = userEvent.setup();
    const { viewer, updateLabel } = createViewer();
    const element = { id: "Task_1", businessObject: { name: "Review" } } as unknown as Element;
    renderProperties(element, viewer);

    await user.type(screen.getByRole("textbox"), "ed{enter}");

    expect(updateLabel).toHaveBeenCalledTimes(1);
    expect(updateLabel).toHaveBeenCalledWith(element, "Reviewed");
  });

  it("leaves the name alone when it is visited and left unchanged", async () => {
    const user = userEvent.setup();
    const { viewer, updateLabel } = createViewer();
    renderProperties({ id: "Task_1", businessObject: { name: "Review" } } as unknown as Element, viewer);

    await user.click(screen.getByRole("textbox"));
    await user.tab();

    expect(updateLabel).not.toHaveBeenCalled();
  });

  it("offers the name alone for an element the vocabulary does not describe", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "Flow_1", businessObject: { $type: "bpmn:sequenceFlow" } } as unknown as Element,
      viewer, { types: VOCABULARY });

    expect(screen.getByText("Identifier: Flow_1")).toBeInTheDocument();
    expect(screen.getAllByRole("textbox")).toHaveLength(1);
    expect(noField("Type")).not.toBeInTheDocument();
  });

  it("offers the name alone while the vocabulary has not arrived", () => {
    const { viewer } = createViewer();

    renderProperties(task("Make this the active version", carrying(setState("ACTIVE", [ "DRAFT" ]))), viewer);

    expect(screen.getAllByRole("textbox")).toHaveLength(1);
    expect(noField("To state")).not.toBeInTheDocument();
  });

  it("shows every parameter of the kind the element means, with what the diagram holds", () => {
    const { viewer } = createViewer();

    renderProperties(task("Promote", {
      "iap:hostTag": undefined,
      "$attrs": { "iap:hostTag": "promoted" },
      ...carrying(setState("ACTIVE", [ "DRAFT", "TRIAL" ])),
    }), viewer, { types: VOCABULARY });

    expect(field("Host tag")).toHaveValue("promoted");
    expect(field("To state")).toHaveValue("ACTIVE");
    expect(screen.getByText("DRAFT")).toBeInTheDocument();
    expect(screen.getByText("TRIAL")).toBeInTheDocument();
  });

  it("reports the kind of node as text when there is only one it could be", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "start", businessObject: bpmnElement("StartEvent", { name: "Kick off" }) } as unknown as Element,
      viewer, { types: [ type("StartEvent", { label: "Start event", xmlElement: "bpmn:startEvent" }) ] });

    expect(screen.getByText("Type: Start event")).toBeInTheDocument();
    expect(noField("Type")).not.toBeInTheDocument();
  });

  it("offers a choice of kinds when the element could be read as several", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties, moddle } = createViewer();
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    renderProperties(element, viewer, { types: VOCABULARY });
    expect(field("Type")).toHaveTextContent("Set state");

    await user.click(field("Type"));
    await user.click(screen.getByRole("option", { name: "Retire active versions" }));

    // The element naming the kind being taken over arrives; the one naming the kind left behind goes
    expect(moddle.createAny).toHaveBeenCalledWith("iap:retireActiveVersions", IAP_NS, {});
    expect(updateModdleProperties).toHaveBeenCalledTimes(1);
    const [ reported, target, properties ] = updateModdleProperties.mock.calls[0] as unknown as
      [ Element, ModdleElement, Record<string, ModdleElement> ];
    expect(reported).toBe(element);
    expect(target).toBe(element.businessObject);
    expect(properties.extensionElements.values?.map(value => value.$type)).toEqual([ "iap:retireActiveVersions" ]);
  });

  it("shows no kind at all for an element that matches none of the kinds it could be", () => {
    const { viewer } = createViewer();

    // Both candidates name an extension element, and the element carries neither
    renderProperties(task("Promote"), viewer, { types: [ SET_STATE, RETIRE_ACTIVE_VERSIONS ] });

    const chosen = field("Type");
    expect(chosen).not.toHaveTextContent("Set state");
    expect(chosen).not.toHaveTextContent("Retire active versions");
    expect(noField("To state")).not.toBeInTheDocument();
  });

  it("leaves the element alone when the choice is left at none", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties } = createViewer();
    renderProperties(task("Promote"), viewer, { types: [ SET_STATE, RETIRE_ACTIVE_VERSIONS ] });

    await user.click(field("Type"));
    await user.click(screen.getByRole("option", { name: "None" }));

    expect(updateModdleProperties).not.toHaveBeenCalled();
  });

  it("writes one edit per value typed, not one per keystroke", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties } = createViewer();
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    renderProperties(element, viewer, { types: VOCABULARY });

    await user.type(field("Host tag"), "promoted");
    expect(updateModdleProperties).not.toHaveBeenCalled();
    expect(field("Host tag")).toHaveValue("promoted");
    await user.keyboard("{enter}");

    expect(updateModdleProperties).toHaveBeenCalledTimes(1);
    expect(updateModdleProperties)
      .toHaveBeenCalledWith(element, element.businessObject, { "iap:hostTag": "promoted" });
  });

  it("says something is being typed before any of it is written", async () => {
    // Nothing reaches the command stack until the field is left, so this is the only sign of a change
    const user = userEvent.setup();
    const { viewer, updateModdleProperties } = createViewer();
    const onDraft = vi.fn();
    renderProperties(task("Promote", carrying(setState("ACTIVE", []))), viewer, { types: VOCABULARY, onDraft });

    await user.type(screen.getByDisplayValue("Promote"), "d");
    expect(onDraft).toHaveBeenCalledTimes(1);
    await user.type(field("Colour"), "p");

    expect(onDraft).toHaveBeenCalledTimes(2);
    expect(updateModdleProperties).not.toHaveBeenCalled();
  });

  it("writes nothing for a field visited and left unchanged", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties } = createViewer();
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    renderProperties(element, viewer, { types: VOCABULARY });

    await user.click(field("To state"));
    await user.tab();

    expect(updateModdleProperties).not.toHaveBeenCalled();
  });

  it("shows a described element's parameters as text when read-only", () => {
    const { viewer } = createViewer();

    renderProperties(task("Promote", {
      "$attrs": { "iap:hostTag": "promoted" },
      ...carrying(setState("ACTIVE", [ "DRAFT", "TRIAL" ])),
    }), viewer, { types: VOCABULARY, readOnly: true });

    expect(screen.getByText("Type: Set state")).toBeInTheDocument();
    expect(screen.getByText("Host tag: promoted")).toBeInTheDocument();
    expect(screen.getByText("To state: ACTIVE")).toBeInTheDocument();
    expect(screen.getByText("From states: DRAFT, TRIAL")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("rereads every value when the canvas reports the element as changed", () => {
    const { viewer } = createViewer();
    const extension = setState("ACTIVE", [ "DRAFT" ]);
    const element = task("Promote", {
      "$attrs": { "iap:hostTag": "promoted" },
      ...carrying(extension),
    });
    const { rerender } = renderProperties(element, viewer, { types: VOCABULARY, revision: 1 });

    // An undo, or an edit made elsewhere
    extension.toState = "TRIAL";
    (element.businessObject as ModdleElement).$attrs = { "iap:hostTag": "retired" };
    rerender(<ElementProperties element={element} viewer={viewer} types={VOCABULARY} revision={2} />);

    expect(field("To state")).toHaveValue("TRIAL");
    expect(field("Host tag")).toHaveValue("retired");
  });

  it("keeps a space being typed when the canvas reports the panel's own edit", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties } = createViewer();
    // Written into the diagram the way modeling would, so the reread sees what was just typed
    updateModdleProperties.mockImplementation(
      (_element: Element, target: ModdleElement, properties: Record<string, string | undefined>) => {
        target.$attrs = { ...target.$attrs, ...properties };
      });
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    const { rerender } = renderProperties(element, viewer, { types: VOCABULARY, revision: 1 });
    const reportChange = (revision: number) =>
      rerender(<ElementProperties element={element} viewer={viewer} types={VOCABULARY} revision={revision} />);

    await user.type(field("Host tag"), "in {enter}");
    reportChange(2);
    await user.type(field("Host tag"), "review{enter}");
    reportChange(3);

    expect(field("Host tag")).toHaveValue("in review");
    expect(updateModdleProperties).toHaveBeenLastCalledWith(element, element.businessObject,
      { "iap:hostTag": "in review" });
  });

  it("fills in the fields when the vocabulary arrives after the element was selected", () => {
    const { viewer } = createViewer();
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    const { rerender } = renderProperties(element, viewer);
    expect(noField("To state")).not.toBeInTheDocument();

    rerender(<ElementProperties element={element} viewer={viewer} types={VOCABULARY} />);

    expect(field("To state")).toHaveValue("ACTIVE");
  });

  it("writes nothing for a value whose BPMN element the node does not carry", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties } = createViewer();
    const duration = parameter("timeDuration", {
      label: "Duration", xmlLocation: "bpmnElement", xmlPath: [ "timerEventDefinition" ],
    });
    renderProperties(task("Promote"), viewer, { types: [ type("Timed", { parameters: [ duration ] }) ] });

    await user.type(field("Duration"), "P5D{enter}");

    expect(updateModdleProperties).not.toHaveBeenCalled();
  });

  it("writes a structure's field once it is left, and nothing when it is left unchanged", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties } = createViewer();
    const target = parameter("target", {
      label: "Target", dataType: "structure", parameters: [ parameter("path", { label: "Path" }) ],
    });
    const record = iapElement("target", { path: "/a" });
    const configure = iapElement("configure", { $children: [ record ] });
    const element = task("Configure", carrying(configure));
    renderProperties(element, viewer,
      { types: [ type("Configure", { xmlExtensionElement: "configure", parameters: [ target ] }) ] });

    await user.click(await screen.findByLabelText("Path"));
    await user.tab();
    expect(updateModdleProperties).not.toHaveBeenCalled();
    await user.type(screen.getByLabelText("Path"), "b{enter}");

    expect(updateModdleProperties).toHaveBeenCalledTimes(1);
    expect(updateModdleProperties).toHaveBeenCalledWith(element, record, { path: "/ab" });
  });

  it("writes nothing for a field the element no longer has after an undo, rather than bringing it back", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties } = createViewer();
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    renderProperties(element, viewer, { types: VOCABULARY });

    await user.type(field("To state"), "D");
    // The kind changed back, and the panel has not been told yet
    (element.businessObject as ModdleElement).extensionElements = { values: [] };
    await user.tab();

    expect(updateModdleProperties).not.toHaveBeenCalled();
  });
});

describe("ElementProperties when the selection changes mid-edit", () => {
  // As selecting another element does, without the field being left first
  it("writes the name still being typed", async () => {
    const user = userEvent.setup();
    const { viewer, updateLabel, fire } = createViewer();
    const element = { id: "Task_1", businessObject: {} } as unknown as Element;
    renderProperties(element, viewer);

    await user.type(screen.getByRole("textbox"), "Hi");
    await fire("selection.changed", { newSelection: [] });

    expect(updateLabel).toHaveBeenCalledTimes(1);
    expect(updateLabel).toHaveBeenCalledWith(element, "Hi");
  });

  it("writes the text and the whole number still being typed", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties, fire } = createViewer();
    const retries = parameter("retries", { label: "Retries", dataType: "long", xmlLocation: "attribute" });
    const element = task("Retry");
    renderProperties(element, viewer, { types: [ type("Retrying", { parameters: [ COLOUR, retries ] }) ] });

    await user.type(field("Colour"), "red");
    await user.type(field("Retries"), "3");
    await fire("selection.changed", { newSelection: [] });

    expect(updateModdleProperties).toHaveBeenCalledTimes(2);
    expect(updateModdleProperties).toHaveBeenCalledWith(element, element.businessObject, { "iap:colour": "red" });
    expect(updateModdleProperties).toHaveBeenCalledWith(element, element.businessObject, { "iap:retries": "3" });
  });

  it("writes everything pending as one step to undo", async () => {
    const { viewer, commandStack, writeThrough, fire } = createViewer();
    writeThrough();
    const element = task("Promote");
    renderProperties(element, viewer, { types: [ type("Colourful", { parameters: [ COLOUR ] }) ] });

    // Leaving a field writes it, so both are typed into without either being left
    fireEvent.change(screen.getAllByRole("textbox")[0], { target: { value: "Promoted" } });
    fireEvent.change(field("Colour"), { target: { value: "red" } });
    await fire("selection.changed", { newSelection: [] });
    const businessObject = element.businessObject as ModdleElement;
    expect(businessObject.name).toBe("Promoted");
    expect(businessObject.$attrs?.["iap:colour"]).toBe("red");

    commandStack.undo();

    expect(businessObject.name).toBe("Promote");
    expect(businessObject.$attrs?.["iap:colour"]).toBeUndefined();
    expect(commandStack.canUndo()).toBe(false);
  });

  it("writes nothing when nothing is pending", async () => {
    const user = userEvent.setup();
    const { viewer, updateLabel, updateModdleProperties, fire } = createViewer();
    renderProperties(task("Promote", carrying(setState("ACTIVE", []))), viewer, { types: VOCABULARY });

    await user.type(field("To state"), "D{enter}");
    updateModdleProperties.mockClear();
    await fire("selection.changed", { newSelection: [] });

    expect(updateLabel).not.toHaveBeenCalled();
    expect(updateModdleProperties).not.toHaveBeenCalled();
  });

  it("writes nothing once the panel has gone without the selection changing", async () => {
    // As it does when the canvas is replaced, which is not the one the draft belongs to
    const user = userEvent.setup();
    const { viewer, updateLabel, fire } = createViewer();
    const { unmount } = renderProperties({ id: "Task_1", businessObject: {} } as unknown as Element, viewer);

    await user.type(screen.getByRole("textbox"), "Hi");
    unmount();
    await fire("selection.changed", { newSelection: [] });

    expect(updateLabel).not.toHaveBeenCalled();
  });

  it.each([
    [ "destroyed", (canvas: ReturnType<typeof createViewer>) => canvas.fire("diagram.destroy") ],
    [ "cleared for another diagram", (canvas: ReturnType<typeof createViewer>) => canvas.fire("diagram.clear") ],
    [ "rid of the element", (canvas: ReturnType<typeof createViewer>, element: Element) =>
      canvas.fire("shape.remove", { element }) ],
    [ "no longer holding the element", (canvas: ReturnType<typeof createViewer>) => {
      canvas.registered.clear();
      return Promise.resolve();
    } ],
  ])("writes nothing into a canvas %s", async (_case, leave) => {
    const user = userEvent.setup();
    const canvas = createViewer();
    const element = { id: "Task_1", businessObject: {} } as unknown as Element;
    renderProperties(element, canvas.viewer);

    await user.type(screen.getByRole("textbox"), "Hi");
    await leave(canvas, element);
    await canvas.fire("selection.changed", { newSelection: [] });

    expect(canvas.updateLabel).not.toHaveBeenCalled();
  });

  it("writes the name of an element other than one removed", async () => {
    const user = userEvent.setup();
    const { viewer, updateLabel, fire } = createViewer();
    const element = { id: "Task_1", businessObject: {} } as unknown as Element;
    renderProperties(element, viewer);

    await user.type(screen.getByRole("textbox"), "Hi");
    await fire("connection.remove", { element: { id: "Flow_1" } });
    await fire("selection.changed", { newSelection: [] });

    expect(updateLabel).toHaveBeenCalledWith(element, "Hi");
  });

  it("writes the name of the element that replaced the one removed under the same id", async () => {
    const user = userEvent.setup();
    const { viewer, updateLabel, fire } = createViewer();
    const removed = { id: "Task_1", businessObject: { name: "Task" } } as unknown as Element;
    const { rerender } = renderProperties(removed, viewer);
    const replacement = { id: "Task_1", businessObject: { name: "Task" } } as unknown as Element;

    // As changing an element's type on the canvas does
    await fire("shape.remove", { element: removed });
    viewer.get<{ add: (element: Element) => void }>("elementRegistry").add(replacement);
    rerender(<ElementProperties element={replacement} viewer={viewer} />);
    await user.type(screen.getByRole("textbox"), "!");
    await fire("selection.changed", { newSelection: [] });

    expect(updateLabel).toHaveBeenCalledTimes(1);
    expect(updateLabel).toHaveBeenCalledWith(replacement, "Task!");
  });

  it("writes nothing for a field of the entry the element meant before an undo", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties, fire } = createViewer();
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    renderProperties(element, viewer, { types: VOCABULARY });

    await user.type(field("To state"), "D");
    (element.businessObject as ModdleElement).extensionElements = { values: [] };
    await fire("selection.changed", { newSelection: [] });

    expect(updateModdleProperties).not.toHaveBeenCalled();
  });
});
