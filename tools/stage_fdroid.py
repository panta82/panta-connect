#!/usr/bin/env python3
"""Stage a signed APK, matching source and signed F-Droid indexes without publishing."""
import argparse
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
from urllib.parse import urlparse

import yaml


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--repo-url", required=True, help="HTTPS publication URL, or localhost HTTP for testing")
    parser.add_argument("--state", type=Path, default=Path.home() / ".local/share/panta-connect/fdroid")
    args = parser.parse_args()
    url = urlparse(args.repo_url)
    if url.scheme != "https" and not (url.scheme == "http" and url.hostname in ("localhost", "127.0.0.1")):
        parser.error("Use HTTPS, except for local device tests")
    root = Path(__file__).resolve().parent.parent
    apk = args.apk.resolve()
    sdk = Path(os.environ["ANDROID_HOME"])
    tools = sdk / "build-tools/37.0.0"
    subprocess.run([str(tools / "apksigner"), "verify", str(apk)], check=True, capture_output=True)
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match or match[1] != "net.pantas.connect":
        parser.error("Only a signed release of net.pantas.connect can be staged")
    package, code, version = match.groups()
    state = args.state.expanduser().resolve()
    state.mkdir(mode=0o700, parents=True, exist_ok=True)
    private = Path.home() / ".config/panta-connect"
    private.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not (state / "config.yml").exists():
        subprocess.run([
            "fdroid", "init", "--no-prompt", "--android-home", str(sdk),
            "--keystore", str(private / "fdroid-repo.p12"),
            "--repo-keyalias", "panta-connect-repo", "-d", "CN=Panta Connect Repository",
        ], cwd=state, check=True)
    config_file = state / "config.yml"
    config = yaml.safe_load(config_file.read_text()) or {}
    icon = root / "src/main/res/mipmap-xxxhdpi/ic_launcher.png"
    shutil.copy2(icon, state / "repo-icon.png")
    config.update(repo_url=args.repo_url.rstrip('/'), repo_name="Panta Connect",
                  repo_description="Independent KDE Connect fork with image clipboard reception",
                  repo_icon="repo-icon.png", sdk_path=str(sdk), archive_older=0)
    config_file.chmod(0o600)
    config_file.write_text("# PANTA: Private local repository signing state. Publish only repo/.\n" + yaml.safe_dump(config, sort_keys=False))
    keystore = Path(config["keystore"])
    if keystore.exists():
        keystore.chmod(0o600)
    repo = state / "repo"
    repo.mkdir(exist_ok=True)
    destination = repo / f"{package}_{code}.apk"
    if destination.exists() and destination.read_bytes() != apk.read_bytes():
        parser.error("This version code is already staged with different bytes; increment the version")
    shutil.copy2(apk, destination)
    metadata = state / "metadata"
    metadata.mkdir(exist_ok=True)
    app = yaml.safe_load((root / "fdroid/metadata/net.pantas.connect.yml").read_text())
    app.update(CurrentVersion=version, CurrentVersionCode=int(code))
    (metadata / f"{package}.yml").write_text(yaml.safe_dump(app, sort_keys=False))
    localized = metadata / package / "en-US"
    localized.mkdir(parents=True, exist_ok=True)
    shutil.copy2(icon, localized / "icon.png")
    source = repo / f"{package}_{code}_src.tar.gz"
    files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root).split(b'\0')
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    with tarfile.open(source, "w:gz") as archive:
        for name in sorted(set(os.fsdecode(path) for path in files if path)):
            path = root / name
            if path.name in ("AGENTS.md", "CLAUDE.md", "IMAGE_CLIPBOARD_PLAN.md", "local.properties"):
                continue
            if path.suffix.lower() in (".p12", ".jks", ".keystore", ".key", ".pem"):
                continue
            if path.name.startswith(".env") or path.name.lower().endswith("signing.properties"):
                continue
            if path.is_file():
                archive.add(path, arcname=f"panta-connect/{name}")
        info = json.dumps({"baseCommit": revision, "versionCode": int(code), "versionName": version,
                           "command": f"./gradlew -PforkVersionCode={code} -PforkVersionName={version} assembleRelease",
                           "signing": "Configure your own signing key or build an unsigned release"}, indent=2).encode()
        member = tarfile.TarInfo("panta-connect/release-build.json")
        member.size = len(info)
        archive.addfile(member, io.BytesIO(info))
    update = ["fdroid", "update", "--pretty"]
    if not keystore.exists():
        update.append("--create-key")
    subprocess.run(update, cwd=state, check=True)
    keystore.chmod(0o600)
    public = root / "build/fdroid-public/repo"
    if public.exists():
        shutil.rmtree(public)
    # fdroid's status reports contain local tool paths, not client repository data.
    shutil.copytree(repo, public, ignore=shutil.ignore_patterns("status"))
    shutil.copy2(root / "fdroid/index.html", public.parent / "index.html")
    (public.parent / ".nojekyll").touch()
    print(f"Staged {version} ({code}) at {public}. Nothing was published.")


if __name__ == "__main__":
    main()
