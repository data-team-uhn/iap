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

import { spelledOutBooleans, type ModdleTypes } from "@iap/workflows/bpmnBooleans";
import { IAP_NS, type ModdleElement } from "@iap/workflows/moddle";

const moddle = new BpmnModdle() as unknown as ModdleTypes;

const diagram = (cancelActivity: string, urgent = "1") => `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:iap="${IAP_NS}"
                  id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="review" isExecutable="true">
    <bpmn:userTask id="task" iap:urgent="${urgent}"/>
    <bpmn:boundaryEvent id="deadline" attachedToRef="task" cancelActivity="${cancelActivity}"/>
    <bpmn:unknownElement isInterrupting="1"/>
  </bpmn:process>
</bpmn:definitions>`;

// What bpmn-moddle reads the boundary event's cancelActivity as
async function cancelActivityOf(xml: string): Promise<unknown> {
  const read = await new BpmnModdle().fromXML(xml) as unknown as { elementsById: Record<string, ModdleElement> };
  return read.elementsById.deadline.cancelActivity;
}

describe("spelledOutBooleans", () => {
  it("has bpmn-moddle read a BPMN boolean written as 1 as true", async () => {
    expect(await cancelActivityOf(spelledOutBooleans(diagram("1"), moddle))).toBe(true);
  });

  it("has bpmn-moddle read a BPMN boolean written as 0 as false", async () => {
    expect(await cancelActivityOf(spelledOutBooleans(diagram("0"), moddle))).toBe(false);
  });

  it("leaves an attribute no package declares as written", () => {
    expect(spelledOutBooleans(diagram("0"), moddle)).toContain('iap:urgent="1"');
  });

  it("leaves an element moddle does not know as written", () => {
    expect(spelledOutBooleans(diagram("0"), moddle)).toContain('isInterrupting="1"');
  });

  it("hands back the very text it was given when nothing is written as a digit", () => {
    const xml = diagram("true");

    expect(spelledOutBooleans(xml, moddle)).toBe(xml);
  });

  it("hands back a document that is not well-formed as it is, for the import to report", () => {
    const xml = "<bpmn:definitions cancelActivity=\"1\">";

    expect(spelledOutBooleans(xml, moddle)).toBe(xml);
  });

  it("passes over an element in no namespace", () => {
    const xml = "<definitions><boundaryEvent cancelActivity=\"1\"/></definitions>";

    expect(spelledOutBooleans(xml, moddle)).toBe(xml);
  });
});
