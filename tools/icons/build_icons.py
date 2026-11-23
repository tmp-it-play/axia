"""rsvg-convert와 Pillow로 README 로고에서 데스크톱 앱 아이콘을 생성한다."""

import io
import subprocess
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "assets"
DRAWABLE = ROOT / "desktopApp/src/main/composeResources/drawable"


def main() -> None:
    png = subprocess.check_output(["rsvg-convert", "--width", "1024", ASSETS / "logo.svg"])
    with Image.open(io.BytesIO(png)) as logo:
        logo.save(ASSETS / "logo.icns")
        logo.convert("RGBA").save(ASSETS / "logo.ico")
        DRAWABLE.mkdir(parents=True, exist_ok=True)
        logo.resize((256, 256), Image.Resampling.LANCZOS).save(DRAWABLE / "logo.png")


if __name__ == "__main__":
    main()
