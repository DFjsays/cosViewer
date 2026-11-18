#!/usr/bin/env python3
"""
用 GitHub API 发布 cosViewer（适合 github.com 被墙但 api.github.com 可达的网络）。

做四件事：
  1. 确保仓库存在（公开）
  2. 把源码以 git commit 方式推到 main 分支（增量：没变的文件不重传）
  3. 用 build.sh 的 VERSION_NAME 建 release（tag = v10.5）
  4. 上传签名后的 cosViewer.apk 作为 release 附件

用法：
  ./publish.py --apk /root/workspace/android-build/cosdl-out/cosViewer.apk \
               --notes "更新说明（可省略）"

需要在 secret.properties 里填 github.token（classic token，勾选 repo 权限）。
用 --dry-run 可以先看会提交哪些文件。
"""
import argparse
import base64
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
BRANCH = "main"

# 不入库（与 .gitignore 一致）
EXCLUDE_DIRS = {".git", "libs", "__pycache__", ".idea", "build"}
EXCLUDE_FILES = {"secret.properties"}
EXCLUDE_SUFFIX = (".apk", ".log", ".keystore", ".jks", ".pyc")


def prop(key, default=""):
    for name in ("secret.properties", "secret.properties.example"):
        path = os.path.join(ROOT, name)
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith(key + "="):
                    v = line.split("=", 1)[1].strip()
                    if v:
                        return v
    return default


TOKEN = prop("github.token")
OWNER = prop("update.owner")
REPO = prop("update.repo")


def die(msg):
    print("!! " + msg)
    sys.exit(1)


def req(method, url, data=None, headers=None, raw=False, timeout=90):
    h = {"User-Agent": "cosViewer-publish", "Authorization": "token " + TOKEN}
    if headers:
        h.update(headers)
    body = None
    if data is not None:
        if raw:
            body = data
        else:
            body = json.dumps(data).encode("utf-8")
            h.setdefault("Content-Type", "application/json")
    r = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            b = resp.read()
            if raw:
                return resp.status, b
            return resp.status, (json.loads(b) if b else None)
    except urllib.error.HTTPError as e:
        b = e.read()
        try:
            return e.code, json.loads(b)
        except Exception:
            return e.code, {"raw": b[:400].decode("utf-8", "replace")}
    except Exception as e:
        return 0, {"error": str(e)}


def collect_files():
    out = []
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
        for f in files:
            if f in EXCLUDE_FILES or f.endswith(EXCLUDE_SUFFIX):
                continue
            full = os.path.join(base, f)
            rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
            with open(full, "rb") as fh:
                out.append((rel, fh.read()))
    return sorted(out)


def blob_sha(content):
    return hashlib.sha1(b"blob %d\0" % len(content) + content).hexdigest()


def ensure_repo():
    code, j = req("GET", "%s/repos/%s/%s" % (API, OWNER, REPO))
    if code == 200:
        print("仓库已存在：%s/%s" % (OWNER, REPO))
        return
    if code != 404:
        die("查询仓库失败 %s %s" % (code, j))
    print("创建公开仓库 %s/%s …" % (OWNER, REPO))
    code, j = req("POST", "%s/user/repos" % API, {
        "name": REPO,
        "private": False,
        "description": "cosViewer Android 客户端",
        "has_issues": True,
        "has_wiki": False,
        "auto_init": False,
    })
    if code not in (200, 201):
        die("创建仓库失败 %s %s" % (code, j))


def current_commit():
    code, j = req("GET", "%s/repos/%s/%s/git/ref/heads/%s" % (API, OWNER, REPO, BRANCH))
    if code != 200:
        return None
    return j.get("object", {}).get("sha")


def remote_tree(commit_sha):
    code, j = req("GET", "%s/repos/%s/%s/git/trees/%s?recursive=1" % (API, OWNER, REPO, commit_sha))
    if code != 200:
        return {}
    return {e["path"]: e["sha"] for e in j.get("tree", []) if e.get("type") == "blob"}


