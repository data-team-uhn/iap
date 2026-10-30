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

// Driven against the real bpmn-moddle rather than a stand-in. Three things are tested to agree. They are
// what moddle hands over on import, what these rules make of it, and what it writes back for the parser.
// A stand-in would only prove the rules agree with themselves.

import { BpmnModdle } from "bpmn-moddle";

import {
  DEFAULT_IAP_PREFIX,
  commaSeparated,
  parameterEdit,
  readValues,
  typeEdit,
} from "@iap/workflows/bpmnParameters";
import type { ParameterEdit } from "@iap/workflows/bpmnParameters";
import { IAP_NS, iapExtensionNames } from "@iap/workflows/moddle";
import type { Moddle, ModdleElement } from "@iap/workflows/moddle";
import { parseWorkflowTypes } from "@iap/workflows/workflowTypes";

import {
  FROM_STATES,
  COLOUR,
  RETIRE_ACTIVE_VERSIONS,
  SERVICE_TASK,
  SET_STATE,
  TO_STATE,
  carrying,
  iapElement,
  parameter,
  type,
} from "./vocabulary.fixture";

const SV_NS = "http://www.jcp.org/jcr/sv/1.0";

const PERFORMERS = parameter("performers", { xmlLocation: "attribute", maxValues: 0 });

const START_EVENT = type("MessageStartEvent", { xmlElement: "bpmn:startEvent", parameters: [ PERFORMERS ] });

// The shape the shipped diagrams share, with the prefix left open. A document IAP did not author binds
// the namespace to whatever prefix its tool chose.
const diagram = (prefix: string) => `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:${prefix}="${IAP_NS}"
                  xmlns:sv="${SV_NS}"
                  id="Definitions_1"
                  targetNamespace="https://iap.uhndata.io/systemWorkflows/activateVersion">
  <bpmn:process id="activateVersion" isExecutable="true">
    <bpmn:startEvent id="requested" ${prefix}:performers="iap-administrators, reviewers">
      <bpmn:messageEventDefinition id="activateRequested" />
    </bpmn:startEvent>
    <bpmn:serviceTask id="promote" name="Make this the active version" ${prefix}:colour="promoted">
      <bpmn:extensionElements>
        <sv:node sv:name="cond:condition" />
        <${prefix}:setState toState="ACTIVE">
          <${prefix}:fromStates>DRAFT</${prefix}:fromStates>
          <${prefix}:fromStates>TRIAL</${prefix}:fromStates>
        </${prefix}:setState>
      </bpmn:extensionElements>
    </bpmn:serviceTask>
  </bpmn:process>
</bpmn:definitions>`;

// A document that has never heard of IAP: nothing to read, and nothing bound to write under.
const PLAIN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="Definitions_2"
                  targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="plain" isExecutable="true">
    <bpmn:serviceTask id="promote" name="Do the work" />
    <bpmn:startEvent id="requested" />
  </bpmn:process>
