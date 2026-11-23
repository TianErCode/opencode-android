#!/usr/bin/env python3
from __future__ import annotations

import argparse
from contextlib import contextmanager
from hashlib import sha256
from io import BytesIO
import json
import os
import posixpath
import shutil
import socket
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from runtime_tools.termux_assets import (
    ANDROID_LINUX_ASSET_ROOT,
    ANDROID_TO_TERMUX_ARCH,
    asset_manifest_path,
    asset_prefix_dir,
    load_data_tar_bytes_from_deb,
    normalize_text_shebang,
    open_data_tar,
    parse_packages_index,
    resolve_dependency_closure,
    ROOT_PACKAGES,
    serializable_manifest,
    strip_termux_prefix,
    TERMUX_MAIN_BASE_URL,
    verify_sha256,
    write_manifest,
    TermuxPackageRecord,
)

DEFAULT_LOCK_FILE = REPO_ROOT / "runtime_tools" / "termux_assets.lock.json"
LOCK_FILE_VERSION = 1


TERMUX_MAIN_FALLBACK_BASE_URLS = (
    TERMUX_MAIN_BASE_URL,
    "https://packages-cf.termux.dev/apt/termux-main",
    "https://mirror.iscas.ac.cn/termux/apt/termux-main",
    "https://grimler.se/termux/termux-main",
    "https://termux.librehat.com/apt/termux-main",
    "https://mirror.rinarin.dev/termux/termux-main",
    "https://ftp.fau.de/termux/termux-main",
)


def force_ipv4_downloads() -> bool:
    configured = os.environ.get("OPENCODE_ANDROID_FORCE_IPV4")
    if configured is None:
        
        
        
        
        return os.name == "nt"
    return configured.strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }


@contextmanager
def ipv4_only_dns(enabled: bool):
    """Scope urllib DNS resolution to IPv4 for hosts with broken IPv6 routes."""
    if not enabled:
        yield
        return

    original_getaddrinfo = socket.getaddrinfo

    def getaddrinfo_ipv4(host, port, family=0, type=0, proto=0, flags=0):
        return original_getaddrinfo(host, port, socket.AF_INET, type, proto, flags)

    socket.getaddrinfo = getaddrinfo_ipv4
    try:
        yield
    finally:
        socket.getaddrinfo = original_getaddrinfo


def download_bytes(url: str, attempts: int = 3) -> bytes:
    import time

    last_error: Exception | None = None
    
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        ),
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
    }
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers=headers)
            with ipv4_only_dns(force_ipv4_downloads()):
                with urllib.request.urlopen(request, timeout=120) as response:
                    payload = response.read()
                    if not payload:
                        last_error = RuntimeError("empty response body")
                        time.sleep(0.5 * (attempt + 1))
                        continue
                    return payload
        except Exception as exc:  
            last_error = exc
            time.sleep(0.5 * (attempt + 1))
    raise RuntimeError(f"Failed to download {url}: {last_error}")


def configured_termux_main_base_urls() -> list[str]:
    configured = []
    for key in ("OPENCODE_ANDROID_TERMUX_MAIN_BASE_URLS", "OPENCODE_ANDROID_TERMUX_MAIN_BASE_URL"):
        raw = os.environ.get(key, "")
        configured.extend(item.strip() for item in raw.replace(";", ",").split(",") if item.strip())
    configured.extend(TERMUX_MAIN_FALLBACK_BASE_URLS)
    return list(dict.fromkeys(url.rstrip("/") for url in configured if url.strip()))


def _termux_main_url(base_url: str, relative_path: str) -> str:
    return f"{base_url.rstrip('/')}/{relative_path.lstrip('/')}"


def _packages_index_path(termux_arch: str) -> str:
    return f"dists/stable/main/binary-{termux_arch}/Packages"


def _validate_termux_payload(relative_path: str, payload: bytes) -> None:
    if "/Packages" not in relative_path:
        return
    preview = payload[:16_384]
    if not preview.lstrip().startswith(b"Package:") or b"\nSHA256:" not in preview:
        raise ValueError("response is not a Debian Packages index")
















