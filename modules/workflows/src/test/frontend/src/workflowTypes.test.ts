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

import { BpmnModdle } from "bpmn-moddle";

import { elementName, localName } from "@iap/workflows/moddle";
import type { ModdleElement } from "@iap/workflows/moddle";
import {
  applicableTypes,
  elementTypeOf,
  entriesOf,
  isMultiple,
  isRequired,
  isStructure,
  loadWorkflowTypes,
  matchFlowNodeType,
  parseWorkflowTypes,
  replacementFor,
  switchableTypes,
  typeGroupOf,
  WORKFLOW_TYPES_PATH,
} from "@iap/workflows/workflowTypes";
import type { FlowNodeType } from "@iap/workflows/workflowTypes";

import { bpmnElement, carrying, iapElement, parameter, type } from "./vocabulary.fixture";

// The vocabulary as the server documents it: one entry per kind of node, grouped by category, with an
// entry in several categories repeated under each.
const SERVICE_TASK = {
  name: "ServiceTask",
  label: "Service Task",
  description: "Anything the platform does on its own",
  category: [ "Activities" ],
  priority: 0,
  xmlElement: "bpmn:serviceTask",
  jcrNodeType: "wf:Activity",
  parameters: [
    {
      name: "handler", label: "Handler", dataType: "text", minValues: 0, maxValues: 1,
      xmlLocation: "extensionElement",
    },
  ],
};

const SET_STATE = {
  name: "SetState",
  label: "Set State",
  category: [ "Activities", "Workflow Authoring" ],
  priority: 10,
  xmlElement: "bpmn:serviceTask",
  xmlExtensionElement: "setState",
  jcrNodeType: "wf:Activity",
  jcrProperties: { handler: "setState" },
  parameters: [
    {
      name: "colour",
      label: "Colour",
      description: "The colour to draw the task in",
      dataType: "text",
      minValues: 0,
      maxValues: 1,
      xmlLocation: "attribute",
    },
    {
      name: "toState",
      label: "To state",
      dataType: "text",
      minValues: 1,
      maxValues: 1,
      xmlLocation: "extensionElement",
      options: [ { value: "DRAFT", label: "Draft" }, { value: "ACTIVE", label: "Active" } ],
    },
  ],
};

const START_EVENT = {
  name: "StartEvent",
  label: "Start Event",
  category: [ "Events" ],
  priority: 0,
  xmlElement: "bpmn:startEvent",
  jcrNodeType: "wf:StartEvent",
  elementTypeDefault: true,
};

const MESSAGE_START_EVENT = {
  name: "MessageStartEvent",
  label: "Message Start Event",
  category: [ "Events" ],
  priority: 10,
  xmlElement: "bpmn:startEvent",
  xmlChildElement: "bpmn:messageEventDefinition",
  jcrNodeType: "wf:StartEvent",
  parameters: [
    {
      name: "performers", label: "Performers", dataType: "principal", minValues: 0, maxValues: 0,
      xmlLocation: "attribute",
    },
  ],
};

const DOCUMENTED = {
  title: "Workflow node types",
  description: "The kinds of node a workflow diagram may be drawn from",
  items: {
    "Activities": [ SERVICE_TASK, SET_STATE ],
    "Events": [ START_EVENT, MESSAGE_START_EVENT ],
    "Workflow Authoring": [ SET_STATE ],
  },
};

const documented = () => parseWorkflowTypes(DOCUMENTED);

type FetchStub = (url: string) => Promise<Response>;

const okResponse = (body: unknown) =>
  ({ ok: true, status: 200, url: "", json: () => Promise.resolve(body) } as unknown as Response);

const refused = (status: number) => ({ ok: false, status } as unknown as Response);

