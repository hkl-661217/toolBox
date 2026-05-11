#!/usr/bin/env python3
"""Query MSC TrackingInfo API using curl_cffi (Chrome TLS impersonation).

stdin  JSON : {"trackingNumber": "...", "trackingMode": "1"}
stdout JSON : {"status": "SUCCESS|NO_RESULT|FAILED",
               "data":   {<MSC raw payload> or null},
               "errorReason": "..."}

Always exits 0 with a JSON result on stdout so the Java caller can read a
single line and parse. Any non-fatal error becomes status=FAILED with a
descriptive errorReason. Java is expected to redact tracking numbers
before logging anything from this output.
"""
import json
import sys
import traceback

try:
    from curl_cffi import requests
except ImportError:
    print(json.dumps({
        "status": "FAILED",
        "errorReason": "curl_cffi not installed on this host (pip3 install --break-system-packages curl_cffi)",
        "data": None,
    }), flush=True)
    sys.exit(0)

HOMEPAGE = "https://www.msccargo.cn/zh/track-a-shipment"
API_URL = "https://www.msccargo.cn/api/feature/tools/TrackingInfo"
IMPERSONATE = "chrome120"
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
)
SEC_CH_UA = '"Not)A;Brand";v="8", "Chromium";v="138", "Google Chrome";v="138"'

BROWSER_HEADERS = {
    "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
    "accept-language": "zh-CN,zh;q=0.9",
    "cache-control": "no-cache",
    "sec-ch-ua": SEC_CH_UA,
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"Linux"',
    "sec-fetch-dest": "document",
    "sec-fetch-mode": "navigate",
    "sec-fetch-site": "none",
    "sec-fetch-user": "?1",
    "upgrade-insecure-requests": "1",
    "user-agent": USER_AGENT,
}

API_HEADERS = {
    "accept": "application/json, text/plain, */*",
    "accept-language": "zh-CN,zh;q=0.9",
    "cache-control": "no-cache",
    "content-type": "application/json",
    "origin": "https://www.msccargo.cn",
    "pragma": "no-cache",
    "referer": HOMEPAGE,
    "sec-ch-ua": SEC_CH_UA,
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"Linux"',
    "sec-fetch-dest": "empty",
    "sec-fetch-mode": "cors",
    "sec-fetch-site": "same-origin",
    "user-agent": USER_AGENT,
    "x-requested-with": "XMLHttpRequest",
}

NO_RESULT_HINTS = ("暂时不可用", "请稍后再试", "no result", "no results", "not found", "未找到")


def query(tracking_number: str, tracking_mode: str = "1") -> dict:
    session = requests.Session(impersonate=IMPERSONATE)

    # Step 1: warm session, harvest ak_bmsc cookie.
    homepage = session.get(HOMEPAGE, headers=BROWSER_HEADERS, timeout=30)
    if homepage.status_code != 200:
        return {"status": "FAILED",
                "errorReason": f"homepage HTTP {homepage.status_code}",
                "data": None}
    if not session.cookies.get("ak_bmsc"):
        return {"status": "FAILED",
                "errorReason": "ak_bmsc cookie not issued by homepage",
                "data": None}

    # Step 2: POST TrackingInfo.
    api = session.post(
        API_URL,
        json={"trackingNumber": tracking_number, "trackingMode": tracking_mode},
        headers=API_HEADERS,
        timeout=30,
    )
    if api.status_code != 200:
        return {"status": "FAILED",
                "errorReason": f"API HTTP {api.status_code}; body head: {api.text[:300]}",
                "data": None}
    try:
        payload = api.json()
    except Exception as parse_err:
        return {"status": "FAILED",
                "errorReason": f"non-JSON response: {parse_err}; body head: {api.text[:300]}",
                "data": None}

    is_success = bool(payload.get("IsSuccess"))
    data = payload.get("Data")
    if is_success:
        return {"status": "SUCCESS", "data": data, "errorReason": ""}

    msg = data if isinstance(data, str) else json.dumps(data, ensure_ascii=False)
    no_result = any(hint in (msg or "") for hint in NO_RESULT_HINTS)
    return {
        "status": "NO_RESULT" if no_result else "FAILED",
        "errorReason": msg or "MSC returned IsSuccess=false without a message",
        "data": None,
    }


def main():
    try:
        raw = sys.stdin.read()
        request = json.loads(raw) if raw.strip() else {}
        tracking_number = (request.get("trackingNumber") or "").strip()
        tracking_mode = request.get("trackingMode") or "1"
        if not tracking_number:
            result = {"status": "FAILED", "errorReason": "trackingNumber is required", "data": None}
        else:
            result = query(tracking_number, str(tracking_mode))
    except Exception:
        result = {
            "status": "FAILED",
            "errorReason": f"unexpected: {traceback.format_exc()[:600]}",
            "data": None,
        }
    print(json.dumps(result, ensure_ascii=False), flush=True)
    sys.exit(0)


if __name__ == "__main__":
    main()
