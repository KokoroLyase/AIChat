"""
生成表情面板用的 emoji 列表：app/src/main/assets/emoji.txt

用法（需要 Python 3）：
    下载 https://www.unicode.org/Public/emoji/latest/emoji-test.txt
    python tools/generate_emoji.py emoji-test.txt

只保留 fully-qualified 的 emoji，去掉肤色变体和单独的组件（发色、肤色色块）。
输出每行一个分组：「分组键<TAB>emoji emoji …」，顺序和系统输入法一致。
App 运行时再用 Paint.hasGlyph 过滤掉系统字体画不出来的。
"""

import pathlib
import sys

GROUPS = {
    "Smileys & Emotion": "smileys",
    "People & Body": "people",
    "Animals & Nature": "animals",
    "Food & Drink": "food",
    "Travel & Places": "travel",
    "Activities": "activities",
    "Objects": "objects",
    "Symbols": "symbols",
    "Flags": "flags",
}

SKIN_TONES = set(range(0x1F3FB, 0x1F400))


def main(source: str) -> None:
    groups: dict[str, list[str]] = {key: [] for key in GROUPS.values()}
    version = ""
    current = None
    for line in pathlib.Path(source).read_text(encoding="utf-8").splitlines():
        if line.startswith("# Version:"):
            version = line.split(":", 1)[1].strip()
        if line.startswith("# group:"):
            current = GROUPS.get(line.split(":", 1)[1].strip())
            continue
        if current is None or not line or line.startswith("#"):
            continue
        codes, rest = line.split(";", 1)
        if not rest.strip().startswith("fully-qualified"):
            continue
        points = [int(c, 16) for c in codes.split()]
        if any(p in SKIN_TONES for p in points):
            continue
        groups[current].append("".join(chr(p) for p in points))

    out = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/emoji.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f"# 由 tools/generate_emoji.py 从 Unicode emoji-test.txt（Emoji {version}）生成，请勿手改。",
        "# 数据来源：Unicode, Inc.，Unicode License v3（https://www.unicode.org/license.txt）",
    ]
    lines += [f"{key}\t{' '.join(items)}" for key, items in groups.items()]
    out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    total = sum(len(v) for v in groups.values())
    print(f"Emoji {version}: {total} 个，写入 {out}")


if __name__ == "__main__":
    main(sys.argv[1])