describe("parseWorkflowTypes", () => {
  it("flattens the categories and lists every entry once", () => {
    const types = documented();

    expect(types.map(type => type.name))
      .toEqual([ "ServiceTask", "SetState", "StartEvent", "MessageStartEvent" ]);
  });

  it("keeps what matching and editing need from an entry", () => {
    const type = documented().find(candidate => candidate.name === "SetState");

    expect(type).toMatchObject({
      label: "Set State",
      category: [ "Activities", "Workflow Authoring" ],
      priority: 10,
      xmlElement: "bpmn:serviceTask",
      xmlExtensionElement: "setState",
      jcrNodeType: "wf:Activity",
    });
    expect(type?.parameters.map(each => each.name)).toEqual([ "colour", "toState" ]);
    expect(type?.parameters[1].options).toEqual([ { value: "DRAFT", label: "Draft" },
      { value: "ACTIVE", label: "Active" } ]);
  });

  it("reads an entry that declares no parameters as one that accepts none", () => {
    const type = documented().find(candidate => candidate.name === "StartEvent");

    expect(type?.parameters).toEqual([]);
    expect(type?.xmlChildElement).toBeUndefined();
    expect(type?.xmlExtensionElement).toBeUndefined();
  });

  it("reads whether an entry is its element type's default", () => {
    const flagged = documented().filter(candidate => candidate.elementTypeDefault).map(candidate => candidate.name);

    expect(flagged).toEqual([ "StartEvent" ]);
  });

  it("fills in what a parameter leaves out", () => {
    const types = parseWorkflowTypes({
      items: { Activities: [ { ...SERVICE_TASK, parameters: [ { name: "amount", dataType: "long" } ] } ] },
    });

    expect(types[0].parameters[0]).toEqual({
      name: "amount",
      label: "amount",
      dataType: "long",
      minValues: 0,
      maxValues: 1,
      xmlLocation: "extensionElement",
      xmlPath: [],
      options: [],
      parameters: [],
    });
  });

  it("reads where a parameter is written, and the path to BPMN's own markup holding it", () => {
    const types = parseWorkflowTypes({
      items: { Events: [ { ...START_EVENT, parameters: [
        { name: "timeDuration", xmlLocation: "bpmnElement", xmlPath: "bpmn:timerEventDefinition/ bpmn:timeDate" },
        { name: "unplaced", xmlLocation: "elsewhere", xmlPath: 3 },
      ] } ] },
    });

    expect(types[0].parameters.map(({ xmlLocation, xmlPath }) => ({ xmlLocation, xmlPath }))).toEqual([
      { xmlLocation: "bpmnElement", xmlPath: [ "timerEventDefinition", "timeDate" ] },
      { xmlLocation: "extensionElement", xmlPath: [] },
    ]);
  });

  it("reads the fields of a structure, and the one that keys it, at every level", () => {
    const types = parseWorkflowTypes({
      items: { Activities: [ { ...SERVICE_TASK, parameters: [ {
        name: "fields", dataType: "structure", keyParameter: "name", parameters: [
          { name: "name" },
          { name: "choices", dataType: "structure", parameters: [ { name: "value" }, { label: "nameless" } ] },
        ],
      } ] } ] },
    });

    const [ fields ] = types[0].parameters;
    expect(isStructure(fields)).toBe(true);
    expect(fields.keyParameter).toBe("name");
    expect(fields.parameters?.map(field => field.name)).toEqual([ "name", "choices" ]);
    expect(fields.parameters?.[1].parameters?.map(field => field.name)).toEqual([ "value" ]);
    expect(fields.parameters?.[0].keyParameter).toBeUndefined();
    expect(isStructure(fields.parameters?.[0] ?? fields)).toBe(false);
  });

  it("reads a parameter that names no data type as text", () => {
    const types = parseWorkflowTypes({
      items: { Activities: [ { ...SERVICE_TASK, parameters: [ { name: "note" } ] } ] },
    });

    expect(types[0].parameters[0].dataType).toBe("text");
  });

  it("skips what it could neither offer nor match", () => {
    const types = parseWorkflowTypes({
      items: {
        Activities: [
          "not an entry",
          { label: "Nameless" },
          { name: "Undrawable" },
          { ...SERVICE_TASK, parameters: [ null, { label: "Nameless" }, { name: "kept" } ] },
          { ...START_EVENT, parameters: "not a list" },
        ],
        Broken: "not a list",
      },
    });

    expect(types.map(type => type.name)).toEqual([ "ServiceTask", "StartEvent" ]);
    expect(types[0].parameters.map(each => each.name)).toEqual([ "kept" ]);
    expect(types[1].parameters).toEqual([]);
  });

  it("skips a choice that names no value, and labels one that names no label", () => {
    const types = parseWorkflowTypes({
      items: {
        Activities: [ {
          ...SERVICE_TASK,
          parameters: [ { name: "state", options: [ { label: "Nothing" }, { value: "DRAFT" }, 7 ] } ],
        } ],
      },
    });

    expect(types[0].parameters[0].options).toEqual([ { value: "DRAFT", label: "DRAFT" } ]);
  });

  it("falls back on the entry's own name, no categories and no priority when it documents none", () => {
    const types = parseWorkflowTypes({ items: { Activities: [ { name: "Odd", xmlElement: "bpmn:task" } ] } });

    expect(types[0]).toMatchObject({ name: "Odd", label: "Odd", category: [], priority: 0,
      elementTypeDefault: false, parameters: [] });
  });

  it("reads an entry's element name without whatever prefix it was written with", () => {
    const types = parseWorkflowTypes({
      items: { Activities: [ { ...SET_STATE, xmlExtensionElement: "iap:setState" } ] },
    });

    expect(types[0].xmlExtensionElement).toBe("setState");
    expect(matchFlowNodeType(bpmnElement("ServiceTask", carrying(iapElement("setState"))), types)?.name)
      .toBe("SetState");
  });

  it("reads an element name that is nothing but a prefix as naming no element", () => {
    const types = parseWorkflowTypes({
      items: { Activities: [ { ...SET_STATE, xmlExtensionElement: "iap:" } ] },
    });

    expect(types[0].xmlExtensionElement).toBeUndefined();
  });

  it("reads a body that documents nothing as an empty vocabulary", () => {
    expect(parseWorkflowTypes(undefined)).toEqual([]);
    expect(parseWorkflowTypes({})).toEqual([]);
    expect(parseWorkflowTypes({ items: [] })).toEqual([]);
  });
});

