#!/usr/bin/env python3
"""Generates Kotlin TL types for the allow-listed Telegram API methods (stdlib only).

Usage: python3 tools/tlgen/tlgen.py   (run from the repository root)
Reads tools/tlgen/schema.tl and tools/tlgen/roots.txt, writes
app/src/main/java/com/smaznet/teleplay/mtproto/tl/generated/{Types,Functions}.kt
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(ROOT, "..", ".."))
OUT = os.path.join(REPO, "app/src/main/java/com/smaznet/teleplay/mtproto/tl/generated")
PKG = "com.smaznet.teleplay.mtproto.tl.generated"

KEYWORDS = {"as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while"}
PRIMS = {"int": "Int", "long": "Long", "double": "Double", "string": "String", "bytes": "ByteArray",
         "int128": "ByteArray", "int256": "ByteArray", "Bool": "Boolean", "true": "Boolean",
         "Int": "Int", "Long": "Long", "Double": "Double", "String": "String", "Bytes": "ByteArray",
         "Int128": "ByteArray", "Int256": "ByteArray"}


class Param:
    def __init__(self, name, typ):
        self.name, self.raw = name, typ
        self.flag_field = None  # (flags field name, bit)
        self.is_flags = typ == "#"
        t = typ
        m = re.match(r"^(\w+)\.(\d+)\?(.+)$", t)
        if m:
            self.flag_field, self.bit = m.group(1), int(m.group(2))
            t = m.group(3)
        self.type = t

    @property
    def kname(self):
        n = self.name
        return "`%s`" % n if n in KEYWORDS else n


class Ctor:
    def __init__(self, name, cid, params, result, is_fn):
        self.name, self.cid, self.params, self.result, self.is_fn = name, cid, params, result, is_fn
        self.cls = ("F_" if is_fn else "C_") + name.replace(".", "_")


def parse(path):
    ctors, fns = [], []
    is_fn = False
    for line in open(path, encoding="utf-8"):
        line = line.split("//")[0].strip()
        if line == "---functions---":
            is_fn = True
            continue
        if not line or line.startswith("---") or not line.endswith(";"):
            continue
        m = re.match(r"^([\w.]+)#([0-9a-f]{1,8})\s*(.*?)\s*=\s*([\w.<>%! ]+);$", line)
        if not m:
            continue
        name, cid, rest, result = m.groups()
        if name in ("vector", "int", "long", "double", "string", "bytes", "int128", "int256", "true") or "{t:Type}" in rest:
            continue
        params = []
        for tok in rest.split():
            if tok.startswith("{"):
                continue
            if ":" not in tok:
                continue
            pn, pt = tok.split(":", 1)
            params.append(Param(pn, pt))
        c = Ctor(name, int(cid, 16), params, result.strip(), is_fn)
        (fns if is_fn else ctors).append(c)
    return ctors, fns


def base_types(t):
    """Boxed TL type names referenced by a type expression."""
    t = t.lstrip("!%")
    m = re.match(r"^[Vv]ector<(.+)>$", t)
    if m:
        return base_types(m.group(1))
    return [t]


def kt_type(t):
    t = t.lstrip("%")
    if t.startswith("!"):
        return "TlObject"
    m = re.match(r"^[Vv]ector<(.+)>$", t)
    if m:
        return "List<%s>" % kt_type(m.group(1))
    if t in PRIMS:
        return PRIMS[t]
    return "T_" + t.replace(".", "_")


def write_expr(t, v, w="wr"):
    bare = t.startswith("%")
    t = t.lstrip("%")
    if t.startswith("!"):
        return "%s.serialize(%s)" % (v, w)
    m = re.match(r"^[Vv]ector<(.+)>$", t)
    if m:
        inner = m.group(1)
        fn = "bareVector" if bare else "vector"
        return "%s.%s(%s) { %s }" % (w, fn, v, write_expr(inner, "it", w))
    if t in ("int", "Int"):
        return "%s.int32(%s)" % (w, v)
    if t in ("long", "Long"):
        return "%s.int64(%s)" % (w, v)
    if t in ("double", "Double"):
        return "%s.double(%s)" % (w, v)
    if t in ("string", "String"):
        return "%s.string(%s)" % (w, v)
    if t in ("bytes", "Bytes"):
        return "%s.bytes(%s)" % (w, v)
    if t in ("int128", "Int128"):
        return "%s.int128(%s)" % (w, v)
    if t in ("int256", "Int256"):
        return "%s.int256(%s)" % (w, v)
    if t == "Bool":
        return "%s.bool(%s)" % (w, v)
    return "%s.serialize(%s)" % (v, w)


def read_expr(t, r="r"):
    bare = t.startswith("%")
    t = t.lstrip("%")
    m = re.match(r"^[Vv]ector<(.+)>$", t)
    if m:
        fn = "bareVector" if bare else "vector"
        return "%s.%s { %s }" % (r, fn, read_expr(m.group(1), r))
    if t in ("int", "Int"):
        return "%s.int32()" % r
    if t in ("long", "Long"):
        return "%s.int64()" % r
    if t in ("double", "Double"):
        return "%s.double()" % r
    if t in ("string", "String"):
        return "%s.string()" % r
    if t in ("bytes", "Bytes"):
        return "%s.bytes()" % r
    if t in ("int128", "Int128"):
        return "%s.int128()" % r
    if t in ("int256", "Int256"):
        return "%s.int256()" % r
    if t == "Bool":
        return "%s.bool()" % r
    return "T_%s.read(%s)" % (t.replace(".", "_"), r)


def main():
    ctors, fns = parse(os.path.join(ROOT, "schema.tl"))
    layer = None
    for line in open(os.path.join(ROOT, "schema.tl"), encoding="utf-8"):
        m = re.match(r"^//\s*LAYER\s+(\d+)", line)
        if m:
            layer = m.group(1)
    roots = [l.strip() for l in open(os.path.join(ROOT, "roots.txt")) if l.strip() and not l.startswith("#")]
    by_type = {}
    for c in ctors:
        by_type.setdefault(c.result, []).append(c)
    fn_by_name = {f.name: f for f in fns}
    missing = [r for r in roots if r not in fn_by_name]
    if missing:
        sys.exit("methods not in schema: %s" % missing)
    roots_fns = [fn_by_name[r] for r in roots]

    need_ser, need_de = set(), set()

    def visit(t, ser):
        for bt in base_types(t):
            if bt in PRIMS or bt.startswith("!"):
                continue
            s = need_ser if ser else need_de
            if bt in s:
                continue
            s.add(bt)
            if bt not in by_type:
                sys.exit("unknown type %s" % bt)
            for c in by_type[bt]:
                for p in c.params:
                    if not p.is_flags:
                        visit(p.type, ser)

    for f in roots_fns:
        for p in f.params:
            if not p.is_flags and not p.type.startswith("!"):
                visit(p.type, True)
        if f.result != "X":
            visit(f.result, False)

    os.makedirs(OUT, exist_ok=True)
    hdr = ("// GENERATED by tools/tlgen/tlgen.py from tools/tlgen/schema.tl (Telegram API layer %s). DO NOT EDIT.\n"
           "@file:Suppress(\"ClassName\", \"unused\", \"UNCHECKED_CAST\", \"RedundantSuppression\")\n"
           "package %s\n\nimport com.smaznet.teleplay.mtproto.tl.*\n\n") % (layer, PKG)
    out = [hdr, "const val TL_LAYER = %s\n\n" % layer]
    for tname in sorted(need_ser | need_de):
        cs = by_type[tname]
        ks = "T_" + tname.replace(".", "_")
        de = tname in need_de
        out.append("sealed class %s : TlObject {\n" % ks)
        if de:
            out.append("    companion object {\n        fun read(r: TlReader): %s {\n            return when (val id = r.int32()) {\n" % ks)
            for c in cs:
                out.append("                0x%08x.toInt() -> %s.read(r)\n" % (c.cid, c.cls))
            out.append("                else -> throw TlException(\"unknown constructor 0x${Integer.toHexString(id)} for %s\")\n            }\n        }\n    }\n" % tname)
        out.append("}\n\n")
        for c in cs:
            ser = tname in need_ser
            out.append(gen_ctor(c, ks, ser, de))
    open(os.path.join(OUT, "Types.kt"), "w").write("".join(out))

    fo = [hdr]
    for f in roots_fns:
        fo.append(gen_fn(f, need_de))
    open(os.path.join(OUT, "Functions.kt"), "w").write("".join(fo))
    print("types: %d  layer: %s" % (len(need_ser | need_de), layer))


def fields(c):
    return [p for p in c.params if not p.is_flags]


def decl(p):
    t = kt_type(p.type)
    if p.flag_field is not None:
        if p.type == "true":
            return "val %s: Boolean = false" % p.kname
        return "val %s: %s? = null" % (p.kname, t)
    return "val %s: %s" % (p.kname, t)


def gen_ctor(c, parent, ser, de):
    fs = fields(c)
    args = ", ".join(decl(p) for p in fs)
    cls = c.cls
    body = []
    # constructor params: required first would break named-arg order; keep schema order
    if ser:
        lines = ["        wr.int32(0x%08x.toInt())" % c.cid] if c.params or True else []
        for p in c.params:
            if p.is_flags:
                bits = []
                for q in c.params:
                    if q.flag_field == p.name:
                        cond = q.kname if q.type == "true" else "%s != null" % q.kname
                        bits.append("(if (%s) %d else 0)" % (cond, 1 << q.bit))
                lines.append("        wr.int32(%s)" % (" or ".join(bits) if bits else "0"))
            elif p.flag_field is not None:
                if p.type == "true":
                    continue
                lines.append("        %s?.let { %s }" % (p.kname, write_expr(p.type, "it")))
            else:
                lines.append("        " + write_expr(p.type, p.kname))
        body.append("    override fun serialize(wr: TlWriter) {\n%s\n    }\n" % "\n".join(lines))
    else:
        body.append("    override fun serialize(wr: TlWriter) = throw UnsupportedOperationException()\n")
    if de:
        lines = []
        for p in c.params:
            if p.is_flags:
                lines.append("            val %s = r.int32()" % p.name)
            elif p.flag_field is not None:
                test = "(%s and %d) != 0" % (p.flag_field, 1 << p.bit)
                if p.type == "true":
                    lines.append("            val %s = %s" % (p.kname, test))
                else:
                    lines.append("            val %s = if (%s) %s else null" % (p.kname, test, read_expr(p.type)))
            else:
                lines.append("            val %s = %s" % (p.kname, read_expr(p.type)))
        call = ", ".join("%s = %s" % (p.kname, p.kname) for p in fs)
        body.append("    companion object {\n        fun read(r: TlReader): %s {\n%s\n            return %s(%s)\n        }\n    }\n" %
                    (cls, "\n".join(lines).replace("            ", "            "), cls, call))
    ctor = "(%s)" % args if fs else "()"
    return "class %s%s : %s() {\n%s}\n\n" % (cls, ctor, parent, "".join(body)) if fs else \
        "class %s : %s() {\n%s}\n\n" % (cls, parent, "".join(body))


def gen_fn(f, need_de):
    fs = fields(f)
    generic = any(p.type.startswith("!") for p in fs)
    res = f.result
    rk = "Any" if res == "X" else kt_type(res)
    args = ", ".join(decl(p) for p in fs)
    lines = ["        wr.int32(0x%08x.toInt())" % f.cid]
    for p in f.params:
        if p.is_flags:
            bits = []
            for q in f.params:
                if q.flag_field == p.name:
                    cond = q.kname if q.type == "true" else "%s != null" % q.kname
                    bits.append("(if (%s) %d else 0)" % (cond, 1 << q.bit))
            lines.append("        wr.int32(%s)" % (" or ".join(bits) if bits else "0"))
        elif p.flag_field is not None:
            if p.type != "true":
                lines.append("        %s?.let { %s }" % (p.kname, write_expr(p.type, "it")))
        elif p.type.startswith("!"):
            lines.append("        %s.serialize(wr)" % p.kname)
        else:
            lines.append("        " + write_expr(p.type, p.kname))
    if generic:
        rd = "        // result type is that of the wrapped query\n        throw UnsupportedOperationException(\"read the inner query's result instead\")"
        fargs = args.replace("TlObject", "TlFunction<*>")
        r = "Any"
    else:
        rd = "        return " + read_expr(res)
        fargs = args
        r = rk
    return ("class %s(%s) : TlFunction<%s> {\n    override fun serialize(wr: TlWriter) {\n%s\n    }\n\n"
            "    override fun readResult(r: TlReader): %s {\n%s\n    }\n}\n\n") % (f.cls, fargs, r, "\n".join(lines), r, rd)


if __name__ == "__main__":
    main()