CACHE_ENV = "OPENCODE_ANDROID_TERMUX_CACHE_DIR"
DEFAULT_CACHE_DIRNAME = "termux-apt-cache"


def cache_dir() -> Path | None:
    """The cache directory, or None when caching is off or unusable."""
    raw = os.environ.get(CACHE_ENV, "").strip()
    if raw.lower() in ("0", "off", "false", "none"):
        return None
    base = Path(raw).expanduser() if raw else Path.home() / ".cache" / DEFAULT_CACHE_DIRNAME
    try:
        base.mkdir(parents=True, exist_ok=True)
    except OSError:
        return None
    return base


def _cache_key(relative_path: str, expected_sha256: str | None) -> str:
    
    digest = (expected_sha256 or "unverified").lower()
    return f"{digest[:16]}-{relative_path.strip('/').replace('/', '_')}"


def _read_cache(directory: Path, relative_path: str, expected_sha256: str | None) -> bytes | None:
    if not expected_sha256:
        return None  
    path = directory / _cache_key(relative_path, expected_sha256)
    try:
        payload = path.read_bytes()
    except OSError:
        return None
    try:
        verify_sha256(payload, expected_sha256)
    except ValueError:
        
        path.unlink(missing_ok=True)
        return None
    return payload


def _write_cache(directory: Path, relative_path: str, expected_sha256: str | None, payload: bytes) -> None:
    if not expected_sha256:
        return
    path = directory / _cache_key(relative_path, expected_sha256)
    
    partial = path.with_name(path.name + ".partial")
    try:
        partial.write_bytes(payload)
        partial.replace(path)
    except OSError:
        partial.unlink(missing_ok=True)


def download_termux_main_path(relative_path: str, expected_sha256: str | None = None) -> bytes:
    directory = cache_dir()
    if directory is not None:
        cached = _read_cache(directory, relative_path, expected_sha256)
        if cached is not None:
            return cached

    errors: list[str] = []
    for base_url in configured_termux_main_base_urls():
        url = _termux_main_url(base_url, relative_path)
        try:
            payload = download_bytes(url)
            _validate_termux_payload(relative_path, payload)
            if expected_sha256:
                try:
                    verify_sha256(payload, expected_sha256)
                except ValueError as exc:
                    errors.append(f"{url}: {exc}")
                    continue
            if directory is not None:
                _write_cache(directory, relative_path, expected_sha256, payload)
            return payload
        except Exception as exc:  
            errors.append(f"{url}: {exc}")
    raise RuntimeError(f"Failed to download Termux path {relative_path}: {'; '.join(errors)}")


