# cosViewer

Android 原生客户端：浏览、播放、下载。（Java + 原生 View，无 Gradle，手机/服务器上用 aapt + javac + d8 直接构建）

## 目录

```
src/             Java 源码
res/             资源（布局、图标、drawable）
assets/          player.html + hls.min.js（网页播放器内核）
libs/arm64-v8a/  libmpv 及 ffmpeg 动态库（约 31MB，不入库，见 releases）
AndroidManifest.xml
build.sh         本机构建脚本
align.py         zipalign 替代（纯 Python）
secret.properties.example  本机配置模板
```

## 构建

依赖：`aapt`、`javac`（JDK 8+）、d8（R8 jar）、apksigner、`android.jar`（API 34）、python3、zip。

```bash
cp secret.properties.example secret.properties   # 填入真实接口配置
./build.sh                                       # 产物见 $OUT/cosdl.apk
```

`build.sh` 会在构建时根据 `secret.properties` 生成 `Secret.java`，源码里不含接口地址、密钥等敏感值。

需要的本地文件（不入库）：

- `libs/arm64-v8a/*.so`：libmpv / ffmpeg 动态库，从 [mpv-android](https://github.com/mpv-android/mpv-android) 发布包中提取
- `debug.keystore` 或自己的签名文件（路径与密码在 `secret.properties` 里配置）

## 检查更新

App 通过 GitHub Releases 检查更新：

1. 定一个版本号（`build.sh` 里的 `VERSION_CODE` / `VERSION_NAME`）
2. 新建 release，tag 写 `v10.4`（数字会与本地 `versionName` 逐段比较）
3. 把签名后的 APK 作为 release 附件上传（文件名以 `.apk` 结尾）
4. 在 `secret.properties` 里配置 `update.owner` 和 `update.repo`

App 启动时会静默检查一次，菜单里的「检查更新」可手动触发；发现新版本可下载并直接调起安装。

## 发布（github.com 不可达时）

部分网络能访问 `api.github.com` 但访问不了 `github.com`，此时 `git push` 和网页上传都会失败。用 `publish.py` 走 GitHub API 完成全部动作：

```bash
# secret.properties 里先填 github.token（classic PAT，勾选 repo）
./publish.py --apk /root/workspace/android-build/cosdl-out/cosViewer.apk --notes "更新说明"
```

它会：建仓库（若不存在）→ 增量推送源码到 main → 按 `build.sh` 的 VERSION_NAME 建 release（tag = v10.5）→ 上传 APK 附件。
`git push` 可用时也可以正常用 git，两条路等效。

App 内更新下载走 `api.github.com/repos/…/releases/assets/{id}`（`Accept: application/octet-stream`），不依赖 `github.com`。

## 说明

- 仅支持 arm64-v8a。
- 播放器内核：内置 mpv（默认）/ WebView+hls.js / 系统 MediaPlayer，可在设置里切换。
