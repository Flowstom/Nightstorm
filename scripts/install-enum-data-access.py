#!/usr/bin/env python3
"""Wrap scalar JSON getters in enum generators, preserving their access paths and masks."""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
for path in sorted((root / "DataGenerator/src/main/java/net/minestom/generators").glob("*.java")):
    source = path.read_text()
    variables = re.findall(r"for\s*\(\s*([\w.]+)\s+(\w+)\s*:\s*\1\.values\(\)\s*\)", source)
    for _, variable in variables:
        call = re.compile(
            r'(\w+)\.addProperty\(("[^"\n]+"),\s*' + re.escape(variable)
            + r'\.(\w+)\(\)(?:\.(\w+))?(?:\s*&\s*(0x[\da-fA-F]+|\d+))?\s*\);'
        )

        def wrap(match):
            obj, key, getter, member, mask = match.groups()
            member_arg = '"' + member + '"' if member else "null"
            return f'{obj}.add({key}, EnumDataAccess.read({variable}, "{getter}", {member_arg}, {mask or "null"}));'

        source = call.sub(wrap, source)
    if source != path.read_text():
        path.write_text(source)