@contextmanager
def locked_package_archive(lock_payload: dict | None):
    """Open the immutable package bundle pinned by the lock, when configured.

    Three things this had to gain:

    * a local-path source, so the bundle can be shipped next to the repository instead of depending on
      another host being up. `package_archive.path` is resolved relative to the lock file.
    * the same download cache as the mirrors. The archive is itself a single pinned blob, so caching it
      is exactly as safe - and without that, the "recoverable when mirrors are down" fallback was only
      true while its own host happened to be reachable.
    * the archive is a *fallback*, never a source of truth: every package inside still carries its own
      SHA-256 in the lock, and `download_locked_package` verifies each entry against it.
    """
    archive_config = (lock_payload or {}).get("package_archive") or {}
    url = str(archive_config.get("url", "")).strip()
    expected_sha256 = str(archive_config.get("sha256", "")).strip()
    local_path = str(archive_config.get("path", "")).strip()

    if not expected_sha256:
        yield None
        return

    payload: bytes | None = None
    directory = cache_dir()

    if directory is not None:
        cached = _read_cache(directory, f"package-archive/{_cache_key('bundle', expected_sha256)}", expected_sha256)
        if cached is not None:
            payload = cached

    if payload is None and local_path:
        
        
        
        base = DEFAULT_LOCK_FILE.parent if DEFAULT_LOCK_FILE and DEFAULT_LOCK_FILE.parent else Path.cwd()
        candidate = Path(local_path).expanduser()
        if not candidate.is_absolute():
            candidate = (base / candidate).resolve()
        else:
            candidate = candidate.resolve()
        try:
            candidate_bytes = candidate.read_bytes()
            verify_sha256(candidate_bytes, expected_sha256)
            payload = candidate_bytes
            if directory is not None:
                _write_cache(directory, f"package-archive/{_cache_key('bundle', expected_sha256)}", expected_sha256, candidate_bytes)
            sys.stderr.write(f"Using local locked Termux package archive {candidate}\n")
        except Exception as exc:
            sys.stderr.write(f"Local locked Termux package archive unusable ({candidate}): {exc}\n")

    if payload is None and url:
        try:
            payload = download_bytes(url)
            verify_sha256(payload, expected_sha256)
            if directory is not None:
                _write_cache(directory, f"package-archive/{_cache_key('bundle', expected_sha256)}", expected_sha256, payload)
        except Exception as exc:  
            sys.stderr.write(f"Locked Termux package archive unavailable ({url}): {exc}\n")
            payload = None

    if payload is None:
        
        
        yield None
        return

    try:
        payload_stream = BytesIO(payload)
        archive = zipfile.ZipFile(payload_stream, "r")
        names = archive.namelist()
        duplicate_names = {name for name in names if names.count(name) > 1}
        if duplicate_names:
            archive.close()
            raise ValueError(
                "Duplicate entries in locked Termux package archive: "
                + ", ".join(sorted(duplicate_names))
            )
    except Exception as exc:  
        sys.stderr.write(f"Locked Termux package archive is not a usable archive: {exc}\n")
        yield None
        return

    with archive:
        yield archive


def download_locked_package(
    package: TermuxPackageRecord,
    archive: zipfile.ZipFile | None,
) -> bytes:
    if archive is not None:
        try:
            payload = archive.read(package.filename)
            verify_sha256(payload, package.sha256)
            return payload
        except (KeyError, ValueError) as exc:
            sys.stderr.write(
                f"Locked Termux package archive entry unavailable ({package.filename}): {exc}\n"
            )
    return download_termux_main_path(package.filename, expected_sha256=package.sha256)


def unique_locked_packages(lock_payload: dict) -> list[TermuxPackageRecord]:
    by_filename: dict[str, TermuxPackageRecord] = {}
    for android_abi, termux_arch in ANDROID_TO_TERMUX_ARCH.items():
        for package in locked_packages(lock_payload, android_abi, termux_arch):
            previous = by_filename.get(package.filename)
            if previous is not None and previous.sha256 != package.sha256:
                raise ValueError(
                    f"Conflicting SHA256 values for locked package {package.filename}: "
                    f"{previous.sha256} and {package.sha256}"
                )
            by_filename[package.filename] = package
    return [by_filename[name] for name in sorted(by_filename)]


