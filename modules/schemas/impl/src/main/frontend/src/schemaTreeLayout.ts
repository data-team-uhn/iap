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

import { TOUCH_TARGET } from "@iap/frontend-commons/touchTarget";

// The widths, in theme spacing, that the pieces of a version's tree share to line up.

// The column a part's expand button stands in. On a wide screen, what the part holds is indented by this width plus
// the card's padding. The part's icon then lines up with what is under it.
export const EXPANDER_COLUMN = 4;

// The column a part's icon stands in, with the space after it. The part's heading and chips start after it, and so
// do a question's options.
export const ICON_COLUMN = 3.5;

// A touch target for a small icon button, whose negative margin keeps the button where it is drawn
export const TOUCH_REACH = { "@media (pointer: coarse)": { ...TOUCH_TARGET["@media (pointer: coarse)"], m: "-5px" } };
