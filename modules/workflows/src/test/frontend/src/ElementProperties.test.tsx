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

import { fireEvent, render, screen, within } from "@testing-library/react";
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
  const bpmnReplace = { replaceElement: vi.fn() };
  const canvas = createCanvas({ moddle, bpmnReplace }, definitions);
  return { ...canvas, moddle, bpmnReplace, definitions, destroy: () => canvas.fire("diagram.destroy") };
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

    expect(screen.getByText("Identifier")).toBeInTheDocument();
    expect(screen.getByText("Task_1")).toBeInTheDocument();
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
    // No document is open, so nothing says which prefix binds the IAP namespace.
    const { viewer } = createViewer();
    vi.spyOn(viewer, "getDefinitions").mockReturnValue(undefined);

    renderProperties(task("Promote", { $attrs: { "iap:colour": "promoted" } }), viewer,
      { types: VOCABULARY });

    expect(screen.getByText("Identifier")).toBeInTheDocument();
    expect(screen.getByText("promote")).toBeInTheDocument();
    expect(field("Colour")).toHaveValue("promoted");
  });

  it("shows the name as text, with nothing to type into, when read-only", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "Task_1", businessObject: { name: "Review" } } as unknown as Element, viewer,
      { readOnly: true });

    expect(screen.getByText("Identifier")).toBeInTheDocument();
    expect(screen.getByText("Task_1")).toBeInTheDocument();
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Review")).toBeInTheDocument();
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

    expect(screen.getByText("Identifier")).toBeInTheDocument();
    expect(screen.getByText("Flow_1")).toBeInTheDocument();
    expect(screen.getAllByRole("textbox")).toHaveLength(1);
    expect(noField("Element Type")).not.toBeInTheDocument();
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
      "iap:colour": undefined,
      "$attrs": { "iap:colour": "promoted" },
      ...carrying(setState("ACTIVE", [ "DRAFT", "TRIAL" ])),
    }), viewer, { types: VOCABULARY });

    expect(field("Colour")).toHaveValue("promoted");
    expect(field("To state")).toHaveValue("ACTIVE");
    expect(screen.getByText("DRAFT")).toBeInTheDocument();
    expect(screen.getByText("TRIAL")).toBeInTheDocument();
  });

  it("reports the element type as text when there is only one it could be", () => {
    const { viewer } = createViewer();

    renderProperties({ id: "start", businessObject: bpmnElement("StartEvent", { name: "Kick off" }) } as unknown as Element,
      viewer, { types: [ type("StartEvent", { xmlElement: "bpmn:startEvent", category: [ "Start Event" ] }) ] });

    expect(screen.getByText("Element Type")).toBeInTheDocument();
    expect(screen.getByText("Start Event")).toBeInTheDocument();
    expect(noField("Element Type")).not.toBeInTheDocument();
    expect(noField("Start Event Type")).not.toBeInTheDocument();
  });

  it("lists the entries of an element type under their groups when there are several", async () => {
    const user = userEvent.setup();
    const { viewer } = createViewer();
    const grouped = { ...SET_STATE, category: [ "Service Task", "Workflows" ] };
    renderProperties(task("Promote", carrying(setState("ACTIVE", []))), viewer, { types: [ SERVICE_TASK, grouped ] });

    await user.click(field("Service Task Type"));

    expect(within(screen.getByRole("listbox")).getByText("Workflows")).toBeInTheDocument();
  });

  it("offers a choice of entries within the element type when the element could be read as several", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties, moddle, bpmnReplace } = createViewer();
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    renderProperties(element, viewer, { types: VOCABULARY });
    expect(field("Service Task Type")).toHaveValue("Set state");

    await user.click(field("Service Task Type"));
    await user.click(screen.getByRole("option", { name: "Retire active versions" }));

    // The element naming the entry being taken over arrives; the one naming the entry left behind goes
    expect(bpmnReplace.replaceElement).not.toHaveBeenCalled();
    expect(moddle.createAny).toHaveBeenCalledWith("iap:retireActiveVersions", IAP_NS, {});
    expect(updateModdleProperties).toHaveBeenCalledTimes(1);
    const [ reported, target, properties ] = updateModdleProperties.mock.calls[0] as unknown as
      [ Element, ModdleElement, Record<string, ModdleElement> ];
    expect(reported).toBe(element);
    expect(target).toBe(element.businessObject);
    expect(properties.extensionElements.values?.map(value => value.$type)).toEqual([ "iap:retireActiveVersions" ]);
  });

  it("says an element matching none of the entries is not recognised, and that the server skips it", () => {
    const { viewer } = createViewer();

    // Both candidates name an extension element, and the element carries neither
    renderProperties(task("Promote"), viewer, { types: [ SET_STATE, RETIRE_ACTIVE_VERSIONS ] });

    expect(screen.getByText("Not recognised (serviceTask)")).toBeInTheDocument();
    expect(screen.getByText("The server skips this element when it reads the diagram.")).toBeInTheDocument();
    expect(noField("Service Task Type")).not.toBeInTheDocument();
    expect(noField("To state")).not.toBeInTheDocument();
  });

  it("names the BPMN element while there is no vocabulary to say more", () => {
    const { viewer } = createViewer();

    renderProperties(task("Promote"), viewer);

    expect(screen.getByText("Element Type")).toBeInTheDocument();
    expect(screen.getByText("serviceTask")).toBeInTheDocument();
    expect(screen.queryByText(/Not recognised/)).not.toBeInTheDocument();
  });

  describe("changing the element type", () => {
    const entry = (name: string, xmlElement: string, category: string[], extra: Partial<FlowNodeType> = {}) =>
      type(name, { label: name, xmlElement: `bpmn:${xmlElement}`, category, priority: 0, ...extra });
    const START = entry("StartEvent", "startEvent", [ "Start Event" ], { jcrNodeType: "wf:StartEvent" });
    const TIMER = entry("TimerStart", "startEvent", [ "Start Event" ],
      { jcrNodeType: "wf:StartEvent", xmlChildElement: "bpmn:timerEventDefinition", priority: 10 });
    const TASK = entry("Task", "task", [ "Task" ]);
    const BOUNDARY = entry("TimerBoundary", "boundaryEvent", [ "Boundary Event" ],
      { xmlChildElement: "bpmn:timerEventDefinition" });
    const ENTRIES = [ SERVICE_TASK, SET_STATE, START, TIMER, TASK, BOUNDARY ];

    const taskElement = () =>
      ({ id: "t", businessObject: bpmnElement("Task", { name: "Do" }) }) as unknown as Element;

    it("redraws the element as the default entry of the element type chosen", async () => {
      const user = userEvent.setup();
      const { viewer, bpmnReplace, updateModdleProperties } = createViewer();
      const replaced = { id: "t", businessObject: bpmnElement("ServiceTask", { name: "Do" }) } as unknown as Element;
      bpmnReplace.replaceElement.mockReturnValue(replaced);
      const element = taskElement();
      renderProperties(element, viewer, { types: ENTRIES });

      await user.click(field("Element Type"));
      await user.click(screen.getByRole("option", { name: "Service Task" }));

      expect(bpmnReplace.replaceElement)
        .toHaveBeenCalledWith(element, { type: "bpmn:ServiceTask", eventDefinitionType: undefined });
      expect(updateModdleProperties).toHaveBeenCalledWith(replaced, replaced.businessObject, expect.anything());
    });

    it("lists the element types it can change to, and none it cannot", async () => {
      const user = userEvent.setup();
      const { viewer } = createViewer();
      renderProperties(taskElement(), viewer, { types: ENTRIES });

      await user.click(field("Element Type"));

      expect(screen.getAllByRole("option").map(option => option.textContent))
        .toEqual([ "Service Task", "Start Event", "Task" ]);
    });

    it("redraws the element, with its event, when the entry chosen needs one", async () => {
      const user = userEvent.setup();
      const { viewer, bpmnReplace } = createViewer();
      bpmnReplace.replaceElement.mockImplementation((element: Element) => element);
      const element = { id: "s", businessObject: bpmnElement("StartEvent") } as unknown as Element;
      renderProperties(element, viewer, { types: ENTRIES });

      await user.click(field("Start Event Type"));
      await user.click(screen.getByRole("option", { name: "TimerStart" }));

      expect(bpmnReplace.replaceElement).toHaveBeenCalledWith(element,
        { type: "bpmn:StartEvent", eventDefinitionType: "bpmn:TimerEventDefinition" });
    });

    it("lists the entries of an element type with one group under no heading", async () => {
      const user = userEvent.setup();
      const { viewer } = createViewer();
      renderProperties({ id: "s", businessObject: bpmnElement("StartEvent") } as unknown as Element, viewer,
        { types: ENTRIES });

      await user.click(field("Start Event Type"));

      expect(within(screen.getByRole("listbox")).getAllByRole("option")).toHaveLength(2);
      expect(within(screen.getByRole("listbox")).queryByText("Start Event")).not.toBeInTheDocument();
    });

    it("offers a boundary event no other element type", () => {
      const { viewer } = createViewer();
      const element = { id: "b", businessObject: bpmnElement("BoundaryEvent", {
        eventDefinitions: [ bpmnElement("TimerEventDefinition") ],
      }) } as unknown as Element;
      renderProperties(element, viewer, { types: ENTRIES });

      expect(screen.getByText("Boundary Event")).toBeInTheDocument();
      expect(noField("Element Type")).not.toBeInTheDocument();
    });
  });

  it("writes one edit per value typed, not one per keystroke", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties } = createViewer();
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    renderProperties(element, viewer, { types: VOCABULARY });

    await user.type(field("Colour"), "promoted");
    expect(updateModdleProperties).not.toHaveBeenCalled();
    expect(field("Colour")).toHaveValue("promoted");
    await user.keyboard("{enter}");

    expect(updateModdleProperties).toHaveBeenCalledTimes(1);
    expect(updateModdleProperties)
      .toHaveBeenCalledWith(element, element.businessObject, { "iap:colour": "promoted" });
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
      "$attrs": { "iap:colour": "promoted" },
      ...carrying(setState("ACTIVE", [ "DRAFT", "TRIAL" ])),
    }), viewer, { types: VOCABULARY, readOnly: true });

    expect(screen.getByText("Service Task Type")).toBeInTheDocument();
    expect(screen.getByText("Set state")).toBeInTheDocument();
    expect(screen.getByText("Colour")).toBeInTheDocument();
    expect(screen.getByText("promoted")).toBeInTheDocument();
    expect(screen.getByText("To state")).toBeInTheDocument();
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
    expect(screen.getByText("From states")).toBeInTheDocument();
    expect(screen.getByText("DRAFT, TRIAL")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("rereads every value when the canvas reports the element as changed", () => {
    const { viewer } = createViewer();
    const extension = setState("ACTIVE", [ "DRAFT" ]);
    const element = task("Promote", {
      "$attrs": { "iap:colour": "promoted" },
      ...carrying(extension),
    });
    const { rerender } = renderProperties(element, viewer, { types: VOCABULARY, revision: 1 });

    // An undo, or an edit made elsewhere
    extension.toState = "TRIAL";
    (element.businessObject as ModdleElement).$attrs = { "iap:colour": "retired" };
    rerender(<ElementProperties element={element} viewer={viewer} types={VOCABULARY} revision={2} />);

    expect(field("To state")).toHaveValue("TRIAL");
    expect(field("Colour")).toHaveValue("retired");
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

    await user.type(field("Colour"), "in {enter}");
    reportChange(2);
    await user.type(field("Colour"), "review{enter}");
    reportChange(3);

    expect(field("Colour")).toHaveValue("in review");
    expect(updateModdleProperties).toHaveBeenLastCalledWith(element, element.businessObject,
      { "iap:colour": "in review" });
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

  it("writes nothing for an attribute of the entry the element meant before an undo", async () => {
    // An attribute can be written whatever the element carries, so only the panel can tell it no longer applies
    const user = userEvent.setup();
    const { viewer, updateModdleProperties, fire } = createViewer();
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    renderProperties(element, viewer, { types: VOCABULARY });

    await user.type(field("Colour"), "red");
    (element.businessObject as ModdleElement).extensionElements = { values: [] };
    await fire("selection.changed", { newSelection: [] });

    expect(updateModdleProperties).not.toHaveBeenCalled();
  });

  it("holds back a whole number still being typed that the server would not store", async () => {
    const user = userEvent.setup();
    const { viewer, updateModdleProperties, fire } = createViewer();
    const retries = parameter("retries", { label: "Retries", dataType: "long", xmlLocation: "attribute" });
    const element = task("Retry", { $attrs: { "iap:retries": "5" } });
    renderProperties(element, viewer, { types: [ type("Retrying", { parameters: [ retries ] }) ] });

    await user.clear(field("Retries"));
    await user.type(field("Retries"), "-");
    await fire("selection.changed", { newSelection: [] });

    expect(updateModdleProperties).not.toHaveBeenCalled();
  });

  it("keeps the name being typed when the vocabulary arrives, and writes it", async () => {
    const user = userEvent.setup();
    const { viewer, updateLabel, fire } = createViewer();
    const element = task("Promote", carrying(setState("ACTIVE", [])));
    const { rerender } = renderProperties(element, viewer);

    await user.type(screen.getByRole("textbox"), "d");
    rerender(<ElementProperties element={element} viewer={viewer} types={VOCABULARY} />);
    expect(screen.getAllByRole("textbox")[0]).toHaveValue("Promoted");
    await fire("selection.changed", { newSelection: [] });

    expect(updateLabel).toHaveBeenCalledWith(element, "Promoted");
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
