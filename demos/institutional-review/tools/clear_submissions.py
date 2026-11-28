# Copyright 2026 DATA @ UHN. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Deletes every submission on an instance, to seed it again. PRETOTYPE.

    python3 demos/institutional-review/tools/clear_submissions.py http://localhost:8083
"""

import sys, requests
URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8083"
s = requests.Session(); s.post(f"{URL}/j_security_check", data={"j_username": "admin", "j_password": "admin", "j_validate": "true"}).raise_for_status()
def walk(path, depth):
    node = s.get(f"{URL}{path}.1.json").json()
    for name, child in node.items():
        if not isinstance(child, dict): continue
        if child.get("jcr:primaryType") == "sub:Submission":
            yield f"{path}/{name}", child.get("title")
        elif child.get("jcr:primaryType") == "sling:Folder" and depth < 3:
            yield from walk(f"{path}/{name}", depth + 1)
for path, title in list(walk("/Submissions", 0)):
    r = s.delete(f"{URL}{path}", params={"permanent": "true"}); print(r.status_code, path, title)
