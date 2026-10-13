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

import { createElement } from "react";

import BooleanParameterField from "./BooleanParameterField";
import LongParameterField from "./LongParameterField";
import TextParameterField from "./TextParameterField";

import type { ParameterFieldProps, ParameterWidget } from "./parameterWidget";

// The field each data type is edited in. `reference` and `principal` values are typed as text.
const FIELDS: ReadonlyMap<string, ParameterWidget> = new Map([
  [ "boolean", BooleanParameterField ],
  [ "long", LongParameterField ],
]);

// One editable value of the selected flow node, in the field for its parameter's type. A type with no
// field of its own is edited as text, the one shape every value can be shown and typed in.
export default function ParameterField(props: ParameterFieldProps) {
  return createElement(FIELDS.get(props.parameter.dataType) ?? TextParameterField, props);
}
