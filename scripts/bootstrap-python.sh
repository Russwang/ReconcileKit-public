#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
[[ $(uname -s) == Darwin && $(uname -m) == arm64 ]] || { echo 'This portable bootstrap supports macOS arm64. Elsewhere provide CPython 3.13.16 via RK_PYTHON.' >&2; exit 2; }
name='cpython-3.13.16+20261003-aarch64-apple-darwin-install_only.tar.gz'
digest='d8975d7df4f08f7b1c7aafcdfacbddcec3d366415f2c1a72b2466b6850815933'
archive="$root/.tools/python-downloads/$name"
destination="$root/.tools/python-3.13.16"
mkdir -p "$root/.tools/python-downloads"
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 2 --connect-timeout 15 --max-time 180 --output "$archive.partial" "https://github.com/astral-sh/python-build-standalone/releases/download/20261003/cpython-3.13.16%2B20261003-aarch64-apple-darwin-install_only.tar.gz"
  [[ $(shasum -a 256 "$archive.partial" | cut -d ' ' -f 1) == "$digest" ]] || { echo 'Python archive checksum mismatch.' >&2; exit 1; }
  mv "$archive.partial" "$archive"
fi
[[ $(shasum -a 256 "$archive" | cut -d ' ' -f 1) == "$digest" ]] || { echo 'Python archive checksum mismatch.' >&2; exit 1; }
if [[ ! -e "$destination" ]]; then
  # The system Python only validates/extracts a verified archive; it is never replaced.
  python3 - "$archive" "$destination" <<'PY'
import pathlib, posixpath, sys, tarfile
archive, destination = map(pathlib.Path, sys.argv[1:])
with tarfile.open(archive) as tar:
    members = tar.getmembers()
    symbolic_paths = {posixpath.normpath(member.name) for member in members if member.issym()}
    for member in members:
        name = posixpath.normpath(member.name)
        if (not (name == 'python' or name.startswith('python/'))
                or member.name.startswith('/') or '..' in member.name.split('/')):
            raise SystemExit('Unsafe Python archive member')
        if any(str(parent) in symbolic_paths for parent in pathlib.PurePosixPath(name).parents):
            raise SystemExit('Python archive member traverses a symbolic link')
        if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
            raise SystemExit('Unsupported Python archive member')
        if member.issym() or member.islnk():
            target = posixpath.normpath(posixpath.join(posixpath.dirname(member.name), member.linkname)) if member.issym() else posixpath.normpath(member.linkname)
            if not (target == 'python' or target.startswith('python/')):
                raise SystemExit('Unsafe Python archive link')
            if member.islnk() and (target in symbolic_paths or any(str(parent) in symbolic_paths for parent in pathlib.PurePosixPath(target).parents)):
                raise SystemExit('Unsafe Python archive hard link')
    destination.mkdir()
    # Python 3.9's tarfile lacks the newer filter argument; validation above is mandatory.
    tar.extractall(destination)
PY
fi
[[ -x "$destination/python/bin/python3.13" ]] || { echo 'Python runtime is incomplete; existing directories are not overwritten.' >&2; exit 2; }
RK_PYTHON="$destination/python/bin/python3.13" "$root/scripts/python.sh" -I --version