</bpmn:definitions>`;

interface Diagram {
  moddle: Moddle;
  definitions: ModdleElement;
  task: ModdleElement;
  start: ModdleElement;
  serialize: () => Promise<string>;
}

const flowElement = (definitions: ModdleElement, id: string): ModdleElement => {
  const processes = (definitions.rootElements ?? []) as ModdleElement[];
  const found = processes
    .flatMap(process => (process.flowElements ?? []) as ModdleElement[])
    .find(element => element.id === id);
  expect(found).toBeDefined();
  return found!;
};

const load = async (prefix = DEFAULT_IAP_PREFIX, xml = diagram(prefix)): Promise<Diagram> => {
  const moddle = new BpmnModdle();
  const { rootElement } = await moddle.fromXML(xml);
  const definitions = rootElement as ModdleElement;
  return {
    moddle: moddle as unknown as Moddle,
    definitions,
    task: flowElement(definitions, "promote"),
    start: flowElement(definitions, "requested"),
    serialize: () => moddle.toXML(definitions, { format: true }).then(written => written.xml),
  };
};

// What bpmn-js does with an edit: every named property is set on the one moddle object the edit names.
const apply = (edit: ParameterEdit | undefined) => {
  expect(edit).toBeDefined();
  Object.entries(edit!.properties).forEach(([ name, value ]) => edit!.moddleElement.set?.(name, value));
};

describe("reading a parameter", () => {
  it.each([ DEFAULT_IAP_PREFIX, "ns0" ])("finds every value in a document prefixed %s", async prefix => {
    const { definitions, task, start } = await load(prefix);

    // An attribute of the node itself, and the same as a comma-separated list
    expect(readValues(task, definitions, SET_STATE, COLOUR)).toEqual([ "promoted" ]);
    expect(readValues(start, definitions, START_EVENT, PERFORMERS))
      .toEqual([ "iap-administrators", "reviewers" ]);
    // An unprefixed attribute of the entry's own element, and its repeated children
    expect(readValues(task, definitions, SET_STATE, TO_STATE)).toEqual([ "ACTIVE" ]);
    expect(readValues(task, definitions, SET_STATE, FROM_STATES)).toEqual([ "DRAFT", "TRIAL" ]);
  });

  it("reads nothing for a parameter the diagram does not carry", async () => {
    const { definitions, task, start } = await load();

    expect(readValues(start, definitions, SET_STATE, TO_STATE)).toEqual([]);
    expect(readValues(start, definitions, SET_STATE, FROM_STATES)).toEqual([]);
    expect(readValues(start, definitions, START_EVENT, COLOUR)).toEqual([]);
    expect(readValues(task, definitions, SET_STATE, parameter("unwritten"))).toEqual([]);
  });

  it("reads an entry with no element of its own from the node's attributes", async () => {
    const { definitions, task } = await load();

    expect(readValues(task, definitions, SERVICE_TASK, parameter("colour"))).toEqual([ "promoted" ]);
  });

  it("reads a value the diagram surrounded with space as that value", async () => {
    const spaced = diagram(DEFAULT_IAP_PREFIX).replace('iap:colour="promoted"', 'iap:colour="  promoted  "');
    const { definitions, task } = await load(DEFAULT_IAP_PREFIX, spaced);

    expect(readValues(task, definitions, SET_STATE, COLOUR)).toEqual([ "promoted" ]);
  });

  it("reads the entry's element's values surrounded with space as those values", async () => {
    // A pretty-printed diagram puts space around a list item's text, and the parser trims it there too
    const spaced = diagram(DEFAULT_IAP_PREFIX)
      .replace('toState="ACTIVE"', 'toState=" ACTIVE "')
      .replace("<iap:fromStates>DRAFT</iap:fromStates>", "<iap:fromStates>\n  DRAFT\n</iap:fromStates>");
    const { definitions, task } = await load(DEFAULT_IAP_PREFIX, spaced);

    expect(readValues(task, definitions, SET_STATE, TO_STATE)).toEqual([ "ACTIVE" ]);
    expect(readValues(task, definitions, SET_STATE, FROM_STATES)).toEqual([ "DRAFT", "TRIAL" ]);
  });

  it("names every IAP element a node carries, and nothing of another namespace's", async () => {
    const { task } = await load("ns0");

    expect(iapExtensionNames(task)).toEqual(new Set([ "setState" ]));
    expect(iapExtensionNames({})).toEqual(new Set());
    expect(iapExtensionNames(carrying(
      iapElement("node", {}, { prefix: "sv", uri: SV_NS }),
      { $descriptor: { ns: { uri: IAP_NS } } },
    ))).toEqual(new Set());
  });
});

describe("declaring the namespace", () => {
  it("binds the namespace on a document that does not, so what is written survives being saved", async () => {
    const { moddle, definitions, task, start, serialize } = await load(DEFAULT_IAP_PREFIX, PLAIN);
    expect(await serialize()).not.toContain("xmlns:iap");

    apply(parameterEdit(moddle, task, definitions, SERVICE_TASK, parameter("handler"), [ "setState" ]));
    apply(parameterEdit(moddle, start, definitions, START_EVENT, PERFORMERS, [ "authors" ]));

    const written = await serialize();
    expect(written.match(/xmlns:iap=/g)).toHaveLength(1);
    expect(written).toContain('iap:handler="setState"');
    expect(written).toContain('iap:performers="authors"');
  });
});

describe("editing an attribute-located parameter", () => {
  it("joins several values into one attribute", async () => {
    const { moddle, definitions, start, serialize } = await load();

    apply(parameterEdit(moddle, start, definitions, START_EVENT, PERFORMERS, [ "authors", "reviewers" ]));

    expect(await serialize()).toContain('iap:performers="authors, reviewers"');
    expect(readValues(start, definitions, START_EVENT, PERFORMERS)).toEqual([ "authors", "reviewers" ]);
  });

  it("keeps the prefix the document chose", async () => {
    const { moddle, definitions, start, serialize } = await load("ns0");

    apply(parameterEdit(moddle, start, definitions, START_EVENT, PERFORMERS, [ "authors" ]));

    const written = await serialize();
    expect(written).toContain('ns0:performers="authors"');
    expect(written).not.toContain("iap:performers");
  });

  it("removes the attribute when the value is cleared", async () => {
    const { moddle, definitions, task, serialize } = await load();

    apply(parameterEdit(moddle, task, definitions, SET_STATE, COLOUR, []));

    expect(await serialize()).not.toContain("iap:colour");
    expect(readValues(task, definitions, SET_STATE, COLOUR)).toEqual([]);
  });

  it("ignores a value that is only blank", async () => {
    const { moddle, definitions, task, serialize } = await load();

    apply(parameterEdit(moddle, task, definitions, SET_STATE, COLOUR, [ "" ]));
    expect(await serialize()).not.toContain("iap:colour");

    // Nothing but space too: the parser would drop it, so it is not written for the parser to drop
    apply(parameterEdit(moddle, task, definitions, SET_STATE, COLOUR, [ "  " ]));
    expect(await serialize()).not.toContain("iap:colour");
  });

  it("reads and writes under a binding the process makes rather than the document", async () => {
    // Any ancestor may bind the namespace, and the element is read and written under the binding in
    // force where it sits
    const nested = PLAIN.replace('<bpmn:process id="plain"', `<bpmn:process id="plain" xmlns:q="${IAP_NS}"`)
      .replace('name="Do the work" />', 'name="Do the work" q:colour="done" />');
    const { moddle, definitions, task, serialize } = await load(DEFAULT_IAP_PREFIX, nested);

    expect(readValues(task, definitions, SET_STATE, COLOUR)).toEqual([ "done" ]);
    apply(parameterEdit(moddle, task, definitions, SET_STATE, COLOUR, [ "finished" ]));
    apply(typeEdit(moddle, task, definitions, undefined, SET_STATE));
    apply(parameterEdit(moddle, task, definitions, SET_STATE, TO_STATE, [ "ACTIVE" ]));

    const written = await serialize();
    expect(written).toContain('q:colour="finished"');
    expect(written).toContain('<q:setState toState="ACTIVE"');
    expect(written).not.toContain("xmlns:iap");
  });
});

describe("editing an element-located parameter", () => {
  it("writes a single value as an unprefixed attribute of the entry's element", async () => {
    const { moddle, definitions, task, serialize } = await load();

    apply(parameterEdit(moddle, task, definitions, SET_STATE, TO_STATE, [ "TRIAL" ]));

    expect(await serialize()).toContain('<iap:setState toState="TRIAL">');
    expect(readValues(task, definitions, SET_STATE, TO_STATE)).toEqual([ "TRIAL" ]);
  });

  it("writes a list as one namespaced child per value", async () => {
    const { moddle, definitions, task, serialize } = await load();

    apply(parameterEdit(moddle, task, definitions, SET_STATE, FROM_STATES, [ "TRIAL", "ACTIVE" ]));

    const written = await serialize();
    expect(written).toContain("<iap:fromStates>TRIAL</iap:fromStates>");
    expect(written).toContain("<iap:fromStates>ACTIVE</iap:fromStates>");
    expect(written).not.toContain("<iap:fromStates>DRAFT</iap:fromStates>");
    expect(readValues(task, definitions, SET_STATE, FROM_STATES)).toEqual([ "TRIAL", "ACTIVE" ]);
  });

  it("leaves the entry's other parameters where they were", async () => {
    const { moddle, definitions, task, serialize } = await load();

    apply(parameterEdit(moddle, task, definitions, SET_STATE, FROM_STATES, [ "DRAFT" ]));

    const written = await serialize();
    expect(written).toContain('toState="ACTIVE"');
    expect(written).toContain('iap:colour="promoted"');
  });

  it("removes the attribute and the children when the values are cleared", async () => {
    const { moddle, definitions, task, serialize } = await load();

    apply(parameterEdit(moddle, task, definitions, SET_STATE, TO_STATE, []));
    apply(parameterEdit(moddle, task, definitions, SET_STATE, FROM_STATES, []));

    const written = await serialize();
    expect(written).toContain("setState");
    expect(written).not.toContain("toState");
    expect(written).not.toContain("fromStates");
  });

  it("writes nothing for an entry whose element the node does not carry", async () => {
    // Only a field built before an undo removed the element gets here, and bringing it back would
    // reverse that undo
    const { moddle, definitions, start } = await load();

    expect(parameterEdit(moddle, start, definitions, SET_STATE, TO_STATE, [ "RETIRED" ])).toBeUndefined();
    expect(parameterEdit(moddle, start, definitions, SET_STATE, FROM_STATES, [ "DRAFT" ])).toBeUndefined();
  });
});

describe("changing what a node means", () => {
  it("brings the entry's element in empty, so every parameter of it reads as unset", async () => {
    const { moddle, definitions, task } = await load();

    // Away and back: what the element held is not restored with it, and neither an attribute nor a
    // child of it is there to be read
    apply(typeEdit(moddle, task, definitions, SET_STATE, RETIRE_ACTIVE_VERSIONS));
    apply(typeEdit(moddle, task, definitions, RETIRE_ACTIVE_VERSIONS, SET_STATE));

    expect(readValues(task, definitions, SET_STATE, TO_STATE)).toEqual([]);
    expect(readValues(task, definitions, SET_STATE, FROM_STATES)).toEqual([]);
  });

  it("replaces the entry's element and keeps what belongs to something else", async () => {
    const { moddle, definitions, task, serialize } = await load();

    apply(typeEdit(moddle, task, definitions, SET_STATE, RETIRE_ACTIVE_VERSIONS));

    const written = await serialize();
    expect(written).toContain("<iap:retireActiveVersions");
    expect(written).not.toContain("setState");
    expect(written).not.toContain("fromStates");
    // The condition subtree is another feature's, under the same extensionElements. Only that it
    // survives is this edit's business: moddle writes a generic element's own-prefix attributes back
    // without the prefix, which is its doing and happens to every save.
    expect(written).toContain("<sv:node");
    // And an attribute says something about the node rather than about the entry, so it stays
    expect(written).toContain('iap:colour="promoted"');
  });

  it("keeps what the entry taken over already holds when the node carries its element", async () => {
    // Two entries' elements on one node: the diagram is ambiguous, and choosing one of them must not
    // throw away the configuration it already has
    const both = diagram(DEFAULT_IAP_PREFIX)
      .replace("<sv:node", "<iap:retireActiveVersions />\n        <sv:node");
    const { moddle, definitions, task, serialize } = await load(DEFAULT_IAP_PREFIX, both);

    apply(typeEdit(moddle, task, definitions, RETIRE_ACTIVE_VERSIONS, SET_STATE));

    expect(readValues(task, definitions, SET_STATE, TO_STATE)).toEqual([ "ACTIVE" ]);
    expect(readValues(task, definitions, SET_STATE, FROM_STATES)).toEqual([ "DRAFT", "TRIAL" ]);
    expect(await serialize()).not.toContain("retireActiveVersions");
  });

  it("drops an attribute only the entry being left reads, and keeps one both read", async () => {
    // A handler named on the generic entry would otherwise stay behind, beside the element that now
    // names the handler, and be reported as an attribute nothing reads
    const { moddle, definitions, task, serialize } = await load(DEFAULT_IAP_PREFIX, PLAIN);
    task.set?.("iap:handler", "setState");
    task.set?.("iap:colour", "done");
    definitions.set?.("xmlns:iap", IAP_NS);

    apply(typeEdit(moddle, task, definitions, SERVICE_TASK, SET_STATE));

    const written = await serialize();
    expect(written).not.toContain("iap:handler");
    expect(written).toContain('iap:colour="done"');
  });

  it("changes nothing when the entry chosen is the one the node already means", async () => {
    const { moddle, definitions, task } = await load();

    apply(typeEdit(moddle, task, definitions, SET_STATE, SET_STATE));

    expect(readValues(task, definitions, SET_STATE, TO_STATE)).toEqual([ "ACTIVE" ]);
  });

  it("leaves nothing behind when the entry taken over has no element of its own", async () => {
    const { moddle, definitions, task, serialize } = await load(DEFAULT_IAP_PREFIX, PLAIN);
    apply(typeEdit(moddle, task, definitions, undefined, SET_STATE));
    apply(parameterEdit(moddle, task, definitions, SET_STATE, TO_STATE, [ "DRAFT" ]));

    apply(typeEdit(moddle, task, definitions, SET_STATE, SERVICE_TASK));

    const written = await serialize();
    expect(written).not.toContain("setState");
    // An empty container is removed rather than written out as an empty element
    expect(written).not.toContain("extensionElements");
  });

  it("writes an element a document bound to another prefix still reads back", async () => {
    const { moddle, definitions, task, serialize } = await load("ns0");

    apply(typeEdit(moddle, task, definitions, SET_STATE, RETIRE_ACTIVE_VERSIONS));

    // A save drops an element whose prefix is not declared, without failing
    const reloaded = await load("ns0", await serialize());
    expect(iapExtensionNames(reloaded.task)).toEqual(new Set([ "retireActiveVersions" ]));
  });

  it("writes the element an entry named with a prefix under the document's prefix, not under both", async () => {
    // The vocabulary may write `iap:setState`. The prefix belongs to the document, and must not be
    // stacked onto the vocabulary's.
    const [ documented ] = parseWorkflowTypes({
      items: {
        Activities: [ { name: "SetState", xmlElement: "bpmn:serviceTask",
          xmlExtensionElement: "iap:setState" } ],
      },
    });
    const { moddle, definitions, start, serialize } = await load();

    apply(typeEdit(moddle, start, definitions, undefined, documented));
    apply(parameterEdit(moddle, start, definitions, documented, TO_STATE, [ "TRIAL" ]));

    expect(await serialize()).toContain('<iap:setState toState="TRIAL"');
    expect(iapExtensionNames(start)).toEqual(new Set([ "setState" ]));
    expect(readValues(start, definitions, documented, TO_STATE)).toEqual([ "TRIAL" ]);
  });

  it("adds the entry's element to a node that carried none", async () => {
    const { moddle, definitions, start, serialize } = await load();

    apply(typeEdit(moddle, start, definitions, undefined, SET_STATE));

    expect(await serialize()).toContain("<iap:setState");
    expect(iapExtensionNames(start)).toEqual(new Set([ "setState" ]));
    expect(readValues(start, definitions, SET_STATE, TO_STATE)).toEqual([]);
  });
});

// Values BPMN has its own markup for: a boundary event's cancelActivity, a timer's duration and an
// arc's condition, as the shipped vocabulary declares them.
const CANCEL_ACTIVITY = parameter("cancelActivity", { dataType: "boolean", xmlLocation: "bpmnAttribute" });
const TIME_DURATION = parameter("timeDuration", { xmlLocation: "bpmnElement", xmlPath: [ "timerEventDefinition" ] });
const CONDITION = parameter("conditionExpression", { xmlLocation: "bpmnElement" });

const TIMER_BOUNDARY_EVENT = type("TimerBoundaryEvent", {
  xmlElement: "bpmn:boundaryEvent",
  xmlChildElement: "bpmn:timerEventDefinition",
  parameters: [ TIME_DURATION, CANCEL_ACTIVITY ],
});

const SEQUENCE_FLOW = type("SequenceFlow", { xmlElement: "bpmn:sequenceFlow", priority: 0, parameters: [ CONDITION ] });

const HELD = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  id="Definitions_3"
                  targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="held" isExecutable="true">
    <bpmn:userTask id="review" extra="a, b" />
    <bpmn:scriptTask id="compute"><bpmn:script> sum </bpmn:script></bpmn:scriptTask>
    <bpmn:boundaryEvent id="deadline" attachedToRef="review" cancelActivity="false">
      <bpmn:timerEventDefinition id="deadlineTimer">
        <bpmn:timeDuration xsi:type="bpmn:tFormalExpression"> P5D </bpmn:timeDuration>
      </bpmn:timerEventDefinition>
    </bpmn:boundaryEvent>
    <bpmn:boundaryEvent id="reminder" attachedToRef="review">
      <bpmn:timerEventDefinition id="reminderTimer" />
    </bpmn:boundaryEvent>
    <bpmn:endEvent id="done" />
    <bpmn:sequenceFlow id="flow" sourceRef="review" targetRef="done">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">approved</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
  </bpmn:process>
</bpmn:definitions>`;

