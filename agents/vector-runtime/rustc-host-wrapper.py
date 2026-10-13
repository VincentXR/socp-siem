#!/usr/bin/env python3
"""Allow musl host build scripts to dlopen libclang; keep target linkage intact."""
import os
import sys


def compiler_arguments(arguments):
    # Cargo passes --target only to target crates. Build scripts/proc macros run
    # on the Alpine host and otherwise default to static musl, which cannot
    # dlopen bindgen's libclang. Do not replace the upstream target rustflags.
    if any(arg == "--target" or arg.startswith("--target=") for arg in arguments):
        return list(arguments)
    return ["-C", "target-feature=-crt-static", *arguments]


if __name__ == "__main__":
    compiler, *arguments = sys.argv[1:]
    os.execvp(compiler, [compiler, *compiler_arguments(arguments)])
