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

// bpmn-moddle ships no types for its entry point (bpmn-js, which bundles it, declares every moddle
// object as `any`), so the parts the round-trip test drives are declared here. It is the real reader
// and writer bpmn-js uses, which is the point of testing against it rather than against a stand-in.
declare module "bpmn-moddle" {
  export class BpmnModdle {
    fromXML(xml: string): Promise<{ rootElement: unknown }>;
    toXML(element: unknown, options?: { format?: boolean }): Promise<{ xml: string }>;
    create(type: string, attributes?: Record<string, unknown>): unknown;
    createAny(type: string, ns: string, attributes?: Record<string, unknown>): unknown;
  }
}