const loadHeld = async (xml = HELD) => {
  const moddle = new BpmnModdle();
  const { rootElement } = await moddle.fromXML(xml);
  const definitions = rootElement as ModdleElement;
  return {
    moddle: moddle as unknown as Moddle,
    definitions,
    element: (id: string) => flowElement(definitions, id),
    serialize: () => moddle.toXML(definitions, { format: true }).then(written => written.xml),
  };
};

describe("a parameter BPMN holds", () => {
  it("reads BPMN's own attributes and the text of its elements, along a path where there is one", async () => {
    const { definitions, element } = await loadHeld();

    expect(readValues(element("deadline"), definitions, TIMER_BOUNDARY_EVENT, CANCEL_ACTIVITY)).toEqual([ "false" ]);
    expect(readValues(element("deadline"), definitions, TIMER_BOUNDARY_EVENT, TIME_DURATION)).toEqual([ "P5D" ]);
    expect(readValues(element("flow"), definitions, SEQUENCE_FLOW, CONDITION)).toEqual([ "approved" ]);
  });

  it("reads an attribute the document leaves out as BPMN's default, and nothing else it leaves out", async () => {
    // The parser stores nothing for it, and the node type defaults cancelActivity to true as BPMN does
    const { definitions, element } = await loadHeld();

    expect(readValues(element("reminder"), definitions, TIMER_BOUNDARY_EVENT, CANCEL_ACTIVITY)).toEqual([ "true" ]);
    expect(readValues(element("reminder"), definitions, TIMER_BOUNDARY_EVENT, TIME_DURATION)).toEqual([]);
    const extra = parameter("extra", { xmlLocation: "bpmnAttribute" });
    expect(readValues(element("deadline"), definitions, TIMER_BOUNDARY_EVENT, extra)).toEqual([]);
  });

  it("still reads the default once it is chosen, saved and loaded again", async () => {
    // Chosen, the default is left out of the document
    const { moddle, definitions, element, serialize } = await loadHeld();

    apply(parameterEdit(moddle, element("deadline"), definitions, TIMER_BOUNDARY_EVENT, CANCEL_ACTIVITY, [ "true" ]));

    const written = await serialize();
    expect(written).toContain('<bpmn:boundaryEvent id="deadline" attachedToRef="review">');
    const reloaded = await loadHeld(written);
    expect(readValues(reloaded.element("deadline"), reloaded.definitions, TIMER_BOUNDARY_EVENT, CANCEL_ACTIVITY))
      .toEqual([ "true" ]);
  });

  it("reads nothing, and writes nothing, along a path the element does not have", async () => {
    const { moddle, definitions, element } = await loadHeld();

    expect(readValues(element("review"), definitions, TIMER_BOUNDARY_EVENT, TIME_DURATION)).toEqual([]);
    expect(parameterEdit(moddle, element("review"), definitions, TIMER_BOUNDARY_EVENT, TIME_DURATION, [ "P1D" ]))
      .toBeUndefined();
  });

  it("follows a path only in the case a document writes its steps, as the server does", async () => {
    const { moddle, definitions, element } = await loadHeld();
    const miscased = parameter("timeDuration", { xmlLocation: "bpmnElement", xmlPath: [ "TimerEventDefinition" ] });

    expect(readValues(element("deadline"), definitions, TIMER_BOUNDARY_EVENT, miscased)).toEqual([]);
    expect(parameterEdit(moddle, element("deadline"), definitions, TIMER_BOUNDARY_EVENT, miscased, [ "P1D" ]))
      .toBeUndefined();
    apply(parameterEdit(moddle, element("deadline"), definitions, TIMER_BOUNDARY_EVENT, TIME_DURATION, [ "P1D" ]));
    expect(readValues(element("deadline"), definitions, TIMER_BOUNDARY_EVENT, TIME_DURATION)).toEqual([ "P1D" ]);
  });

  it("writes an attribute BPMN declares in the type BPMN declares it with", async () => {
    const { moddle, definitions, element, serialize } = await loadHeld();
    const quantity = parameter("completionQuantity", { dataType: "long", xmlLocation: "bpmnAttribute" });

    apply(parameterEdit(moddle, element("reminder"), definitions, TIMER_BOUNDARY_EVENT, CANCEL_ACTIVITY, [ "FALSE" ]));
    apply(parameterEdit(moddle, element("deadline"), definitions, TIMER_BOUNDARY_EVENT, CANCEL_ACTIVITY, []));
    apply(parameterEdit(moddle, element("review"), definitions, SEQUENCE_FLOW, quantity, [ "2" ]));

    expect(element("reminder").cancelActivity).toBe(false);
    expect(element("review").completionQuantity).toBe(2);
    const written = await serialize();
    expect(written).toContain('<bpmn:boundaryEvent id="reminder" cancelActivity="false" attachedToRef="review">');
    expect(written).toContain('<bpmn:boundaryEvent id="deadline" attachedToRef="review">');
    expect(written).toContain('completionQuantity="2"');
    expect(readValues(element("deadline"), definitions, TIMER_BOUNDARY_EVENT, CANCEL_ACTIVITY)).toEqual([ "true" ]);
  });

  it("reads and writes a list in an attribute BPMN does not declare, joined with commas", async () => {
    const { moddle, definitions, element, serialize } = await loadHeld();
    const extra = parameter("extra", { xmlLocation: "bpmnAttribute", maxValues: 0 });

    expect(commaSeparated(SEQUENCE_FLOW, extra)).toBe(true);
    expect(readValues(element("review"), definitions, SEQUENCE_FLOW, extra)).toEqual([ "a", "b" ]);
    apply(parameterEdit(moddle, element("review"), definitions, SEQUENCE_FLOW, extra, [ "c", " d " ]));

    expect(await serialize()).toContain('extra="c, d"');
  });

  it("writes an expression as a formal expression holding the value as its body", async () => {
    const { moddle, definitions, element, serialize } = await loadHeld();

    apply(parameterEdit(moddle, element("reminder"), definitions, TIMER_BOUNDARY_EVENT, TIME_DURATION, [ "P1D" ]));
    apply(parameterEdit(moddle, element("flow"), definitions, SEQUENCE_FLOW, CONDITION, [ "rejected" ]));
    apply(parameterEdit(moddle, element("deadline"), definitions, TIMER_BOUNDARY_EVENT, TIME_DURATION, []));

    const written = await serialize();
    expect(written).toContain('<bpmn:timeDuration xsi:type="bpmn:tFormalExpression">P1D</bpmn:timeDuration>');
    expect(written).toContain(
      '<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">rejected</bpmn:conditionExpression>');
    expect(written).not.toContain("P5D");
    // And the server's parser, like this module, finds them again in the saved document
    const reloaded = await loadHeld(written);
    expect(readValues(reloaded.element("reminder"), reloaded.definitions, TIMER_BOUNDARY_EVENT, TIME_DURATION))
      .toEqual([ "P1D" ]);
    expect(readValues(reloaded.element("flow"), reloaded.definitions, SEQUENCE_FLOW, CONDITION))
      .toEqual([ "rejected" ]);
  });

  it("writes an element holding plain text as that text", async () => {
    const { moddle, definitions, element, serialize } = await loadHeld();
    const script = parameter("script", { xmlLocation: "bpmnElement" });

    expect(readValues(element("compute"), definitions, SEQUENCE_FLOW, script)).toEqual([ "sum" ]);
    apply(parameterEdit(moddle, element("compute"), definitions, SEQUENCE_FLOW, script, [ "product" ]));

    expect(await serialize()).toContain("<bpmn:script>product</bpmn:script>");
  });

  it("writes a list of expressions as one element per value", () => {
    // No BPMN element takes a list of expressions, so the descriptor is a stand-in
    const moddle: Moddle = { create: (_type, attributes = {}) => attributes, createAny: () => ({}) };
    const holder: ModdleElement = {
      $descriptor: { properties: [ { name: "guards", type: "Expression", isMany: true } ] },
    };
    const guards = parameter("guards", { xmlLocation: "bpmnElement", maxValues: 0 });

    const edit = parameterEdit(moddle, holder, {}, SEQUENCE_FLOW, guards, [ "a", "b" ]);

    expect(edit?.properties).toEqual({ guards: [ { body: "a" }, { body: "b" } ] });
  });

  it("keeps BPMN's own markup when the node is made to mean another entry", async () => {
    const { moddle, definitions, element, serialize } = await loadHeld();
    const other = type("OtherBoundaryEvent", { xmlElement: "bpmn:boundaryEvent", parameters: [] });

    apply(typeEdit(moddle, element("deadline"), definitions, TIMER_BOUNDARY_EVENT, other));

    const written = await serialize();
    expect(written).toContain('cancelActivity="false"');
    expect(written).toContain("P5D");
  });
});

