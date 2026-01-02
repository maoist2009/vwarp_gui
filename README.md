# Vwarp Android 图形客户端

Vwarp是一个Warp第三方客户端，这是一个Vwarp第三方图形Android客户端

## How to build

### 重新编译vwarp

```bash
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -ldflags="-s -w -checklinkname=0" -o libvwarp.so ./cmd/vwarp
```

复制到`libvwarp.so`到`app/src/main/jniLibs/arm64-v8a`

### 构建Android应用

```bash
./gradlew assembleDebug
```
