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

// Vocabulary entries and IAP elements as the editor sees them, for tests that need a few without
// parsing a documented vocabulary.

import { IAP_NS } from "@iap/workflows/moddle";
import type { ModdleElement } from "@iap/workflows/moddle";
import type { FlowNodeType, Parameter } from "@iap/workflows/workflowTypes";

// A single optional text parameter in the entry's own element, unless told otherwise.
export const parameter = (name: string, overrides: Partial<Parameter> = {}): Parameter => ({
  name,
  label: name,
  dataType: "text",
  minValues: 0,
  maxValues: 1,
  xmlLocation: "extensionElement",
  xmlPath: [],
  options: [],
  ...overrides,
});

// A service task entry taking no parameters, unless told otherwise.
export const type = (name: string, overrides: Partial<FlowNodeType> = {}): FlowNodeType => ({
  name,
  label: name,
  category: [],
  priority: 10,
  xmlElement: "bpmn:serviceTask",
  jcrNodeType: "wf:Activity",
  parameters: [],
  ...overrides,
});

// The state-changing step's entry, and the entries a service task carrying it could otherwise mean.
export const HOST_TAG = parameter("hostTag", { label: "Host tag", xmlLocation: "attribute" });
export const TO_STATE = parameter("toState", { label: "To state", minValues: 1 });
export const FROM_STATES = parameter("fromStates", { label: "From states", minValues: 1, maxValues: 0 });

export const SET_STATE = type("SetState", {
  label: "Set state",
  xmlExtensionElement: "setState",
  parameters: [ HOST_TAG, TO_STATE, FROM_STATES ],
});

export const RETIRE_ACTIVE_VERSIONS = type("RetireActiveVersions", {
  label: "Retire active versions",
  xmlExtensionElement: "retireActiveVersions",
  parameters: [ HOST_TAG ],
});

// The generic fallback entry: no element of its own, so everything it carries is an attribute.
export const SERVICE_TASK = type("ServiceTask", {
  label: "Service task",
  priority: 0,
  parameters: [ HOST_TAG, parameter("handler", { label: "Handler" }) ],
});

// An element as moddle hands it over, written as `prefix:name` in the namespace given. A package is
// passed for a type one declares.
export const moddleElement = (
  name: string,
  extra: Partial<ModdleElement>,
  { prefix, uri, pkg }: { prefix: string; uri?: string; pkg?: { xml?: { tagAlias?: string } } }
): ModdleElement => ({
  $type: `${prefix}:${name}`,
  $descriptor: { ns: { prefix, localName: name, uri }, ...pkg && { $pkg: pkg } },
  ...extra,
});

// An element in the IAP namespace, or in another namespace when told one.
export const iapElement = (
  name: string,
  extra: Partial<ModdleElement> = {},
  { prefix = "iap", uri = IAP_NS } = {}
): ModdleElement => moddleElement(name, extra, { prefix, uri });

// A BPMN business object as bpmn-js hands it over. It carries its model's own name (`ServiceTask`), and
// the package that says the document writes it with a lowercase initial (`serviceTask`).
export const bpmnElement = (modelName: string, extra: Partial<ModdleElement> = {}): ModdleElement =>
  moddleElement(modelName, extra, { prefix: "bpmn", pkg: { xml: { tagAlias: "lowerCase" } } });

// What a flow node holding these elements under its `bpmn:extensionElements` carries.
export const carrying = (...values: ModdleElement[]) => ({ extensionElements: { values } });