// The shape of the content-editing step's field declarations.
const VALUE = parameter("value", { minValues: 1 });
const CHOICES = parameter("choices", {
  dataType: "structure", maxValues: 0, keyParameter: "value", parameters: [ VALUE, parameter("label") ],
});
const APPLIES_WHEN = parameter("appliesWhen", {
  dataType: "structure", parameters: [ parameter("property"), parameter("values", { maxValues: 0 }) ],
});
const FIELDS = parameter("fields", {
  dataType: "structure",
  maxValues: 0,
  keyParameter: "name",
  parameters: [ parameter("name"), parameter("label"), CHOICES, APPLIES_WHEN ],
});
const UPDATE_CONTENT = type("UpdateContent", { xmlExtensionElement: "updateContent", parameters: [ FIELDS ] });

const structured = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:iap="${IAP_NS}"
                  id="Definitions_3" targetNamespace="https://iap.uhndata.io/test">
  <bpmn:process id="edit" isExecutable="true">
    <bpmn:serviceTask id="promote">
      <bpmn:extensionElements>
        <iap:updateContent keep="me">
          <iap:fields name="workflow" label=" Workflow " hint="kept">
            <iap:choices value="a" label="A" />
            <iap:appliesWhen property="dataType"><iap:values>long</iap:values></iap:appliesWhen>
          </iap:fields>
          <iap:fields name="status" label="Status" />
          <iap:other />
        </iap:updateContent>
      </bpmn:extensionElements>
    </bpmn:serviceTask>
    <bpmn:startEvent id="requested" />
  </bpmn:process>
