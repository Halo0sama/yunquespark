#!/usr/bin/env python3
"""
生成 app 的种子笔记资产（可选功能）。

如果你已用 Obsidian 插件（minote-sync 等）把小米笔记同步为本地 Markdown，
可以用本脚本把它们打包成 app 首启种子数据，实现"开箱即全库离线可用"：
    python3 tools/gen_seed.py "/path/to/vault/minote" app/src/main/assets/seed/notes.jsonl

不生成种子也完全没问题：在 app 里登录小米账号后，云同步会拉取全部笔记。
隐私提示：种子文件含全部笔记内容，已被 .gitignore 排除，请勿提交到公开仓库。
"""
import json
import hashlib
import re
import sys
from pathlib import Path


def main(vault_dir: str, out_path: str):
    vault = Path(vault_dir)
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    notes, seen = [], set()
    for md in vault.rglob("*.md"):
        rel = md.relative_to(vault)
        folder = rel.parts[0] if len(rel.parts) > 1 else "未分类"
        name = md.stem
        m = re.search(r"_(\d{10,})$", name)
        nid = m.group(1) if m else "local_" + hashlib.md5(str(rel).encode()).hexdigest()[:12]
        title = name[: m.start()] if m else name
        content = md.read_text(encoding="utf-8", errors="replace")
        st = md.stat()
        if nid in seen:
            continue
        seen.add(nid)
        notes.append({
            "id": nid, "title": title, "folder": folder, "content": content,
            "createTs": int(st.st_mtime * 1000), "modifyTs": int(st.st_mtime * 1000),
        })
    notes.sort(key=lambda n: n["createTs"])
    with out.open("w", encoding="utf-8") as f:
        for n in notes:
            f.write(json.dumps(n, ensure_ascii=False) + "\n")
    print(f"已生成 {len(notes)} 篇 → {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2])
