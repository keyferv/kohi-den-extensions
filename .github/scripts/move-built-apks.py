import shutil
from pathlib import Path

REPO_APK_DIR = Path("repo/apk")

shutil.rmtree(REPO_APK_DIR, ignore_errors=True)
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

for apk in Path.home().joinpath("apk-artifacts").rglob("*.apk"):
    dest_name = apk.name.replace("-release", "")
    shutil.move(str(apk), REPO_APK_DIR / dest_name)