</bpmn:definitions>`;

const WORKFLOW = {
  name: [ "workflow" ],
  label: [ "Workflow" ],
  choices: [ { value: [ "a" ], label: [ "A" ] } ],
  appliesWhen: [ { property: [ "dataType" ], values: [ "long" ] } ],
};
const STATUS = { name: [ "status" ], label: [ "Status" ], choices: [], appliesWhen: [] };

describe("a structured parameter", () => {
  it("reads every level of its records", async () => {
    const { definitions, task, start } = await load(DEFAULT_IAP_PREFIX, structured);

    expect(readValues(task, definitions, UPDATE_CONTENT, FIELDS)).toEqual([ WORKFLOW, STATUS ]);
    expect(readValues(start, definitions, UPDATE_CONTENT, FIELDS)).toEqual([]);
    expect(readValues(task, definitions, UPDATE_CONTENT, TO_STATE)).toEqual([]);
  });

  it("edits one field of a nested record in place, keeping what else its element holds", async () => {
    const { moddle, definitions, task, serialize } = await load(DEFAULT_IAP_PREFIX, structured);
    const wanted = [ { ...WORKFLOW, choices: [ { value: [ "a" ], label: [ "Ay" ] } ] }, STATUS ];

    const edit = parameterEdit(moddle, task, definitions, UPDATE_CONTENT, FIELDS, wanted);

    expect(edit?.properties).toEqual({ label: "Ay" });
    apply(edit);
    const written = await serialize();
    expect(written).toContain('<iap:choices value="a" label="Ay" />');
    expect(written).toContain('hint="kept"');
    expect(written).toContain('keep="me"');
    expect(written).toContain("<iap:other />");
    expect(readValues(task, definitions, UPDATE_CONTENT, FIELDS)).toEqual(wanted);
  });

  it("edits a list and a single value of one record in one step", async () => {
    const { moddle, definitions, task } = await load(DEFAULT_IAP_PREFIX, structured);
    const wanted = [ WORKFLOW, { ...STATUS, label: [ "State" ], choices: [ { value: [ "x" ] } ] } ];

    apply(parameterEdit(moddle, task, definitions, UPDATE_CONTENT, FIELDS, wanted));

    expect(readValues(task, definitions, UPDATE_CONTENT, FIELDS))
      .toEqual([ WORKFLOW, { ...STATUS, label: [ "State" ], choices: [ { value: [ "x" ], label: [] } ] } ]);
  });

  it("adds, removes and reorders records, keeping the elements of the ones left unchanged", async () => {
    const { moddle, definitions, task, serialize } = await load(DEFAULT_IAP_PREFIX, structured);
    const added = { name: [ "owner" ], appliesWhen: [ { property: [ "kind" ], values: [ "a", "b" ] } ] };

    apply(parameterEdit(moddle, task, definitions, UPDATE_CONTENT, FIELDS, [ added, WORKFLOW ]));

    const written = await serialize();
    expect(written).toContain('hint="kept"');
    expect(written).not.toContain("status");
    expect(written).toContain("<iap:other />");
    expect(written.indexOf('name="owner"')).toBeLessThan(written.indexOf('name="workflow"'));
    expect(readValues(task, definitions, UPDATE_CONTENT, FIELDS)).toEqual([
      { name: [ "owner" ], label: [], choices: [], appliesWhen: [ { property: [ "kind" ], values: [ "a", "b" ] } ] },
      WORKFLOW,
    ]);
    // Reread from the written XML, as the server would
    const reloaded = await load(DEFAULT_IAP_PREFIX, written);
    expect(readValues(reloaded.task, reloaded.definitions, UPDATE_CONTENT, FIELDS)).toHaveLength(2);
  });

  it("moves two records past each other in one step", async () => {
    const { moddle, definitions, task } = await load(DEFAULT_IAP_PREFIX, structured);

    const edit = parameterEdit(moddle, task, definitions, UPDATE_CONTENT, FIELDS, [ STATUS, WORKFLOW ]);

    expect(edit?.moddleElement.$descriptor?.ns?.localName).toBe("updateContent");
    apply(edit);
    expect(readValues(task, definitions, UPDATE_CONTENT, FIELDS)).toEqual([ STATUS, WORKFLOW ]);
  });

  it("removes a record, and adds one where the diagram holds none", async () => {
    const { moddle, definitions, task } = await load(DEFAULT_IAP_PREFIX, structured);

    apply(parameterEdit(moddle, task, definitions, UPDATE_CONTENT, FIELDS, [ WORKFLOW ]));
    expect(readValues(task, definitions, UPDATE_CONTENT, FIELDS)).toEqual([ WORKFLOW ]);

    const { definitions: plainDefinitions, task: bare } = await load(DEFAULT_IAP_PREFIX, structured
      .replace(/<iap:updateContent keep="me">[\s\S]*<\/iap:updateContent>/, "<iap:updateContent />"));
    apply(parameterEdit(moddle, bare, plainDefinitions, UPDATE_CONTENT, FIELDS, [ { name: [ "status" ] } ]));
    expect(readValues(bare, plainDefinitions, UPDATE_CONTENT, FIELDS))
      .toEqual([ { ...STATUS, label: [] } ]);
  });

  it("reads a field a record leaves out as one left empty", async () => {
    const { moddle, definitions, task } = await load(DEFAULT_IAP_PREFIX, structured);

    const edit = parameterEdit(moddle, task, definitions, UPDATE_CONTENT, FIELDS,
      [ WORKFLOW, { name: [ "status" ], label: [ "State" ] } ]);

    expect(edit?.properties).toEqual({ label: "State" });
  });

  it("reads a structure that declares no fields as records holding nothing", async () => {
    const { moddle, definitions, task } = await load(DEFAULT_IAP_PREFIX, structured);
    const bare = parameter("fields", { dataType: "structure", maxValues: 0 });
    const holder = type("UpdateContent", { xmlExtensionElement: "updateContent", parameters: [ bare ] });

    expect(readValues(task, definitions, holder, bare)).toEqual([ {}, {} ]);
    expect(parameterEdit(moddle, task, definitions, holder, bare, [ { name: [ "x" ] } ])).toBeDefined();
  });

  it("writes nothing when the records are what the diagram holds, once settled", async () => {
    const { moddle, definitions, task } = await load(DEFAULT_IAP_PREFIX, structured);
    const spaced = [ { ...WORKFLOW, label: [ " Workflow  " ] }, STATUS, {} ];

    expect(parameterEdit(moddle, task, definitions, UPDATE_CONTENT, FIELDS, spaced)).toBeUndefined();
  });

  it("writes every level of a record into the entry's element a change of kind brought in", async () => {
    const { moddle, definitions, start, serialize } = await load(DEFAULT_IAP_PREFIX, structured);

    apply(typeEdit(moddle, start, definitions, undefined, UPDATE_CONTENT));
    apply(parameterEdit(moddle, start, definitions, UPDATE_CONTENT, FIELDS, [ WORKFLOW ]));

    const reloaded = await load(DEFAULT_IAP_PREFIX, await serialize());
    expect(readValues(reloaded.start, reloaded.definitions, UPDATE_CONTENT, FIELDS)).toEqual([ WORKFLOW ]);
  });

  it("writes nothing for a structure declared where the server does not read one", async () => {
    const { moddle, definitions, task } = await load(DEFAULT_IAP_PREFIX, structured);
    const outside = { ...FIELDS, xmlLocation: "attribute" as const };

    expect(readValues(task, definitions, UPDATE_CONTENT, outside)).toEqual([]);
    expect(parameterEdit(moddle, task, definitions, UPDATE_CONTENT, outside, [ STATUS ])).toBeUndefined();
    expect(parameterEdit(moddle, task, definitions, type("Bare", { parameters: [ FIELDS ] }), FIELDS, [ STATUS ]))
      .toBeUndefined();
  });
});
