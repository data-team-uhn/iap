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

import { carriesTag } from "@iap/tags/carriesTag";

describe("carriesTag", () => {
  it("finds a tag placed on the node", () => {
    expect(carriesTag({ tags: [ "draft", "incomplete" ] }, "incomplete")).toBe(true);
  });

  it("finds a tag inherited from an ancestor", () => {
    expect(carriesTag({ tags: [ "draft" ], inheritedTags: [ "incomplete" ] }, "incomplete")).toBe(true);
  });

  it("finds a tag computed from the node's own content", () => {
    expect(carriesTag({ tags: [ "draft" ], computedTags: [ "incomplete" ] }, "incomplete")).toBe(true);
  });

  it("finds a tag aggregated up from below the node", () => {
    expect(carriesTag({ tags: [ "draft" ], aggregatedTags: [ "incomplete" ] }, "incomplete")).toBe(true);
  });

  it("finds a tag carried in a property other than the first", () => {
    expect(carriesTag({ tags: [ "draft" ], inheritedTags: [ "other" ], computedTags: [ "incomplete" ] },
      "incomplete")).toBe(true);
  });

  it("reads a single-valued property, which is serialized as a bare string", () => {
    expect(carriesTag({ tags: "incomplete" }, "incomplete")).toBe(true);
  });

  it("finds nothing on a node that does not carry the tag, or on no node at all", () => {
    expect(carriesTag({ tags: [ "draft" ] }, "incomplete")).toBe(false);
    expect(carriesTag(undefined, "incomplete")).toBe(false);
  });
});
