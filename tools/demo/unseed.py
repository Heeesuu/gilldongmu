"""seed.py가 심은 한마디를 지운다(남긴 기기 ID로 DELETE /v1/messages/{id}).

사용: python3 -I tools/demo/unseed.py
다 지우면 tools/demo/seeded.local.json을 seeded.local.done.json으로 바꿔 둔다.
"""
import json
import os
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(HERE, "seeded.local.json")


def main():
    if not os.path.exists(STATE):
        sys.exit(f"심은 기록이 없습니다: {STATE}")
    seeded = json.load(open(STATE, encoding="utf-8"))
    base = seeded["server"]
    failed = 0
    for m in seeded["messages"]:
        req = urllib.request.Request(f"{base}/messages/{m['id']}", method="DELETE")
        req.add_header("X-Device-Id", m["device"])
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                print(f"{m['name']}: 지움({r.status})")
        except urllib.error.HTTPError as e:
            print(f"{m['name']}: 실패({e.code}) {e.read().decode('utf-8', 'replace')}")
            failed += 1
    if failed:
        sys.exit(f"{failed}개를 지우지 못했습니다")
    os.replace(STATE, os.path.join(HERE, "seeded.local.done.json"))


if __name__ == "__main__":
    main()
