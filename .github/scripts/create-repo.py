import json
import os
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.animeextension.nsfw' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(
    r"^application-icon-320:'([^']+)'", re.MULTILINE
)
LANGUAGE_REGEX = re.compile(r"aniyomi-([^\.]+)")

*_, ANDROID_BUILD_TOOLS = (Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir()
REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"

REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

# Extensions whose sites are dead/parked. Source is kept in the repo, but they
# are excluded from the published index so users don't see broken sources.
EXCLUDED_PKGS = {
    "eu.kanade.tachiyomi.animeextension.es.animebum",
    "eu.kanade.tachiyomi.animeextension.es.animeyt",
    "eu.kanade.tachiyomi.animeextension.es.ennovelas",
    "eu.kanade.tachiyomi.animeextension.es.estrenosdoramas",
    "eu.kanade.tachiyomi.animeextension.es.fanpelis",
    "eu.kanade.tachiyomi.animeextension.es.hentaitk",
    "eu.kanade.tachiyomi.animeextension.es.jkhentai",
    "eu.kanade.tachiyomi.animeextension.es.locopelis",
    "eu.kanade.tachiyomi.animeextension.es.samatodenvideos",
    "eu.kanade.tachiyomi.animeextension.es.verseriesonline",
    "eu.kanade.tachiyomi.animeextension.es.zeroanime",
    "eu.kanade.tachiyomi.animeextension.es.zonaleros",
}

with open("output.json", encoding="utf-8") as f:
    inspector_data = json.load(f)

index_data = []
index_min_data = []

for apk in REPO_APK_DIR.iterdir():
    badging = subprocess.check_output(
        [
            ANDROID_BUILD_TOOLS / "aapt",
            "dump",
            "--include-meta-data",
            "badging",
            apk,
        ]
    ).decode()

    package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
    package_name = PACKAGE_NAME_REGEX.search(package_info).group(1)

    if package_name in EXCLUDED_PKGS:
        continue

    application_icon = APPLICATION_ICON_320_REGEX.search(badging).group(1)

    with ZipFile(apk) as z, z.open(application_icon) as i, (
        REPO_ICON_DIR / f"{package_name}.png"
    ).open("wb") as f:
        f.write(i.read())

    language = LANGUAGE_REGEX.search(apk.name).group(1)
    sources = inspector_data[package_name]

    if len(sources) == 1:
        source_language = sources[0]["lang"]

        if (
            source_language != language
            and source_language not in {"all", "other"}
            and language not in {"all", "other"}
        ):
            language = source_language

    common_data = {
        "name": APPLICATION_LABEL_REGEX.search(badging).group(1),
        "pkg": package_name,
        "apk": apk.name,
        "lang": language,
        "code": int(VERSION_CODE_REGEX.search(package_info).group(1)),
        "version": VERSION_NAME_REGEX.search(package_info).group(1),
        "nsfw": int(IS_NSFW_REGEX.search(badging).group(1)),
    }
    min_data = {
        **common_data,
        "sources": [],
    }

    for source in sources:
        min_data["sources"].append(
            {
                "name": source["name"],
                "lang": source["lang"],
                "id": source["id"],
                "baseUrl": source["baseUrl"],
            }
        )

    index_min_data.append(min_data)
    index_data.append(
        {
            **common_data,
            "hasReadme": 0,
            "hasChangelog": 0,
            "sources": sources,
        }
    )

index_data.sort(key=lambda x: x["pkg"])
index_min_data.sort(key=lambda x: x["pkg"])

with (REPO_DIR / "index.json").open("w", encoding="utf-8") as f:
    index_data_str = json.dumps(index_data, ensure_ascii=False, indent=2)

    print(index_data_str)
    f.write(index_data_str)

with (REPO_DIR / "index.min.json").open("w", encoding="utf-8") as f:
    json.dump(index_min_data, f, ensure_ascii=False, separators=(",", ":"))

# Aniyomi/Mihon require a repo.json with the signing certificate fingerprint.
# The fingerprint is read from an actual signed APK so it always matches the
# key used by the CI to sign the extensions.
SHA256_REGEX = re.compile(r"SHA-256 digest: ([0-9a-f]+)")

sample_apk = next(REPO_APK_DIR.iterdir())
certs = subprocess.check_output(
    [
        ANDROID_BUILD_TOOLS / "apksigner",
        "verify",
        "--print-certs",
        sample_apk,
    ]
).decode()
signing_key_fingerprint = SHA256_REGEX.search(certs).group(1)

repo_meta = {
    "meta": {
        "name": "Kohi-den extensions",
        "website": "https://keyferv.github.io/kohi-den-extensions",
        "signingKeyFingerprint": signing_key_fingerprint,
    }
}

with (REPO_DIR / "repo.json").open("w", encoding="utf-8") as f:
    json.dump(repo_meta, f, ensure_ascii=False)
