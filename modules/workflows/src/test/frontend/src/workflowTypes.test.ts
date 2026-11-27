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
  isReachable,
  isRequired,
  isSkippedElement,
  isStructure,
  matchFlowNodeType,
  parseWorkflowTypes,
  replacementFor,
  switchableTypes,
  typeGroupOf,
} from "@iap/workflows/workflowTypes";
import type { FlowNodeType } from "@iap/workflows/workflowTypes";

import {
  bpmnElement, carrying, documentedEntry, documentedParameter, drawnEntry, iapElement, parameter,
} from "./vocabulary.fixture";

// The vocabulary as the server documents it: one entry per kind of node, each listed under its element type.
const SERVICE_TASK = documentedEntry("ServiceTask", {
  label: "Service Task",
  description: "Anything the platform does on its own",
  group: "General",
  priority: 0,
  parameters: [ documentedParameter("handler", { label: "Handler" }) ],
});

const SET_STATE = documentedEntry("SetState", {
  label: "Set State",
  group: "Workflow Authoring",
  priority: 10,
  xmlExtensionElement: "setState",
  jcrProperties: { handler: "setState" },
  parameters: [
    documentedParameter("colour", {
      label: "Colour", description: "The colour to draw the task in", xmlLocation: "attribute",
    }),
    documentedParameter("toState", {
      label: "To state", minValues: 1, options: [ { value: "DRAFT", label: "Draft" }, { value: "ACTIVE", label: "Active" } ],
    }),
  ],
});

const START_EVENT = documentedEntry("StartEvent", {
  label: "Start Event",
  category: [ "Start Event" ],
  priority: 0,
  xmlElement: "bpmn:startEvent",
  jcrNodeType: "wf:StartEvent",
  elementTypeDefault: true,
});

const MESSAGE_START_EVENT = documentedEntry("MessageStartEvent", {
  label: "Message Start Event",
  category: [ "Start Event" ],
  priority: 10,
  xmlElement: "bpmn:startEvent",
  xmlChildElement: "bpmn:messageEventDefinition",
  jcrNodeType: "wf:StartEvent",
  parameters: [
    documentedParameter("performers", { label: "Performers", dataType: "principal", maxValues: 0, xmlLocation: "attribute" }),
  ],
});

const DOCUMENTED = {
  title: "Workflow node types",
  description: "The kinds of node a workflow diagram may be drawn from",
  items: {
    "Service Task": [ SERVICE_TASK, SET_STATE ],
    "Start Event": [ START_EVENT, MESSAGE_START_EVENT ],
  },
};

const documented = () => parseWorkflowTypes(DOCUMENTED);

