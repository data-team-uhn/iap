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

import { IAP_NS } from "@iap/workflows/moddle";
import type { ModdleElement } from "@iap/workflows/moddle";
import { diagramKeyProblems, keyOf, keyProblems } from "@iap/workflows/structureKeys";

import { parameter, type } from "./vocabulary.fixture";

import type { Element } from "bpmn-js/lib/model/Types";

const VALUE = parameter("value");
const CHOICES = parameter("choices", {
  label: "Choices", dataType: "structure", maxValues: 0, keyParameter: "value", parameters: [ VALUE ],
});
const FIELDS = parameter("fields", {
  label: "Fields", dataType: "structure", maxValues: 0, keyParameter: "name", parameters: [ parameter("name"), CHOICES ],
});
const UPDATE_CONTENT = type("UpdateContent", { xmlExtensionElement: "updateContent", parameters: [ FIELDS ] });
const OTHER = type("Other", { xmlElement: "bpmn:startEvent", parameters: [ FIELDS ] });

const record = (name: string, ...choices: string[]) =>
  ({ name: [ name ], choices: choices.map(value => ({ value: [ value ] })) });

describe("keyProblems", () => {
  it("flags a later record of a key, comparing keys trimmed, and a record with content but no key", () => {
    const records = [ record("a"), record(" a "), { name: [ " " ], choices: [ { value: [ "x" ] } ] }, record("b") ];

    expect(keyProblems(FIELDS, records)).toEqual([ undefined, "repeated", "blank", undefined ]);
  });

  it("leaves a record with nothing in it alone, as a draft", () => {
    expect(keyProblems(FIELDS, [ {}, { name: [ "" ] } ])).toEqual([ undefined, undefined ]);
  });

  it("flags nothing in a structure with no key", () => {
    expect(keyProblems({ ...FIELDS, keyParameter: undefined }, [ record("a"), record("a") ]))
      .toEqual([ undefined, undefined ]);
    expect(keyOf({ ...FIELDS, keyParameter: undefined }, record("a"))).toBe("");
  });
});

const diagram = (fields: string) => `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:iap="${IAP_NS}"
                  id="Definitions_1" targetNamespace="https://iap.uhndata.io/test">
  <bpmn:process id="edit" isExecutable="true">
    <bpmn:serviceTask id="named" name="Edit it">
      <bpmn:extensionElements><iap:updateContent>${fields}</iap:updateContent></bpmn:extensionElements>
    </bpmn:serviceTask>
    <bpmn:serviceTask id="unnamed">
      <bpmn:extensionElements>
        <iap:updateContent><iap:fields name="a" /><iap:fields name="a" /></iap:updateContent>
      </bpmn:extensionElements>
    </bpmn:serviceTask>
    <bpmn:serviceTask id="plain" />
    <bpmn:startEvent id="start" />
  </bpmn:process>
</bpmn:definitions>`;

const problemsOf = async (fields: string) => {
  const { rootElement } = await new BpmnModdle().fromXML(diagram(fields));
  const definitions = rootElement as ModdleElement;
  const process = (definitions.rootElements as ModdleElement[])[0];
  const elements = (process.flowElements as ModdleElement[]).map(businessObject =>
    ({ id: businessObject.id, businessObject }) as unknown as Element);
  return diagramKeyProblems([ ...elements, { id: "bare" } ], definitions, [ UPDATE_CONTENT, OTHER ]);
};

describe("diagramKeyProblems", () => {
  it("names the element, by its name or else its id, and the key", async () => {
    const problems = await problemsOf('<iap:fields name="x" /><iap:fields name="x" /><iap:fields label="n"><iap:choices value="c" /></iap:fields>');

    expect(problems).toEqual([
      'Edit it: the key "x" of Fields is already in use.',
      "Edit it: a Fields has no key.",
      'unnamed: the key "a" of Fields is already in use.',
    ]);
  });

  it("looks inside the records, naming the one a structure is in", async () => {
    const problems = await problemsOf(
      '<iap:fields name="f"><iap:choices value="c" /><iap:choices value="c" /><iap:choices value="d" /></iap:fields>'
      + '<iap:fields><iap:choices value="e" /><iap:choices value="e" /></iap:fields>'
    );

    expect(problems).toEqual([
      "Edit it: a Fields has no key.",
      'Edit it, Fields "f": the key "c" of Choices is already in use.',
      'Edit it, Fields without a key: the key "e" of Choices is already in use.',
      'unnamed: the key "a" of Fields is already in use.',
    ]);
  });

  it("finds nothing wrong with sound keys", async () => {
    expect((await problemsOf('<iap:fields name="a" /><iap:fields name="b" />')).filter(line => !line.startsWith("unnamed")))
      .toEqual([]);
  });
});
