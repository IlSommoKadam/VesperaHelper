#!/usr/bin/env python3
"""Build Android IpConfigStore binary for eth0 static config."""
import io
import struct
import sys


def write_utf(buf: io.BytesIO, s: str) -> None:
    data = s.encode("utf-8")
    buf.write(struct.pack(">H", len(data)))
    buf.write(data)


def build_ipconfig(ip: str, prefix: int, gw: str, dns_list: list[str], iface: str = "eth0") -> bytes:
    buf = io.BytesIO()
    buf.write(struct.pack(">I", 3))  # IpConfigStore version
    write_utf(buf, "ipAssignment")
    write_utf(buf, "STATIC")
    write_utf(buf, "linkAddress")
    write_utf(buf, ip)
    buf.write(struct.pack(">I", int(prefix)))
    write_utf(buf, "gateway")
    buf.write(struct.pack(">I", 0))  # no destination route
    buf.write(struct.pack(">I", 1))  # has gateway
    write_utf(buf, gw)
    for dns in dns_list:
        write_utf(buf, "dns")
        write_utf(buf, dns)
    write_utf(buf, "proxySettings")
    write_utf(buf, "NONE")
    write_utf(buf, "id")
    write_utf(buf, iface)
    write_utf(buf, "eos")
    return buf.getvalue()


def main() -> int:
    ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.1.4"
    prefix = int(sys.argv[2]) if len(sys.argv) > 2 else 24
    gw = sys.argv[3] if len(sys.argv) > 3 else "192.168.1.252"
    dns1 = sys.argv[4] if len(sys.argv) > 4 else "8.8.8.8"
    dns2 = sys.argv[5] if len(sys.argv) > 5 else "8.8.4.4"
    out = sys.argv[6] if len(sys.argv) > 6 else "ipconfig.txt"
    dns = [d for d in (dns1, dns2) if d and d != "-"]
    data = build_ipconfig(ip, prefix, gw, dns)
    with open(out, "wb") as f:
        f.write(data)
    print(f"wrote {len(data)} bytes -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
