#!/usr/bin/env python3
"""
Выгрузить эталонные планы движка в spec/golden/<сценарий>.json.

Запускать из корня репозитория после любого изменения движка (src/sync) или сценариев
(src/sync/golden.py), затем просмотреть diff: tests/test_golden_plans.py сверяет файлы с движком.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from src.sync.golden import SCENARIOS, build, dumps  # noqa: E402

GOLDEN = ROOT / "spec" / "golden"


def main() -> None:
    GOLDEN.mkdir(parents=True, exist_ok=True)
    names = {s.name for s in SCENARIOS}
    for stale in GOLDEN.glob("*.json"):
        if stale.stem not in names:
            stale.unlink()
            print(f"🗑️  удалён {stale.name}: сценария больше нет")
    changed = 0
    for scenario in SCENARIOS:
        path = GOLDEN / f"{scenario.name}.json"
        text = dumps(build(scenario))
        if not path.exists() or path.read_text(encoding="utf-8") != text:
            path.write_text(text, encoding="utf-8")
            changed += 1
            print(f"✏️  {path.relative_to(ROOT)}")
    print(f"✅ Сценариев: {len(SCENARIOS)}, изменено файлов: {changed}")


if __name__ == "__main__":
    main()
