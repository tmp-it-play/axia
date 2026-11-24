"""rsvg-convert와 Pillow로 README 로고에서 데스크톱 앱 아이콘을 생성한다."""

import io
import subprocess
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "assets"
DRAWABLE = ROOT / "desktopApp/src/main/composeResources/drawable"


def main() -> None:
    png = subprocess.check_output(["rsvg-convert", "--width", "1024", ASSETS / "logo.svg"])
    with Image.open(io.BytesIO(png)) as logo:
        # 512 × 512 원본의 지도 테두리 안쪽부터 macOS의 둥근 아이콘 끝까지 채운다.
        mac_icon = ImageOps.fit(logo, logo.size, method=Image.Resampling.LANCZOS, bleed=32 / 512)
        mac_icon.save(ASSETS / "logo.icns")
        logo.convert("RGBA").save(ASSETS / "logo.ico")
        DRAWABLE.mkdir(parents=True, exist_ok=True)
        logo.resize((256, 256), Image.Resampling.LANCZOS).save(DRAWABLE / "logo.png")


if __name__ == "__main__":
    main()