describe("loadWorkflowTypes", () => {
  it("asks the vocabulary to document itself each time an editor opens", async () => {
    const fetchUtil = vi.fn<FetchStub>(() => Promise.resolve(okResponse(DOCUMENTED)));

    const first = await loadWorkflowTypes(fetchUtil);
    const second = await loadWorkflowTypes(fetchUtil);

    expect(fetchUtil).toHaveBeenCalledTimes(2);
    expect(fetchUtil).toHaveBeenCalledWith(`${WORKFLOW_TYPES_PATH}.doc.json`);
    expect(first.map(type => type.name)).toContain("SetState");
    expect(second).toEqual(first);
  });

  it("shares one request between canvases opening at the same time", async () => {
    let answer: () => void = () => undefined;
    const fetchUtil = vi.fn<FetchStub>(() => new Promise<Response>(resolve => {
      answer = () => resolve(okResponse(DOCUMENTED));
    }));

    const asking = Promise.all([ loadWorkflowTypes(fetchUtil), loadWorkflowTypes(fetchUtil) ]);
    answer();
    const [ first, second ] = await asking;

    expect(fetchUtil).toHaveBeenCalledTimes(1);
    expect(second).toBe(first);
  });

  it("reports a refusal, and asks again rather than remembering it", async () => {
    const fetchUtil = vi.fn<FetchStub>()
      .mockResolvedValueOnce(refused(503))
      .mockResolvedValue(okResponse(DOCUMENTED));

    await expect(loadWorkflowTypes(fetchUtil)).rejects.toThrow("503");

    await expect(loadWorkflowTypes(fetchUtil)).resolves.toHaveLength(4);
    expect(fetchUtil).toHaveBeenCalledTimes(2);
  });
});

