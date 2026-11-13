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

import { replaying } from "@iap/frontend-commons/keyframes";

describe("replaying", () => {
  const PULSE = { "50%": { scale: "1.03" } };

  it("names the keyframes anew each time the count changes", () => {
    expect(replaying("pulse", 0, PULSE)).toEqual({ name: "pulse0", keyframes: { "@keyframes pulse0": PULSE } });
    expect(replaying("pulse", 1, PULSE).name).toBe("pulse1");
    expect(replaying("pulse", 2, PULSE).name).toBe("pulse0");
  });
});
