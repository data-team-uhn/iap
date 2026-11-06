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

import { createExtensionLoader } from "@iap/ui-extension/extensionLoader";

// The extension point declaring the answer components. A deployment adds a question type by
// registering an `ext:Extension` on this point and shipping the asset it names.
export const ANSWER_COMPONENT_POINT = "AnswerComponent";

const loader = createExtensionLoader(ANSWER_COMPONENT_POINT, "answer components");

// Loads every answer component declared on the point. A load that succeeded is not repeated.
export const loadAnswerComponents = loader.load;

// Whether the load of the answer components has settled, whether or not it succeeded. They arrive
// over HTTP, so a field's first render comes before any of them is registered.
export const useAnswerComponents = loader.useLoaded;