describe("matching an element against the vocabulary", () => {
  it("reads the local part of an element name, whatever it is prefixed with", () => {
    expect(localName("bpmn:serviceTask")).toBe("serviceTask");
    expect(localName("serviceTask")).toBe("serviceTask");
  });

  it("offers every entry drawn as this element, most specific first", () => {
    const candidates = applicableTypes(bpmnElement("ServiceTask"), documented());

    expect(candidates.map(type => type.name)).toEqual([ "SetState", "ServiceTask" ]);
  });

  it("ranks two entries of equal priority by name, whichever order they are documented in", () => {
    const ranked = (...entries: unknown[]) =>
      applicableTypes(bpmnElement("ServiceTask"), parseWorkflowTypes({ items: { Activities: entries } }))
        .map(type => type.name);
    const zebra = { name: "Zebra", xmlElement: "bpmn:serviceTask", jcrNodeType: "wf:Activity", xmlExtensionElement: "zebra" };
    const antelope = { name: "Antelope", xmlElement: "bpmn:serviceTask", jcrNodeType: "wf:Activity",
      xmlExtensionElement: "antelope" };

    expect(ranked(zebra, antelope, SET_STATE)).toEqual([ "SetState", "Antelope", "Zebra" ]);
    expect(ranked(antelope, zebra, SET_STATE)).toEqual([ "SetState", "Antelope", "Zebra" ]);
  });

  it("reads a BPMN type the way the document writes it, and any other as named", () => {
    const timed = bpmnElement("BoundaryEvent", { eventDefinitions: [ bpmnElement("TimerEventDefinition") ] });
    const types = parseWorkflowTypes({ items: { Events: [
      { name: "Timer", xmlElement: "bpmn:boundaryEvent", xmlChildElement: "bpmn:timerEventDefinition",
        jcrNodeType: "wf:Event" },
    ] } });

    expect(matchFlowNodeType(timed, types)?.name).toBe("Timer");
    expect(elementName(bpmnElement("ServiceTask"))).toBe("serviceTask");
    expect(elementName(iapElement("SetState"))).toBe("SetState");
  });

  it("offers nothing for an element the vocabulary does not describe", () => {
    expect(applicableTypes(bpmnElement("SequenceFlow"), documented())).toEqual([]);
    expect(applicableTypes({}, documented())).toEqual([]);
    expect(matchFlowNodeType({}, documented())).toBeUndefined();
  });

  it("offers only the entries whose required child the element carries", () => {
    const plain = bpmnElement("StartEvent");
    const messaged = bpmnElement("StartEvent", {
      eventDefinitions: [ bpmnElement("MessageEventDefinition") ],
    });

    expect(applicableTypes(plain, documented()).map(type => type.name)).toEqual([ "StartEvent" ]);
    expect(matchFlowNodeType(plain, documented())?.name).toBe("StartEvent");
    expect(matchFlowNodeType(messaged, documented())?.name).toBe("MessageStartEvent");
  });

  it("compares element names exactly, in the case a document writes them, as the server does", async () => {
    const { rootElement } = await new BpmnModdle().fromXML(`<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d" targetNamespace="x">
  <bpmn:process id="p">
    <bpmn:serviceTask id="task" />
    <bpmn:boundaryEvent id="timer" attachedToRef="task">
      <bpmn:timerEventDefinition />
    </bpmn:boundaryEvent>
  </bpmn:process>
</bpmn:definitions>`);
    const [ task, timer ] = ((rootElement as ModdleElement).rootElements as ModdleElement[])[0]
      .flowElements as ModdleElement[];
    const matching = (element: ModdleElement, entry: { xmlElement: string; xmlChildElement?: string }) =>
      matchFlowNodeType(element, parseWorkflowTypes({ items: { Activities: [
        { name: "Entry", jcrNodeType: "wf:Activity", ...entry },
      ] } }))?.name;

    expect(matching(task, { xmlElement: "bpmn:ServiceTask" })).toBeUndefined();
    expect(matching(task, { xmlElement: "bpmn:serviceTask" })).toBe("Entry");
    expect(matching(timer, { xmlElement: "bpmn:boundaryEvent", xmlChildElement: "bpmn:TimerEventDefinition" }))
      .toBeUndefined();
    expect(matching(timer, { xmlElement: "bpmn:boundaryEvent", xmlChildElement: "bpmn:timerEventDefinition" }))
      .toBe("Entry");
  });

  it("looks for a required child among every BPMN child the element is written with", async () => {
    // The server's parser accepts any direct BPMN child, so a loop marker, a reference or a child
    // written as its property has to count here too
    const moddle = new BpmnModdle();
    const { rootElement } = await moddle.fromXML(`<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d" targetNamespace="x">
  <bpmn:process id="p">
    <bpmn:serviceTask id="each">
      <bpmn:incoming>in</bpmn:incoming>
      <bpmn:ioSpecification id="io" />
      <bpmn:multiInstanceLoopCharacteristics />
    </bpmn:serviceTask>
    <bpmn:startEvent id="start" />
    <bpmn:sequenceFlow id="in" sourceRef="start" targetRef="each" />
  </bpmn:process>
</bpmn:definitions>`);
    const process = (rootElement as ModdleElement).rootElements as ModdleElement[];
    const each = (process[0].flowElements as ModdleElement[])[0];
    // Each names an element of its own, so that every one the element could be made to mean is offered
    const requiring = (name: string, child: string) =>
      ({ name, xmlElement: "bpmn:serviceTask", jcrNodeType: "wf:Activity", xmlChildElement: child,
        xmlExtensionElement: name, priority: 10 });
    const types = parseWorkflowTypes({ items: { Activities: [
      requiring("PerItem", "bpmn:multiInstanceLoopCharacteristics"),
      requiring("Reached", "bpmn:incoming"),
      requiring("Specified", "bpmn:ioSpecification"),
      requiring("Repeated", "bpmn:standardLoopCharacteristics"),
    ] } });

    expect(applicableTypes(each, types).map(type => type.name)).toEqual([ "PerItem", "Reached", "Specified" ]);
  });

  it("reads a sequence flow as the entry drawn as one", () => {
    const flow = { name: "SequenceFlow", xmlElement: "bpmn:sequenceFlow", jcrNodeType: "wf:SequenceFlow", priority: 0 };
    const types = parseWorkflowTypes({ items: { "Sequence Flows": [ flow ] } });

    expect(matchFlowNodeType(bpmnElement("SequenceFlow"), types)?.name).toBe("SequenceFlow");
  });

  it("reads a service task as the handler its extension element names", () => {
    const promote = bpmnElement("ServiceTask", carrying(iapElement("setState")));

    // The entry naming the element wins over the generic one, which every service task also matches
    expect(applicableTypes(promote, documented()).map(type => type.name))
      .toEqual([ "SetState", "ServiceTask" ]);
    expect(matchFlowNodeType(promote, documented())?.name).toBe("SetState");
  });

  it("neither offers nor matches an entry naming no node type, as the server does", () => {
    const types = parseWorkflowTypes({ items: { Activities: [
      { ...SET_STATE, jcrNodeType: undefined },
      { ...SET_STATE, name: "BlankSetState", jcrNodeType: " " },
      SERVICE_TASK,
    ] } });
    const promote = bpmnElement("ServiceTask", carrying(iapElement("setState")));

    expect(matchFlowNodeType(promote, types)?.name).toBe("ServiceTask");
    expect(applicableTypes(promote, types).map(type => type.name)).toEqual([ "ServiceTask" ]);
  });

  it("reads a service task with no extension element as the generic one", () => {
    const work = bpmnElement("ServiceTask", carrying(iapElement("retireActiveVersions")));

    expect(matchFlowNodeType(bpmnElement("ServiceTask"), documented())?.name).toBe("ServiceTask");
    expect(matchFlowNodeType(work, documented())?.name).toBe("ServiceTask");
  });

  it("recognizes the extension element by namespace rather than by prefix", () => {
    const authored = bpmnElement("ServiceTask", carrying(iapElement("setState", {}, { prefix: "ns0" })));
    const foreign = bpmnElement("ServiceTask", carrying(iapElement("setState", {}, { prefix: "other", uri: "urn:example" })));

    expect(matchFlowNodeType(authored, documented())?.name).toBe("SetState");
    expect(matchFlowNodeType(foreign, documented())?.name).toBe("ServiceTask");
  });
});

