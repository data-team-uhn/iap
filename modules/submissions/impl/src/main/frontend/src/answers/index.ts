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

// Loads the answer components that ship with this module. Each one registers itself as it loads,
// so all this has to do is make sure they are evaluated: what a component recognizes, and how
// confidently, is stated where the component is rather than in a list here that would have to be
// kept in step with it.
//
// Nothing is registered twice by importing this more than once: a module is evaluated once, and the
// registry ignores a candidate it already holds. Load order only settles a tie in confidence, and
// the shipped components key off distinct data types, so there is none to settle.

import "./BooleanAnswer";
import "./ChoiceAnswer";
import "./DateAnswer";
import "./FileAnswer";
import "./NumberAnswer";
import "./TextAnswer";
