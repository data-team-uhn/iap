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
#

"""Fill a .docx template with docxtpl, keeping every bit of its formatting.

Templates are content a deployment edits, and the values are somebody's answers, so the Jinja
environment is sandboxed and strict: a template cannot reach Python internals through the
values, and a name nobody supplied fails the render instead of leaving a gap in the letter.
Values are XML-escaped, since docxtpl leaves that off by default and one ``&`` in an answer
otherwise produces a file Word refuses to open.
"""

from __future__ import annotations

import argparse
import io
import json
import resource
import sys
import zipfile
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import jinja2
from docx.opc.exceptions import PackageNotFoundError
from docxtpl import DocxTemplate
from jinja2.sandbox import SandboxedEnvironment
from lxml import etree

# How main() says the template was at fault, with the reason as its only output
EXIT_TEMPLATE_ERROR = 2


class TemplateError(ValueError):
    """The template cannot be rendered with this context, so the reply is a 422.

    The message is about the template (an undefined name, a misplaced tag), never about the
    worker's internals, so it is safe to hand back to whoever has to fix the template.
    """


def render(template: bytes, context: dict[str, Any]) -> bytes:
    """Render ``template`` with ``context``.

    @param template: the .docx template's bytes
    @param context: the values its tags refer to
    @return: the rendered .docx's bytes
    @raise TemplateError: when the template is not a .docx, or cannot be rendered with this
        context
    """
    document = DocxTemplate(io.BytesIO(template))
    try:
        document.init_docx()
    except (zipfile.BadZipFile, KeyError, ValueError, PackageNotFoundError) as exc:
        raise TemplateError("the template is not a readable .docx file") from exc

    # A fresh environment per render: docxtpl sets autoescape on the one it is given
    environment = _KeysFirstEnvironment(undefined=jinja2.StrictUndefined)
    try:
        document.render(context, jinja_env=environment, autoescape=True)
    except jinja2.TemplateError as exc:
        raise TemplateError(_describe(exc)) from exc
    except etree.XMLSyntaxError as exc:
        # A {%p %} or {%tr %} tag placed where it cuts an element in half renders to broken XML
        raise TemplateError(f"the template renders to malformed document XML: {exc}") from exc

    rendered = io.BytesIO()
    document.save(rendered)
    return rendered.getvalue()


class _KeysFirstEnvironment(SandboxedEnvironment):
    """Reads ``a.b`` on a mapping as its ``b`` key before any attribute of that name.

    The values are dicts keyed by the schema's names, and a question may be named ``items`` or
    ``update``. Jinja would print the dict's method of that name instead of the answer. With no
    such key, the attribute is still found, so ``a.items()`` keeps working.
    """

    def getattr(self, obj: Any, attribute: str) -> Any:
        if isinstance(obj, Mapping) and attribute in obj:
            return obj[attribute]
        return super().getattr(obj, attribute)


def _describe(error: jinja2.TemplateError) -> str:
    """Say what is wrong with the template in terms its author can act on."""
    if isinstance(error, jinja2.UndefinedError):
        return f"the template uses a value that was not provided: {error.message}"
    if isinstance(error, jinja2.TemplateSyntaxError):
        return f"the template has a tag Jinja cannot read: {error.message}"
    return f"the template could not be rendered: {error.message or type(error).__name__}"


def main(argv: list[str] | None = None) -> int:
    """Render one template from files, in a process of its own that the worker can kill.

    A template can loop or build strings without end, which the sandbox does not stop. Here it
    can only spend the memory it is given, and the worker bounds its time.

    @param argv: the template, context (JSON) and output paths, and the memory allowed
    @return: 0 once the output is written, or :data:`EXIT_TEMPLATE_ERROR` with the reason printed
    """
    parser = argparse.ArgumentParser(description="Render a .docx template with a JSON context.")
    parser.add_argument("template", type=Path)
    parser.add_argument("context", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--memory-bytes", type=int, required=True)
    args = parser.parse_args(argv)

    resource.setrlimit(resource.RLIMIT_AS, (args.memory_bytes, args.memory_bytes))
    try:
        rendered = render(args.template.read_bytes(), json.loads(args.context.read_text("utf-8")))
    except TemplateError as exc:
        print(exc)
        return EXIT_TEMPLATE_ERROR
    except MemoryError:
        print("the template needs more memory than one document is allowed")
        return EXIT_TEMPLATE_ERROR
    args.output.write_bytes(rendered)
    return 0


if __name__ == "__main__":
    sys.exit(main())
