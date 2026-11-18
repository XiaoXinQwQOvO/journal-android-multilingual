#!/usr/bin/env python3
"""Sync substance data from PsychonautWiki and build a client extension pack.

The script is intentionally conservative: API values only replace local values
when the API actually provides them, and long-form text (summary, risks, safer
use) is never overwritten for substances that already exist locally, because the
API's `summary` field mixes article leftovers and risk text.

Run it from a checkout of this repository so `--assets-dir` points at
app/src/main/assets/substances. Only the standard library is required.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

PW_API_URL = "https://api.psychonautwiki.org/"
DEFAULT_TRANSLATE_API = "http://60.204.204.179:10087/v1"
DEFAULT_MODEL = "deepseek-v4.1-flash-1017"
PACK_REGISTER_NAME = "pw_core_data"
PAGE_SIZE = 200

GRAPHQL_QUERY = """
query($limit: Int!, $offset: Int!) {
  substances(limit: $limit, offset: $offset) {
    name
    url
    featured
    commonNames
    summary
    class { chemical psychoactive }
    tolerance { full half zero }
    addictionPotential
    toxicity
    crossTolerances
    uncertainInteractions { name }
    unsafeInteractions { name }
    dangerousInteractions { name }
    roas {
      name
      dose {
        units
        threshold
        light { min max }
        common { min max }
        strong { min max }
        heavy
      }
      duration {
        onset { min max units }
        comeup { min max units }
        peak { min max units }
        offset { min max units }
        total { min max units }
        afterglow { min max units }
      }
      bioavailability { min max }
    }
  }
}
"""

LANG_LABELS = {"zh_cn": "简体中文", "zh_tw": "繁體中文（台灣）"}
LANG_TITLES = {
    "en_us": ("PsychonautWiki data", "Latest substance data and translations from PsychonautWiki"),
    "zh_cn": ("PsychonautWiki 数据", "来自 PsychonautWiki 的最新物质数据与翻译"),
    "zh_tw": ("PsychonautWiki 資料", "來自 PsychonautWiki 的最新物質資料與翻譯"),
}


def log(message):
    print(message, file=sys.stderr)


def http_json(url, payload, headers=None, timeout=60):
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json", "User-Agent": "journal-pw-sync/1.0", **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", "replace")[:500]
        raise RuntimeError(f"HTTP {error.code} from {url}: {body}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"cannot reach {url}: {error}") from error


def fetch_all_substances(limit=None):
    substances = []
    seen = set()
    offset = 0
    while True:
        payload = {"query": GRAPHQL_QUERY, "variables": {"limit": PAGE_SIZE, "offset": offset}}
        response = http_json(PW_API_URL, payload)
        if response.get("errors"):
            raise RuntimeError(f"PsychonautWiki GraphQL error: {response['errors'][:2]}")
        page = (response.get("data") or {}).get("substances") or []
        for substance in page:
            name = substance.get("name")
            if name and name not in seen:
                seen.add(name)
                substances.append(substance)
        log(f"fetched {len(substances)} substances (offset {offset})")
        if limit is not None and len(substances) >= limit:
            return substances[:limit]
        if len(page) < PAGE_SIZE:
            return substances
        offset += PAGE_SIZE
        time.sleep(0.1)


def sanitize_filename(name):
    return re.sub(r'[\\/*?:"<>|]', "_", name)


def number(value):
    if value is None:
        return None
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return value


def parse_zero_hours(text):
    if not text:
        return None
    numbers = [float(n) for n in re.findall(r"\d+(?:\.\d+)?", text)]
    if not numbers:
        return None
    span = numbers[0] if len(numbers) == 1 else (numbers[0] + numbers[1]) / 2
    lower = text.lower()
    if "month" in lower:
        unit = 30 * 24
    elif "week" in lower:
        unit = 7 * 24
    elif "day" in lower:
        unit = 24
    elif "hour" in lower:
        unit = 1
    else:
        return None
    return int(round(span * unit))


def names_of(items):
    return [item.get("name") for item in (items or []) if item.get("name")]


def duration_range(value):
    if not value:
        return None
    return {
        "min": number(value.get("min")),
        "max": number(value.get("max")),
        "units": value.get("units"),
    }


def build_roa(api_roa, existing_roa=None):
    existing = existing_roa or {}
    roa = {
        "name": api_roa.get("name") or existing.get("name"),
    }
    dose = api_roa.get("dose") or {}
    old_dose = existing.get("dose") or {}
    units = dose.get("units") or old_dose.get("units")
    if dose and units:
        new_dose = dict(old_dose)
        new_dose["units"] = units
        # The app's lightMin historically stores the API "threshold" value, not
        # the lower bound of the light range.
        light = dose.get("light") or {}
        if dose.get("threshold") is not None:
            new_dose["lightMin"] = number(dose["threshold"])
        elif light.get("min") is not None:
            new_dose["lightMin"] = number(light["min"])
        for key in ("common", "strong"):
            range_ = dose.get(key)
            if range_ and range_.get("min") is not None:
                new_dose[f"{key}Min"] = number(range_["min"])
        if dose.get("heavy") is not None:
            new_dose["heavyMin"] = number(dose["heavy"])
        if new_dose:
            roa["dose"] = new_dose

    duration = api_roa.get("duration") or {}
    old_duration = existing.get("duration") or {}
    new_duration = {}
    for key in ("onset", "comeup", "peak", "offset", "total", "afterglow"):
        parsed = duration_range(duration.get(key))
        if parsed and parsed.get("min") is not None:
            new_duration[key] = parsed
        elif key in old_duration:
            new_duration[key] = old_duration[key]
    if new_duration:
        roa["duration"] = new_duration

    bioavailability = api_roa.get("bioavailability")
    old_bio = existing.get("bioavailability")
    if bioavailability and bioavailability.get("max") is not None:
        roa["bioavailability"] = {
            "min": number(bioavailability.get("min")),
            "max": number(bioavailability.get("max")),
        }
    elif old_bio:
        roa["bioavailability"] = old_bio
    return roa


def merge_roas(api_roas, existing_roas):
    existing_by_name = {}
    for roa in existing_roas or []:
        if roa.get("name"):
            existing_by_name[roa["name"].lower()] = roa
    merged = []
    seen = set()
    for api_roa in api_roas or []:
        name = (api_roa.get("name") or "").lower()
        if not name:
            continue
        merged.append(build_roa(api_roa, existing_by_name.get(name)))
        seen.add(name)
    for roa in existing_roas or []:
        name = (roa.get("name") or "").lower()
        if name and name not in seen:
            merged.append(roa)
    return merged


def derive_categories(api_substance):
    # Upstream psychoactive classes are not reliable enough to map
    # automatically (a butanediol record was tagged as a psychedelic), so new
    # entries start uncategorised unless they are featured.
    categories = []
    if api_substance.get("featured"):
        categories.append("common")
    return categories


def merge_substance(api_substance, existing):
    """Return (merged_json, changed_text_fields, is_new)."""
    existing = existing or {}
    merged = json.loads(json.dumps(existing))
    is_new = not existing

    merged["name"] = api_substance.get("name") or merged.get("name")
    if api_substance.get("url"):
        merged["url"] = api_substance["url"]
    # The upstream API occasionally carries mismatched common names on a record
    # (observed: a butanediol entry listing DMT aliases), so only keep API
    # aliases when the record's own name is among them.
    api_names = api_substance.get("commonNames") or []
    name_lower = (api_substance.get("name") or "").lower()
    if is_new:
        if api_names and any(alias.lower() == name_lower for alias in api_names):
            merged["commonNames"] = api_names
        else:
            merged["commonNames"] = [api_substance.get("name")]
    elif api_names and not merged.get("commonNames"):
        merged["commonNames"] = api_names
    if is_new:
        merged.setdefault("isApproved", bool(api_substance.get("featured")))

    api_tolerance = api_substance.get("tolerance") or {}
    if api_tolerance:
        tolerance = dict(merged.get("tolerance") or {})
        for key in ("full", "zero"):
            if api_tolerance.get(key):
                tolerance[key] = api_tolerance[key]
        if tolerance.get("zero") and "zeroToleranceInHours" not in tolerance:
            hours = parse_zero_hours(tolerance["zero"])
            if hours is not None:
                tolerance["zeroToleranceInHours"] = hours
        if tolerance:
            merged["tolerance"] = tolerance

    toxicity = api_substance.get("toxicity")
    if toxicity:
        merged["toxicities"] = toxicity
    cross = api_substance.get("crossTolerances")
    if cross and not merged.get("crossTolerances"):
        merged["crossTolerances"] = cross
    if api_substance.get("addictionPotential") is not None:
        merged["addictionPotential"] = api_substance["addictionPotential"]

    if is_new:
        categories = derive_categories(api_substance)
        if categories:
            merged["categories"] = categories
        if api_substance.get("summary"):
            merged["summary"] = api_substance["summary"]
    elif not merged.get("categories"):
        categories = derive_categories(api_substance)
        if categories:
            merged["categories"] = categories

    # Interaction lists are safety-critical and use curated substance names
    # (the API renamed THC to Cannabis), so only populate them when absent.
    interaction_fields = {
        "dangerous": "dangerousInteractions",
        "unsafe": "unsafeInteractions",
        "uncertain": "uncertainInteractions",
    }
    if not merged.get("interactions") and any(
        api_substance.get(key) is not None for key in interaction_fields.values()
    ):
        interactions = {}
        for key, api_key in interaction_fields.items():
            values = api_substance.get(api_key)
            if values is not None:
                interactions[key] = names_of(values)
        merged["interactions"] = interactions

    api_roas = api_substance.get("roas")
    if api_roas:
        merged["roas"] = merge_roas(api_roas, merged.get("roas") or [])
    elif "roas" not in merged:
        merged["roas"] = []

    changed_text = {}
    for key in ("addictionPotential", "summary"):
        if merged.get(key) and (is_new or merged.get(key) != existing.get(key)):
            changed_text[key] = merged[key]
    old_tolerance = existing.get("tolerance") or {}
    new_tolerance = merged.get("tolerance") or {}
    tolerance_text = {
        key: new_tolerance[key]
        for key in ("full", "zero")
        if new_tolerance.get(key) and (is_new or new_tolerance.get(key) != old_tolerance.get(key))
    }
    if tolerance_text:
        changed_text["tolerance"] = tolerance_text
    old_toxicities = existing.get("toxicities") or []
    new_toxicities = merged.get("toxicities") or []
    if is_new or new_toxicities != old_toxicities:
        if new_toxicities:
            changed_text["toxicities"] = new_toxicities

    return merged, changed_text, is_new


def deep_merge(base, patch):
    if patch is None:
        return base
    if not isinstance(base, dict) or not isinstance(patch, dict):
        return patch
    merged = dict(base)
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def json_equal(left, right):
    return json.dumps(left, sort_keys=True, ensure_ascii=False) == json.dumps(
        right, sort_keys=True, ensure_ascii=False
    )


def load_json(path):
    if not path.exists():
        return None
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def translation_key(lang, text):
    return hashlib.sha256(f"{lang}\n{text}".encode("utf-8")).hexdigest()


def translate_text(text, lang, api_base, api_key, model, cache, cache_path):
    key = translation_key(lang, text)
    if key in cache:
        return cache[key]
    system_prompt = (
        f"你是专业翻译。把用户提供的文本翻译成{LANG_LABELS[lang]}。"
        "保留数值、剂量、单位、缩写和专有名词；药物名称使用中文常用译名。"
        "只输出译文，不要解释、注释或拒绝。"
    )
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": text},
        ],
        "temperature": 0.2,
        "max_tokens": 2048,
        "stream": False,
    }
    headers = {}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    response = http_json(f"{api_base.rstrip('/')}/chat/completions", payload, headers)
    choices = response.get("choices") or []
    if not choices:
        raise RuntimeError(f"translation API returned no choices: {str(response)[:300]}")
    result = (choices[0].get("message") or {}).get("content", "").strip()
    if not result:
        raise RuntimeError("translation API returned empty text")
    cache[key] = result
    write_json(cache_path, cache)
    return result


def translated_overlay(changed_text, root, lang, translate, existing_overlay):
    overlay = {}
    existing_overlay = existing_overlay or {}
    if changed_text.get("summary"):
        overlay["summary"] = translate(changed_text["summary"])
    if changed_text.get("addictionPotential"):
        overlay["addictionPotential"] = translate(changed_text["addictionPotential"])
    tolerance = changed_text.get("tolerance") or {}
    if tolerance:
        current = dict(existing_overlay.get("tolerance") or {})
        for key in ("full", "zero"):
            if tolerance.get(key):
                current[key] = translate(tolerance[key])
        root_tolerance = root.get("tolerance") or {}
        if "zeroToleranceInHours" in root_tolerance:
            current["zeroToleranceInHours"] = root_tolerance["zeroToleranceInHours"]
        if current:
            overlay["tolerance"] = current
    toxicities = changed_text.get("toxicities")
    if toxicities:
        overlay["toxicities"] = [translate(item) for item in toxicities]
    localized = existing_overlay.get("localizedName")
    if localized:
        overlay["localizedName"] = localized
    else:
        overlay["localizedName"] = translate(root["name"])
    return overlay


def multipart_upload(url, admin_token, file_path, title, description, official_link):
    boundary = "----journal" + uuid.uuid4().hex
    parts = []

    def add_field(name, value):
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode("utf-8")
        )

    add_field("title", title)
    add_field("description", description)
    add_field("official_link", official_link)
    file_bytes = file_path.read_bytes()
    parts.append(
        (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"{file_path.name}\"\r\nContent-Type: application/zip\r\n\r\n"
        ).encode("utf-8")
        + file_bytes
        + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    body = b"".join(parts)
    request = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "X-Admin-Token": admin_token,
            "User-Agent": "journal-pw-sync/1.0",
        },
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read().decode("utf-8", "replace")


def build_pack(changes, output_dir, version_code, version_name):
    output_dir.mkdir(parents=True, exist_ok=True)
    pack_path = output_dir / f"{PACK_REGISTER_NAME}_{version_code}.zip"
    manifest = {
        "registerName": PACK_REGISTER_NAME,
        "titleTranslateable": "ext_pw_core_title",
        "descriptionTranslateable": "ext_pw_core_desc",
        "officalLink": "https://github.com/XiaoXinQwQOvO/journal-android-multilingual",
        "updateJsonLink": "https://journal.xiaoxinww.xin/",
        "versionName": version_name,
        "versionCode": version_code,
    }
    with zipfile.ZipFile(pack_path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        for lang, (title, description) in LANG_TITLES.items():
            archive.writestr(
                f"lang/{lang}.json",
                json.dumps(
                    {"ext_pw_core_title": title, "ext_pw_core_desc": description},
                    ensure_ascii=False,
                    indent=2,
                ),
            )
        for change in changes:
            archive.writestr(
                f"substances/root/{change['filename']}",
                json.dumps(change["root"], ensure_ascii=False, indent=2),
            )
            if change["en_text"]:
                archive.writestr(
                    f"substances/en_us/{change['filename']}",
                    json.dumps(change["en_text"], ensure_ascii=False, indent=2),
                )
            for lang, overlay in change["translations"].items():
                archive.writestr(
                    f"substances/{lang}/{change['filename']}",
                    json.dumps(overlay, ensure_ascii=False, indent=2),
                )
    return pack_path


def main():
    parser = argparse.ArgumentParser(description="Sync PsychonautWiki substance data into an extension pack")
    parser.add_argument("--assets-dir", required=True, help="path to app/src/main/assets/substances")
    parser.add_argument("--out-dir", default="dist", help="directory for the generated zip")
    parser.add_argument("--api-base", default=os.environ.get("PW_TRANSLATE_API_BASE", DEFAULT_TRANSLATE_API))
    parser.add_argument("--api-key", default=os.environ.get("PW_TRANSLATE_API_KEY", ""))
    parser.add_argument("--model", default=os.environ.get("PW_TRANSLATE_MODEL", DEFAULT_MODEL))
    parser.add_argument("--cache", default=str(Path(__file__).with_name(".pw_translation_cache.json")))
    parser.add_argument("--state-dir", default=str(Path(__file__).with_name(".pw_state")))
    parser.add_argument("--include-new", action="store_true", help="add API substances that are not in the local assets")
    parser.add_argument("--no-translate", action="store_true", help="only update English/base data")
    parser.add_argument("--limit", type=int, default=None, help="process at most N API substances (debugging)")
    parser.add_argument("--publish", metavar="SERVER_URL", help="upload the pack, e.g. https://journal.xiaoxinww.xin")
    parser.add_argument("--advance-state", action="store_true", help="record this run as synced when publishing manually")
    parser.add_argument("--admin-token", default=os.environ.get("ADMIN_TOKEN", ""))
    args = parser.parse_args()

    assets_dir = Path(args.assets_dir)
    root_dir = assets_dir / "root"
    if not root_dir.is_dir():
        raise SystemExit(f"missing {root_dir}")
    if not args.no_translate and not args.api_key:
        raise SystemExit("translation needs --api-key or PW_TRANSLATE_API_KEY (or pass --no-translate)")

    cache_path = Path(args.cache)
    translation_cache = load_json(cache_path) or {}

    def translate(text, lang):
        return translate_text(
            text,
            lang,
            args.api_base,
            args.api_key,
            args.model,
            translation_cache,
            cache_path,
        )

    api_substances = fetch_all_substances(args.limit)
    state_dir = Path(args.state_dir)
    changes = []
    state_updates = {}
    skipped = 0
    for api_substance in api_substances:
        name = api_substance.get("name")
        if not name:
            continue
        filename = f"{sanitize_filename(name)}.json"
        asset_root = load_json(root_dir / filename)
        state_root = load_json(state_dir / "root" / filename)
        baseline_root = (
            deep_merge(asset_root, state_root) if (asset_root or state_root) else None
        )
        if baseline_root is None and not args.include_new:
            skipped += 1
            continue
        merged, changed_text, is_new = merge_substance(api_substance, baseline_root)
        root_changed = baseline_root is None or not json_equal(baseline_root, merged)
        if not root_changed and not changed_text:
            continue

        en_text = {}
        for key in ("summary", "addictionPotential", "toxicities"):
            if changed_text.get(key):
                en_text[key] = changed_text[key]
        if changed_text.get("tolerance"):
            en_text["tolerance"] = changed_text["tolerance"]
        if is_new and merged.get("summary"):
            en_text.setdefault("summary", merged["summary"])

        translations = {}
        if changed_text and not args.no_translate:
            for current_lang in ("zh_cn", "zh_tw"):
                asset_overlay = load_json(assets_dir / current_lang / filename)
                state_overlay = load_json(state_dir / current_lang / filename)
                baseline_overlay = (
                    deep_merge(asset_overlay, state_overlay)
                    if (asset_overlay or state_overlay)
                    else None
                )
                new_overlay = translated_overlay(
                    changed_text,
                    merged,
                    current_lang,
                    lambda text, lang=current_lang: translate(text, lang),
                    baseline_overlay,
                )
                cumulative = deep_merge(baseline_overlay, new_overlay)
                if cumulative:
                    translations[current_lang] = cumulative
                    state_updates[f"{current_lang}/{filename}"] = cumulative

        # Do not advance state for English-only runs that skipped translation,
        # otherwise the untranslated text would never be picked up later.
        if not (args.no_translate and changed_text):
            state_updates[f"root/{filename}"] = merged

        changes.append(
            {
                "name": name,
                "filename": filename,
                "root": merged,
                "en_text": en_text,
                "translations": translations,
                "is_new": is_new,
            }
        )
        log(f"{'new ' if is_new else 'updated '}{name} ({len(changed_text)} text fields)")

    log(f"{len(changes)} substances changed, {skipped} API-only substances skipped")
    if not changes:
        log("nothing to publish")
        return 0

    version_code = int(time.time() // 60)
    version_name = dt.datetime.now(dt.timezone.utc).strftime("%Y.%m.%d.%H%M")
    pack_path = build_pack(changes, Path(args.out_dir), version_code, version_name)
    digest = hashlib.sha256(pack_path.read_bytes()).hexdigest()
    log(f"pack: {pack_path} ({pack_path.stat().st_size} bytes, sha256 {digest})")

    if args.publish:
        if not args.admin_token:
            raise SystemExit("--publish needs --admin-token or ADMIN_TOKEN")
        result = multipart_upload(
            f"{args.publish.rstrip('/')}/api/v1/admin/packs",
            args.admin_token,
            pack_path,
            "PsychonautWiki data",
            "Latest substance data and translations from PsychonautWiki",
            "https://github.com/XiaoXinQwQOvO/journal-android-multilingual",
        )
        log(f"published {PACK_REGISTER_NAME} v{version_code}: {result[:300]}")

    if args.publish or args.advance_state:
        for relative, value in state_updates.items():
            write_json(state_dir / relative, value)
        log(f"state advanced in {state_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())