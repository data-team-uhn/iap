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

import { conditionKeyOf } from "@iap/schemas/conditionModel";
import { indexQuestions } from "@iap/schemas/schemaVersionTreeModel";

import { withPaths } from "./schemaServer.fixture";

describe("conditionKeyOf", () => {
  const versionAt = (path: string, uuid: string) => withPaths(path, {
    "jcr:primaryType": "sch:SchemaVersion",
    intake: { "sling:resourceSuperType": "sch/Requirement", "sling:resourceType": "sch/FormRequirement",
      age: { "sling:resourceSuperType": "sch/FormItem", "sling:resourceType": "sch/Question", "jcr:uuid": uuid } },
  });
  const ofAge = (uuid: string, comparator = "is", extra: Record<string, unknown> = {}) => ({
    "jcr:primaryType": "cond:SingleCondition", "jcr:created": uuid, comparator, ...extra,
    operandA: { "jcr:primaryType": "cond:ConditionOperand", source: "answer", value: [ uuid ] },
    operandB: { value: [ "18" ], source: "literal" },
  });

  it("takes a copied condition for the same, though it refers to the copied question by another identifier", () => {
    const keyIn = (path: string, uuid: string) =>
      conditionKeyOf(ofAge(uuid), indexQuestions(versionAt(path, uuid)), path);
    const before = keyIn("/Schemas/s/v1", "uuid-1");
    const after = keyIn("/Schemas/s/v2", "uuid-2");

    expect(after).toBe(before);
    expect(before).toContain("\"age\"");
  });

  const ageQuestion = (uuid: string) =>
    ({ "sling:resourceSuperType": "sch/FormItem", "sling:resourceType": "sch/Question", "jcr:uuid": uuid });

  it("takes a condition for the same when the question it refers to moved", () => {
    const moved = withPaths("/Schemas/s/v2", {
      other: { "sling:resourceSuperType": "sch/Requirement", "sling:resourceType": "sch/FormRequirement",
        age: ageQuestion("uuid-2") },
    });

    expect(conditionKeyOf(ofAge("uuid-2"), indexQuestions(moved), "/Schemas/s/v2"))
      .toBe(conditionKeyOf(ofAge("uuid-1"), indexQuestions(versionAt("/Schemas/s/v1", "uuid-1")), "/Schemas/s/v1"));
  });

  it("refers to a question whose identifier another shares by where it stands", () => {
    const twice = withPaths("/Schemas/s/v1", {
      one: { "sling:resourceSuperType": "sch/Requirement", "sling:resourceType": "sch/FormRequirement",
        age: ageQuestion("uuid-1") },
      two: { "sling:resourceSuperType": "sch/Requirement", "sling:resourceType": "sch/FormRequirement",
        age: ageQuestion("uuid-3") },
    });

    expect(conditionKeyOf(ofAge("uuid-1"), indexQuestions(twice), "/Schemas/s/v1")).toContain("one/age");
  });

  it("tells conditions apart by what they store, whatever order it was written in", () => {
    const index = indexQuestions(versionAt("/Schemas/s/v1", "uuid-1"));

    expect(conditionKeyOf(ofAge("uuid-1", "is not"), index, "/Schemas/s/v1"))
      .not.toBe(conditionKeyOf(ofAge("uuid-1"), index, "/Schemas/s/v1"));
    expect(conditionKeyOf({ b: 1, a: 2 }, index, "/Schemas/s/v1"))
      .toBe(conditionKeyOf({ a: 2, b: 1 }, index, "/Schemas/s/v1"));
    // A question it cannot find stays as it is referred to
    expect(conditionKeyOf(ofAge("uuid-9"), index, "/Schemas/s/v1")).toContain("uuid-9");
  });
});