describe("what a parameter accepts", () => {
  it("is mandatory exactly when it asks for a value", () => {
    expect(isRequired(parameter("value"))).toBe(false);
    expect(isRequired(parameter("value", { minValues: 1 }))).toBe(true);
  });

  it("takes several values when it is bounded anywhere but at exactly one", () => {
    expect(isMultiple(parameter("value"))).toBe(false);
    expect(isMultiple(parameter("value", { maxValues: 2 }))).toBe(true);
    expect(isMultiple(parameter("value", { maxValues: 0 }))).toBe(true);
    // A nonsensical bound still has to be read the way the server reads it, or the two would store
    // the same entry's value in two different shapes
    expect(isMultiple(parameter("value", { maxValues: -1 }))).toBe(true);
  });
});

describe("element types and switching between them", () => {
  const entry = (name: string, xmlElement: string, category: string[], extra: Partial<FlowNodeType> = {}) =>
    type(name, { label: name, xmlElement: `bpmn:${xmlElement}`, category, priority: 0, ...extra });
  const SERVICE = entry("ServiceTask", "serviceTask", [ "Service Task", "General" ]);
  const COUNT = entry("Count", "serviceTask", [ "Service Task", "Metrics" ], { xmlExtensionElement: "count" });
  const TASK = entry("Task", "task", [ "Task" ]);
  const START = entry("StartEvent", "startEvent", [ "Start Event" ]);
  const TIMER = entry("TimerStart", "startEvent", [ "Start Event" ],
    { xmlChildElement: "bpmn:timerEventDefinition" });
  const EXCLUSIVE = entry("ExclusiveGateway", "exclusiveGateway", [ "Gateway" ]);
  const PARALLEL = entry("ParallelGateway", "parallelGateway", [ "Gateway" ]);
  const CATCH = entry("IntermediateCatchEvent", "intermediateCatchEvent", [ "Intermediate Event" ]);
  const THROW = entry("IntermediateThrowEvent", "intermediateThrowEvent", [ "Intermediate Event" ],
    { elementTypeDefault: true });
  const BOUNDARY = entry("TimerBoundary", "boundaryEvent", [ "Boundary Event" ],
    { xmlChildElement: "bpmn:timerEventDefinition" });
  const FLOW = entry("SequenceFlow", "sequenceFlow", [ "Sequence Flow" ]);
  const ONLY_EXTENDED = entry("Notify", "sendTask", [ "Send Task" ], { xmlExtensionElement: "notify" });
  const UNLISTED = entry("Unlisted", "manualTask", []);
  const ALL = [ COUNT, SERVICE, TASK, START, TIMER, EXCLUSIVE, PARALLEL, CATCH, THROW, BOUNDARY, FLOW, ONLY_EXTENDED,
    UNLISTED ];

  it("lists an entry under its first category, or else under its element's name", () => {
    expect(elementTypeOf(TIMER)).toBe("Start Event");
    expect(elementTypeOf(UNLISTED)).toBe("manualTask");
  });

  it("groups the entries of an element type by its second category, or else by the element type", () => {
    expect(typeGroupOf(COUNT)).toBe("Metrics");
    expect(typeGroupOf(START)).toBe("Start Event");
  });

  it("lists the entries of an element type by label", () => {
    expect(entriesOf(ALL, "Service Task").map(found => found.name)).toEqual([ "Count", "ServiceTask" ]);
    expect(entriesOf(ALL, "Nothing")).toEqual([]);
  });

  it("offers the element types a flow node can be changed to, each with the entry it becomes", () => {
    const switchable = switchableTypes(bpmnElement("Task"), ALL);

    expect([ ...switchable.keys() ]).toEqual([
      "Gateway", "Intermediate Event", "Service Task", "Start Event", "Task", "manualTask",
    ]);
    // The flagged entry, even when another plain one comes first; else the first plain entry
    expect(switchable.get("Gateway")?.name).toBe("ExclusiveGateway");
    expect(switchable.get("Intermediate Event")?.name).toBe("IntermediateThrowEvent");
    expect(switchable.get("Service Task")?.name).toBe("ServiceTask");
  });

  it("offers no change for a boundary event, a sequence flow, or an element the vocabulary does not describe", () => {
    expect(switchableTypes(bpmnElement("SequenceFlow"), ALL).size).toBe(0);
    expect(switchableTypes(bpmnElement("BoundaryEvent", {
      eventDefinitions: [ bpmnElement("TimerEventDefinition") ],
    }), ALL).size).toBe(0);
    expect(switchableTypes(bpmnElement("DataObjectReference"), ALL).size).toBe(0);
  });

  it("needs no new element for an entry the element is already drawn as", () => {
    expect(replacementFor(bpmnElement("ServiceTask"), COUNT)).toBeUndefined();
    expect(replacementFor(bpmnElement("StartEvent", { eventDefinitions: [ bpmnElement("TimerEventDefinition") ] }),
      TIMER)).toBeUndefined();
    expect(replacementFor(bpmnElement("StartEvent"), START)).toBeUndefined();
  });

  it("asks for another element, carrying the event the entry names", () => {
    expect(replacementFor(bpmnElement("Task"), TIMER))
      .toEqual({ type: "bpmn:StartEvent", eventDefinitionType: "bpmn:TimerEventDefinition" });
    expect(replacementFor(bpmnElement("ExclusiveGateway"), PARALLEL))
      .toEqual({ type: "bpmn:ParallelGateway", eventDefinitionType: undefined });
  });

  it("asks for the same element again to change or drop the event it carries", () => {
    const timed = bpmnElement("StartEvent", { eventDefinitions: [ bpmnElement("TimerEventDefinition") ] });

    expect(replacementFor(timed, START)).toEqual({ type: "bpmn:StartEvent", eventDefinitionType: undefined });
    expect(replacementFor(bpmnElement("StartEvent"), TIMER))
      .toEqual({ type: "bpmn:StartEvent", eventDefinitionType: "bpmn:TimerEventDefinition" });
  });

  it("names no event for a required child that is not an event definition", () => {
    const looped = entry("Looped", "serviceTask", [ "Service Task" ],
      { xmlChildElement: "bpmn:multiInstanceLoopCharacteristics" });

    expect(replacementFor(bpmnElement("ServiceTask"), looped))
      .toEqual({ type: "bpmn:ServiceTask", eventDefinitionType: undefined });
  });
});