describe("parseWorkflowTypes", () => {
  it("lists the entries of every element type in one list", () => {
    const types = documented();

    expect(types.map(type => type.name))
      .toEqual([ "ServiceTask", "SetState", "StartEvent", "MessageStartEvent" ]);
  });

  it("keeps what matching and editing need from an entry", () => {
    const type = documented().find(candidate => candidate.name === "SetState");

    expect(type).toMatchObject({
      label: "Set State",
      category: [ "Service Task" ],
      group: "Workflow Authoring",
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

  it("falls back on the entry's own name, no categories, no group and no priority when it documents none", () => {
    const types = parseWorkflowTypes({ items: { Activities: [ { name: "Odd", xmlElement: "bpmn:task" } ] } });

    expect(types[0]).toMatchObject({ name: "Odd", label: "Odd", category: [], priority: 0,
      elementTypeDefault: false, parameters: [] });
    expect(types[0].group).toBeUndefined();
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

describe("matching an element against the vocabulary", () => {
  it("reads the local part of an element name, whatever it is prefixed with", () => {
    expect(localName("bpmn:serviceTask")).toBe("serviceTask");
    expect(localName("serviceTask")).toBe("serviceTask");
  });

  it("matches every entry drawn as this element, most specific first", () => {
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

  it("matches nothing for an element the vocabulary does not describe", () => {
    expect(applicableTypes(bpmnElement("SequenceFlow"), documented())).toEqual([]);
    expect(applicableTypes({}, documented())).toEqual([]);
    expect(matchFlowNodeType({}, documented())).toBeUndefined();
  });

  it("matches only the entries whose required child the element carries", () => {
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
    // Each names an element of its own, so that none is outranked by another
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

  it("matches no entry naming no node type, as the server does", () => {
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
  const SERVICE = drawnEntry("ServiceTask", "serviceTask", [ "Service Task" ], { group: "General" });
  const COUNT = drawnEntry("Count", "serviceTask", [ "Service Task" ], { group: "Metrics", xmlExtensionElement: "count" });
  const TASK = drawnEntry("Task", "task", [ "Task" ]);
  const CALL = drawnEntry("CallActivity", "callActivity", [ "Call Activity" ]);
  const START = drawnEntry("StartEvent", "startEvent", [ "Start Event" ]);
  const TIMER = drawnEntry("TimerStart", "startEvent", [ "Start Event" ],
    { xmlChildElement: "bpmn:timerEventDefinition", priority: 10 });
  const END = drawnEntry("EndEvent", "endEvent", [ "End Event" ]);
  const EXCLUSIVE = drawnEntry("ExclusiveGateway", "exclusiveGateway", [ "Gateway" ]);
  const PARALLEL = drawnEntry("ParallelGateway", "parallelGateway", [ "Gateway" ]);
  const CATCH = drawnEntry("IntermediateCatchEvent", "intermediateCatchEvent", [ "Intermediate Event" ]);
  const THROW = drawnEntry("IntermediateThrowEvent", "intermediateThrowEvent", [ "Intermediate Event" ],
    { elementTypeDefault: true });
  const BOUNDARY = drawnEntry("TimerBoundary", "boundaryEvent", [ "Boundary Event" ],
    { xmlChildElement: "bpmn:timerEventDefinition" });
  const MESSAGE_BOUNDARY = drawnEntry("MessageBoundary", "boundaryEvent", [ "Boundary Event" ],
    { xmlChildElement: "bpmn:messageEventDefinition" });
  const FLOW = drawnEntry("SequenceFlow", "sequenceFlow", [ "Sequence Flow" ]);
  const ONLY_EXTENDED = drawnEntry("Notify", "sendTask", [ "Send Task" ], { xmlExtensionElement: "notify" });
  const UNLISTED = drawnEntry("Unlisted", "manualTask", []);
  const ALL = [ COUNT, SERVICE, TASK, CALL, START, TIMER, END, EXCLUSIVE, PARALLEL, CATCH, THROW, BOUNDARY,
    MESSAGE_BOUNDARY, FLOW, ONLY_EXTENDED, UNLISTED ];

  const switchableNames = (matched: FlowNodeType) => [ ...switchableTypes(matched, ALL).keys() ];

  it("lists an entry under its first category, or else under its element's name", () => {
    expect(elementTypeOf(TIMER)).toBe("Start Event");
    expect(elementTypeOf(UNLISTED)).toBe("manualTask");
  });

  it("groups the entries of an element type by their group, or else by the element type", () => {
    expect(typeGroupOf(COUNT)).toBe("Metrics");
    expect(typeGroupOf(START)).toBe("Start Event");
  });

  it("lists the entries of an element type by label", () => {
    expect(entriesOf(ALL, "Service Task").map(found => found.name)).toEqual([ "Count", "ServiceTask" ]);
    expect(entriesOf(ALL, "Nothing")).toEqual([]);
  });

  it("offers an activity every element type of activity, each with the entry it becomes", () => {
    const switchable = switchableTypes(TASK, ALL);

    expect([ ...switchable.keys() ]).toEqual([ "Call Activity", "Service Task", "Task", "manualTask" ]);
    // The first entry needing neither an extension element nor an event definition
    expect(switchable.get("Service Task")?.name).toBe("ServiceTask");
  });

  it("offers each kind of event only the element types of its own kind", () => {
    expect(switchableNames(TIMER)).toEqual([ "Start Event" ]);
    expect(switchableNames(END)).toEqual([ "End Event" ]);
    expect(switchableNames(CATCH)).toEqual([ "Intermediate Event" ]);
  });

  it("offers a gateway only the element types of gateway", () => {
    expect(switchableNames(PARALLEL)).toEqual([ "Gateway" ]);
  });

  it("changes an element to the flagged entry of an element type, even when another plain one comes first", () => {
    expect(switchableTypes(CATCH, ALL).get("Intermediate Event")?.name).toBe("IntermediateThrowEvent");
  });

  it("changes an element to the first plain entry of an element type, passing over one with an event", () => {
    const earlyTimer = { ...TIMER, label: "At a set time" };

    expect(switchableTypes(START, [ earlyTimer, START ]).get("Start Event")?.name).toBe("StartEvent");
  });

  it("offers no change for a boundary event or a sequence flow", () => {
    expect(switchableTypes(FLOW, ALL).size).toBe(0);
    expect(switchableTypes(BOUNDARY, ALL).size).toBe(0);
  });

  it("reaches an entry when an element changed to it reads as that entry", () => {
    expect(isReachable(SERVICE, ALL)).toBe(true);
    expect(isReachable(COUNT, ALL)).toBe(true);
    expect(isReachable(TIMER, ALL)).toBe(true);
  });

  it("cannot reach an entry another entry outranks, or one naming no node type", () => {
    const outranked = drawnEntry("Generic", "serviceTask", [ "Service Task" ], { priority: -1 });
    const untyped = drawnEntry("Untyped", "serviceTask", [ "Service Task" ],
      { jcrNodeType: undefined, xmlExtensionElement: "untyped" });

    expect(isReachable(outranked, [ ...ALL, outranked ])).toBe(false);
    expect(isReachable(untyped, [ ...ALL, untyped ])).toBe(false);
  });

  it("knows the BPMN elements the server passes over on purpose", () => {
    expect(isSkippedElement("textAnnotation")).toBe(true);
    expect(isSkippedElement("lane")).toBe(true);
    expect(isSkippedElement("sendTask")).toBe(false);
  });

  it("needs no new element for an entry the element is already drawn as", () => {
    expect(replacementFor(bpmnElement("ServiceTask"), COUNT)).toBeUndefined();
    expect(replacementFor(bpmnElement("StartEvent", { eventDefinitions: [ bpmnElement("TimerEventDefinition") ] }),
      TIMER)).toBeUndefined();
    expect(replacementFor(bpmnElement("StartEvent"), START)).toBeUndefined();
  });

  it("asks for another element, carrying the event the entry names", () => {
    expect(replacementFor(bpmnElement("Task"), CALL))
      .toEqual({ type: "bpmn:CallActivity", eventDefinitionType: undefined });
    expect(replacementFor(bpmnElement("ExclusiveGateway"), PARALLEL))
      .toEqual({ type: "bpmn:ParallelGateway", eventDefinitionType: undefined });
  });

  it("asks for the same element again to change or drop the event it carries", () => {
    const timed = bpmnElement("StartEvent", { eventDefinitions: [ bpmnElement("TimerEventDefinition") ] });

    expect(replacementFor(timed, START)).toEqual({ type: "bpmn:StartEvent", eventDefinitionType: undefined });
    expect(replacementFor(bpmnElement("StartEvent"), TIMER))
      .toEqual({ type: "bpmn:StartEvent", eventDefinitionType: "bpmn:TimerEventDefinition" });
  });

  it("keeps a non-interrupting event non-interrupting when it is redrawn", () => {
    const reminder = bpmnElement("BoundaryEvent",
      { cancelActivity: false, eventDefinitions: [ bpmnElement("TimerEventDefinition") ] });
    const watching = bpmnElement("StartEvent", { isInterrupting: false });

    expect(replacementFor(reminder, MESSAGE_BOUNDARY)).toEqual({ type: "bpmn:BoundaryEvent",
      eventDefinitionType: "bpmn:MessageEventDefinition", cancelActivity: false });
    expect(replacementFor(watching, TIMER)).toEqual({ type: "bpmn:StartEvent",
      eventDefinitionType: "bpmn:TimerEventDefinition", isInterrupting: false });
  });

  it("names no event for a required child that is not an event definition", () => {
    const looped = drawnEntry("Looped", "serviceTask", [ "Service Task" ],
      { xmlChildElement: "bpmn:multiInstanceLoopCharacteristics" });

    expect(replacementFor(bpmnElement("ServiceTask"), looped))
      .toEqual({ type: "bpmn:ServiceTask", eventDefinitionType: undefined });
  });
});