def push_sources(message, dry_run):
    files = collect_files()
    print("待检查文件：%d 个，共 %.1f MB" % (len(files), sum(len(c) for _, c in files) / 1048576.0))

    parent = current_commit()
    known = remote_tree(parent) if parent else {}
    print("远端已有 commit：%s" % ("无（首次提交）" if not parent else parent[:8]))

    entries = []
    uploaded = 0
    for rel, content in files:
        sha = blob_sha(content)
        if known.get(rel) == sha:
            continue
        if dry_run:
            print("  将上传 %s (%.1f KB)" % (rel, len(content) / 1024.0))
            uploaded += 1
            continue
        code, j = req("POST", "%s/repos/%s/%s/git/blobs" % (API, OWNER, REPO), {
            "content": base64.b64encode(content).decode("ascii"),
            "encoding": "base64",
        })
        if code not in (200, 201):
            die("上传 blob 失败 %s %s %s" % (rel, code, j))
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": j["sha"]})
        uploaded += 1
        if uploaded % 20 == 0:
            print("  …已上传 %d 个" % uploaded)

    if dry_run:
        print("dry-run：将新增/更新 %d 个文件" % uploaded)
        return None
    if not entries:
        print("源码无变化，跳过 commit")
        return parent

    tree = {"tree": entries}
    if parent:
        code, base = req("GET", "%s/repos/%s/%s/git/commits/%s" % (API, OWNER, REPO, parent))
        if code == 200:
            tree["base_tree"] = base["tree"]["sha"]
            # 已存在的文件也要带上，否则会从整树里消失
            all_entries = []
            for rel, content in files:
                sha = known.get(rel) or blob_sha(content)
                if any(e["path"] == rel for e in entries):
                    continue
                all_entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": sha})
            tree["tree"] = all_entries + entries
    code, j = req("POST", "%s/repos/%s/%s/git/trees" % (API, OWNER, REPO), tree)
    if code not in (200, 201):
        die("创建 tree 失败 %s %s" % (code, j))
    tree_sha = j["sha"]

    commit = {"message": message, "tree": tree_sha}
    if parent:
        commit["parents"] = [parent]
    code, j = req("POST", "%s/repos/%s/%s/git/commits" % (API, OWNER, REPO), commit)
    if code not in (200, 201):
        die("创建 commit 失败 %s %s" % (code, j))
    commit_sha = j["sha"]

    if parent:
        code, j = req("PATCH", "%s/repos/%s/%s/git/refs/heads/%s" % (API, OWNER, REPO, BRANCH),
                      {"sha": commit_sha, "force": False})
        if code not in (200, 201):
            die("更新分支失败 %s %s" % (code, j))
    else:
        code, j = req("POST", "%s/repos/%s/%s/git/refs" % (API, OWNER, REPO),
                      {"ref": "refs/heads/" + BRANCH, "sha": commit_sha})
        if code not in (200, 201):
            die("创建分支失败 %s %s" % (code, j))
    print("已提交到 %s：%s" % (BRANCH, commit_sha[:8]))
    return commit_sha


def create_release(tag, notes, dry_run):
    code, j = req("GET", "%s/repos/%s/%s/releases/tags/%s" % (API, OWNER, REPO, tag))
    if code == 200:
        print("release %s 已存在，复用 id=%s" % (tag, j["id"]))
        return j["id"]
    if dry_run:
        print("将创建 release %s" % tag)
        return None
    code, j = req("POST", "%s/repos/%s/%s/releases" % (API, OWNER, REPO), {
        "tag_name": tag,
        "name": tag,
        "body": notes or ("cosViewer " + tag),
        "draft": False,
        "prerelease": False,
    })
    if code not in (200, 201):
        die("创建 release 失败 %s %s" % (code, j))
    print("已创建 release %s" % tag)
    return j["id"]


def upload_asset(release_id, apk_path, dry_run):
    name = os.path.basename(apk_path)
    if dry_run:
        print("将上传附件 %s (%.1f MB)" % (name, os.path.getsize(apk_path) / 1048576.0))
        return
    code, j = req("GET", "%s/repos/%s/%s/releases/%s/assets" % (API, OWNER, REPO, release_id))
    if code == 200:
        for a in j:
            if a["name"] == name:
                print("删除旧附件 %s" % name)
                req("DELETE", "%s/repos/%s/%s/releases/assets/%s" % (API, OWNER, REPO, a["id"]))
    with open(apk_path, "rb") as f:
        data = f.read()
    print("上传 %s (%.1f MB) …" % (name, len(data) / 1048576.0))
    code, j = req("POST",
                  "%s/repos/%s/%s/releases/%s/assets?name=%s" % (UPLOADS, OWNER, REPO, release_id,
                                                                 urllib.parse.quote(name)),
                  data=data, raw=True,
                  headers={"Content-Type": "application/vnd.android.package-archive",
                           "Content-Length": str(len(data))})
    if code not in (200, 201):
        die("上传附件失败 %s %s" % (code, str(j)[:400]))
    print("附件已上传：%s" % j.get("browser_download_url", ""))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=os.path.join(ROOT, "build", "cosViewer.apk"))
    ap.add_argument("--notes", default="")
    ap.add_argument("--tag", default="")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if args.dry_run:
        files = collect_files()
        print("dry-run：将提交 %d 个文件，%.1f MB" % (len(files), sum(len(c) for _, c in files) / 1048576.0))
        for rel, c in files:
            print("  %8.1f KB  %s" % (len(c) / 1024.0, rel))
        return

    if not TOKEN:
        die("缺少 github.token：在 secret.properties 里加一行 github.token=你的PAT\n"
            "   生成地址 https://github.com/settings/tokens （classic，勾选 repo）")
    if not OWNER or not REPO:
        die("secret.properties 里缺少 update.owner / update.repo")

    ver = ""
    build = os.path.join(ROOT, "build.sh")
    if os.path.exists(build):
        for line in open(build, encoding="utf-8"):
            if line.startswith("VERSION_NAME="):
                ver = line.split("=", 1)[1].strip()
    tag = args.tag or ("v" + (ver or "0.0"))
    print("仓库：%s/%s  版本：%s" % (OWNER, REPO, tag))

    ensure_repo()
    push_sources("cosViewer " + tag, args.dry_run)
    rid = create_release(tag, args.notes, args.dry_run)
    if not args.dry_run:
        if not os.path.exists(args.apk):
            die("找不到 APK：%s" % args.apk)
        upload_asset(rid, args.apk, False)
    print("完成。")


if __name__ == "__main__":
    main()
