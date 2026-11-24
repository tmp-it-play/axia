"""UN M49 숫자 코드와 Axia 국가 ID의 대응표."""

import re

from build_economy import download

UN_M49 = "https://unstats.un.org/unsd/methodology/m49/overview/"


def game_codes_by_m49(cache_dir):
    html = download(UN_M49, cache_dir / "un-m49.html" if cache_dir else None).decode("utf-8")
    # UN 표에서 연속 세 열은 M49 숫자 코드, ISO alpha-2, ISO alpha-3이다.
    codes = dict(re.findall(r"<td>\s*([0-9]{3})\s*</td>\s*<td>\s*[A-Z]{2}\s*</td>\s*<td>\s*([A-Z]{3})\s*</td>", html))
    if codes.get("156") != "CHN" or len(codes) < 240:
        raise ValueError("UN M49 국가 코드 표가 불완전합니다.")
    codes.update({"158": "TWN", "275": "PSX", "728": "SDS"})
    return codes