def build_package_archive(lock_payload: dict, output_path: Path) -> str:
    """Create a deterministic ZIP of all lock-pinned .deb files."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    partial_path = output_path.with_name(f"{output_path.name}.partial")
    partial_path.unlink(missing_ok=True)
    try:
        with zipfile.ZipFile(partial_path, "w", compression=zipfile.ZIP_STORED) as archive:
            for package in unique_locked_packages(lock_payload):
                payload = download_termux_main_path(
                    package.filename,
                    expected_sha256=package.sha256,
                )
                info = zipfile.ZipInfo(package.filename, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_STORED
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                archive.writestr(info, payload)
        partial_path.replace(output_path)
    finally:
        partial_path.unlink(missing_ok=True)
    return sha256(output_path.read_bytes()).hexdigest()


def read_packages_index(termux_arch: str) -> dict[str, TermuxPackageRecord]:
    return parse_packages_index(download_termux_main_path(_packages_index_path(termux_arch)).decode("utf-8", "ignore"))


def _package_record_to_json(record: TermuxPackageRecord) -> dict:
    return {
        "name": record.name,
        "version": record.version,
        "filename": record.filename,
        "sha256": record.sha256,
        "depends": list(record.depends),
    }


def _package_record_from_json(payload: dict) -> TermuxPackageRecord:
    return TermuxPackageRecord(
        name=str(payload["name"]),
        version=str(payload["version"]),
        filename=str(payload["filename"]),
        sha256=str(payload["sha256"]),
        depends=tuple(str(item) for item in payload.get("depends", [])),
    )


def build_lock_payload() -> dict:
    architectures = {}
    for android_abi, termux_arch in ANDROID_TO_TERMUX_ARCH.items():
        packages = resolve_dependency_closure(read_packages_index(termux_arch))
        architectures[android_abi] = {
            "termux_arch": termux_arch,
            "packages": [_package_record_to_json(package) for package in packages],
        }
    return {
        "version": LOCK_FILE_VERSION,
        "termux_main_base_url": TERMUX_MAIN_BASE_URL,
        "root_packages": list(ROOT_PACKAGES),
        "architectures": architectures,
    }


def write_lock_file(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(payload, indent=2, sort_keys=True) + "\n").encode("utf-8"))


def load_lock_file(path: Path) -> dict | None:
    if not path.is_file():
        return None
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("version") != LOCK_FILE_VERSION:
        raise ValueError(f"Unsupported Termux asset lock file version in {path}")
    return payload


def locked_packages(lock_payload: dict, android_abi: str, termux_arch: str) -> list[TermuxPackageRecord]:
    arch_payload = lock_payload.get("architectures", {}).get(android_abi)
    if not arch_payload:
        raise KeyError(f"Termux asset lock file does not contain Android ABI {android_abi}")
    if arch_payload.get("termux_arch") != termux_arch:
        raise ValueError(f"Termux asset lock file maps {android_abi} to {arch_payload.get('termux_arch')}, not {termux_arch}")
    return [_package_record_from_json(item) for item in arch_payload.get("packages", [])]


def _termux_root(extracted_root: Path) -> Path:
    return extracted_root / "data" / "data" / "com.termux" / "files" / "usr"


def _normalize_link_target(source: Path, target: str, termux_root: Path) -> str | None:
    target_path = Path(target)
    if target_path.is_absolute():
        if str(target_path).startswith(str(termux_root)):
            return str(target_path.relative_to(termux_root))
        return None
    resolved = (source.parent / target_path).resolve(strict=False)
    try:
        return resolved.relative_to(termux_root).as_posix()
    except ValueError:
        return None


def mirror_extracted_tree(extracted_root: Path, staging_prefix: Path) -> list[dict]:
    termux_root = _termux_root(extracted_root)
    if not termux_root.exists():
        return []

    inode_first_paths: dict[tuple[int, int], str] = {}
    links: list[dict] = []

    for source in sorted(termux_root.rglob("*"), key=lambda item: item.relative_to(termux_root).as_posix()):
        relative = source.relative_to(termux_root)
        destination = staging_prefix / relative
        if source.is_dir():
            destination.mkdir(parents=True, exist_ok=True)
            continue

        destination.parent.mkdir(parents=True, exist_ok=True)

        if source.is_symlink():
            normalized_target = _normalize_link_target(source, os.readlink(source), termux_root)
            if normalized_target:
                links.append({"path": relative.as_posix(), "target": normalized_target})
            continue

        stat = source.stat()
        inode_key = (stat.st_dev, stat.st_ino)
        first_path = inode_first_paths.get(inode_key)
        if first_path is not None:
            links.append({"path": relative.as_posix(), "target": first_path})
            continue
        inode_first_paths[inode_key] = relative.as_posix()

        payload = normalize_text_shebang(source.read_bytes())
        destination.write_bytes(payload)
        if relative.as_posix().startswith(("bin/", "libexec/")) or os.access(source, os.X_OK):
            destination.chmod(0o755)

    return links


def _archive_termux_relative(path: str) -> str | None:
    normalized = posixpath.normpath(str(path).replace("\\", "/"))
    relative = strip_termux_prefix(normalized)
    if relative is None:
        return None
    relative = posixpath.normpath(relative)
    if relative == ".":
        return ""
    if relative.startswith("../"):
        return None
    return relative


def _staging_destination(staging_prefix: Path, relative: str) -> Path:
    parts = [part for part in relative.split("/") if part and part != "."]
    if any(part == ".." for part in parts):
        raise ValueError(f"Unsafe archive member path: {relative!r}")
    destination = staging_prefix.joinpath(*parts)
    destination.resolve(strict=False).relative_to(staging_prefix.resolve(strict=False))
    return destination


def _normalize_archive_link_target(source_relative: str, target: str) -> str | None:
    direct = _archive_termux_relative(target)
    if direct:
        return direct
    if direct == "":
        return None

    normalized = str(target).replace("\\", "/")
    if normalized.startswith("/"):
        return None
    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(source_relative), normalized))
    if resolved == "." or resolved.startswith("../"):
        return None
    return resolved


def _normalize_archive_hardlink_target(source_relative: str, target: str) -> str | None:
    direct = _archive_termux_relative(target)
    if direct:
        return direct
    if direct == "":
        return None

    normalized = posixpath.normpath(str(target).replace("\\", "/").lstrip("./"))
    if normalized and normalized != "." and not normalized.startswith("../"):
        return normalized
    return _normalize_archive_link_target(source_relative, target)


def mirror_data_tar(data_tar: tarfile.TarFile, staging_prefix: Path) -> list[dict]:
    links: list[dict] = []
    staging_prefix.mkdir(parents=True, exist_ok=True)

    for member in sorted(data_tar.getmembers(), key=lambda item: item.name):
        relative = _archive_termux_relative(member.name)
        if relative is None:
            continue
        if relative == "":
            staging_prefix.mkdir(parents=True, exist_ok=True)
            continue

        destination = _staging_destination(staging_prefix, relative)

        if member.isdir():
            destination.mkdir(parents=True, exist_ok=True)
            continue

        if member.issym():
            target = _normalize_archive_link_target(relative, member.linkname)
            if target:
                links.append({"path": relative, "target": target})
            continue

        if member.islnk():
            target = _normalize_archive_hardlink_target(relative, member.linkname)
            if target:
                links.append({"path": relative, "target": target})
            continue

        if not member.isfile():
            continue

        file_obj = data_tar.extractfile(member)
        if file_obj is None:
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        payload = normalize_text_shebang(file_obj.read())
        destination.write_bytes(payload)
        if relative.startswith(("bin/", "libexec/")) or member.mode & 0o111:
            destination.chmod(0o755)

    return links


def prune_staging_prefix(prefix_dir: Path) -> None:
    removable = [
        prefix_dir / "include",
        prefix_dir / "lib" / "pkgconfig",
        prefix_dir / "share" / "doc",
        prefix_dir / "share" / "info",
        prefix_dir / "share" / "man",
        prefix_dir / "share" / "zsh",
        prefix_dir / "share" / "LICENSES",
        prefix_dir / "var" / "cache",
    ]
    for path in removable:
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)


def manifest_file_list(prefix_dir: Path) -> list[str]:
    return [
        path.relative_to(prefix_dir).as_posix()
        for path in sorted(prefix_dir.rglob("*"), key=lambda item: item.relative_to(prefix_dir).as_posix())
        if path.is_file()
    ]


def prepare_assets(output_dir: Path, lock_file: Path | None = DEFAULT_LOCK_FILE, refresh_lock_file: bool = False) -> None:
    lock_payload = None
    if lock_file is not None:
        if refresh_lock_file:
            lock_payload = build_lock_payload()
            write_lock_file(lock_file, lock_payload)
        else:
            lock_payload = load_lock_file(lock_file)

    with locked_package_archive(lock_payload) as package_archive:
        for android_abi, termux_arch in ANDROID_TO_TERMUX_ARCH.items():
            if lock_payload:
                packages = locked_packages(lock_payload, android_abi, termux_arch)
            else:
                records = read_packages_index(termux_arch)
                packages = resolve_dependency_closure(records)

            prefix_dir = asset_prefix_dir(output_dir, android_abi)
            if prefix_dir.exists():
                shutil.rmtree(prefix_dir)
            prefix_dir.mkdir(parents=True, exist_ok=True)

            links: list[dict] = []
            for package in packages:
                payload = download_locked_package(package, package_archive)
                data_bytes, data_name = load_data_tar_bytes_from_deb(payload)
                with open_data_tar(data_bytes, data_name) as tar:
                    links.extend(mirror_data_tar(tar, prefix_dir))

            prune_staging_prefix(prefix_dir)
            for extra_dir in [prefix_dir / "home", prefix_dir / "tmp"]:
                extra_dir.mkdir(parents=True, exist_ok=True)

            write_manifest(
                asset_manifest_path(output_dir, android_abi),
                serializable_manifest(
                    android_abi,
                    packages,
                    links=links,
                    files=manifest_file_list(prefix_dir),
                ),
            )


def check_termux_mirror_health(termux_arch: str = "x86_64") -> dict:
    import importlib.util

    harness_path = REPO_ROOT / "scripts" / "android_visual_harness.py"
    spec = importlib.util.spec_from_file_location("android_visual_harness", harness_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load {harness_path}")
    harness = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(harness)
    mirrors = harness.check_termux_package_mirrors(termux_arch=termux_arch)
    healthy = [item for item in mirrors if item["status"] == "ok"]
    return {
        "termux_arch": termux_arch,
        "healthy_count": len(healthy),
        "recommended_base_urls": [item["base_url"] for item in healthy],
        "mirrors": mirrors,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare Android-native command assets for OpenCode builds")
    parser.add_argument("--output-dir", required=True, help="Directory where generated assets should be written")
    parser.add_argument(
        "--lock-file",
        default=str(DEFAULT_LOCK_FILE),
        help="Pinned Termux package lock file used for reproducible release builds",
    )
    parser.add_argument("--refresh-lock-file", action="store_true", help="Refresh the pinned Termux package lock file")
    parser.add_argument("--lock-only", action="store_true", help="Only refresh the lock file, without extracting assets")
    parser.add_argument(
        "--build-package-archive",
        help="Create a deterministic ZIP containing every package pinned by the lock file",
    )
    parser.add_argument(
        "--check-mirrors",
        action="store_true",
        help="Probe configured Termux main mirrors and print a JSON health report",
    )
    parser.add_argument("--mirror-report", help="Optional path to write the mirror health JSON report")
    parser.add_argument("--termux-arch", default="x86_64", help="Termux arch used for mirror health probes")
    args = parser.parse_args()
    if args.check_mirrors:
        report = check_termux_mirror_health(termux_arch=args.termux_arch)
        encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.mirror_report:
            Path(args.mirror_report).expanduser().resolve().write_text(encoded, encoding="utf-8")
        else:
            sys.stdout.write(encoded)
        if report["healthy_count"] == 0:
            raise SystemExit(1)
        return
    output_dir = Path(args.output_dir).expanduser().resolve()
    lock_file = Path(args.lock_file).expanduser().resolve() if args.lock_file else None
    if args.build_package_archive:
        if lock_file is None:
            raise ValueError("--build-package-archive requires --lock-file")
        lock_payload = load_lock_file(lock_file)
        if lock_payload is None:
            raise FileNotFoundError(lock_file)
        archive_path = Path(args.build_package_archive).expanduser().resolve()
        archive_sha256 = build_package_archive(lock_payload, archive_path)
        sys.stdout.write(
            json.dumps(
                {
                    "path": str(archive_path),
                    "sha256": archive_sha256,
                    "bytes": archive_path.stat().st_size,
                },
                sort_keys=True,
            )
            + "\n"
        )
        return
    if args.lock_only:
        if lock_file is None:
            raise ValueError("--lock-only requires --lock-file")
        write_lock_file(lock_file, build_lock_payload())
        return
    asset_root = output_dir / ANDROID_LINUX_ASSET_ROOT
    if asset_root.exists():
        shutil.rmtree(asset_root)
    asset_root.mkdir(parents=True, exist_ok=True)
    prepare_assets(output_dir, lock_file=lock_file, refresh_lock_file=args.refresh_lock_file)


if __name__ == "__main__":
    main()
