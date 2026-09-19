# -*- coding: utf-8 -*-
"""生成 release 签名密钥库 + keystore.properties，并输出证书指纹。
密码随机生成，写入本地（不入 Git）供用户保存。
"""
import os
import re
import secrets
import string
import subprocess

ROOT = r"E:\study\class\cleantable"
KS_DIR = os.path.join(ROOT, "keystore")
KS = os.path.join(KS_DIR, "zhike-release.jks")
ALIAS = "zhike"
PROPS = os.path.join(ROOT, "keystore.properties")
BACKUP = r"E:\study\class\backup\2026-09-19-pre-1.0\签名密钥与密码-请妥善保存.txt"
KEYTOOL = r"C:\Program Files\Java\jdk-22\bin\keytool.exe"

os.makedirs(KS_DIR, exist_ok=True)

# 清理残留：此前被中断的运行可能留下密码未知的密钥库
for stale in (KS, PROPS):
    if os.path.exists(stale):
        os.remove(stale)
        print("已删除残留:", stale)

alphabet = string.ascii_letters + string.digits
password = "".join(secrets.choice(alphabet) for _ in range(20))

cmd = [
    KEYTOOL, "-genkeypair", "-v",
    "-keystore", KS,
    "-alias", ALIAS,
    "-keyalg", "RSA", "-keysize", "2048",
    "-validity", "10950",
    "-storepass", password,
    "-keypass", password,
    "-dname", "CN=Zhike, OU=Dev, O=Zhike, L=Beijing, ST=Beijing, C=CN",
]
r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
if r.returncode != 0:
    print("KEYTOOL FAILED")
    print(r.stdout[-2000:])
    print(r.stderr[-2000:])
    raise SystemExit(1)

# keystore.properties（Gradle 读取；已加入 .gitignore）
with open(PROPS, "w", encoding="utf-8") as f:
    f.write("storeFile=keystore/zhike-release.jks\n")
    f.write(f"storePassword={password}\n")
    f.write(f"keyAlias={ALIAS}\n")
    f.write(f"keyPassword={password}\n")

# 证书指纹
r2 = subprocess.run(
    [KEYTOOL, "-list", "-v", "-keystore", KS, "-alias", ALIAS, "-storepass", password],
    capture_output=True, text=True, encoding="utf-8", errors="replace",
)
info = r2.stdout
fps = {}
for name in ("SHA1", "SHA256", "MD5"):
    m = re.search(rf"{name}: ([0-9A-F:]+)", info)
    if m:
        fps[name] = m.group(1)

# 备份密码到仓库外的备份目录
with open(BACKUP, "w", encoding="utf-8") as f:
    f.write("【执课 正式签名密钥库 —— 请立即妥善保存，丢失将无法更新已发布的应用】\n\n")
    f.write(f"密钥库文件：{KS}\n")
    f.write(f"别名 alias：{ALIAS}\n")
    f.write(f"密钥库密码 storePassword：{password}\n")
    f.write(f"密钥密码 keyPassword：{password}\n")
    f.write("有效期：30 年\n\n")
    for k, v in fps.items():
        f.write(f"{k}：{v}\n")
    f.write(f"\n配置文件（勿提交到 Git）：{PROPS}\n")

print("KEYSTORE_OK")
print("ALIAS:", ALIAS)
print("PASSWORD:", password)
for k, v in fps.items():
    print(f"{k}: {v}")
print("BACKUP_FILE:", BACKUP)
