# 三云Buddy 企业管理后台部署手册：Docker Compose + 宝塔 HTTPS

本次部署以独立的三云Buddy 企业管理后台为企业服务端，使用 PostgreSQL。宝塔 Nginx 在 HTTPS 域名的 443 端口接收请求，转发到同机 `127.0.0.1:18890`；Compose 内的 `admin` 连接未对宿主机发布端口的 `db`。员工的 Agent 和工具仍在 Desktop 本机运行。

> 本文使用 `admin.example.com` 等示例。实际域名、服务器信息、账号、密码、模型 Key 和证书私钥不写入仓库。旧的 IP + 8443 临时入口不属于这套部署。

## 1. 构建并校验安装包

构建机需要 Java 17+、Maven 3.9+、Node 22.19+ 或 24+、Python 3.9+。在 Admin 源码根目录用 Bash 运行构建命令；服务器需要 Docker Engine、支持 `up --wait` 的 Docker Compose v2 和 Python 3.9+。

```bash
set -e
mvn -f server/pom.xml clean test package
npm --prefix web ci
npm --prefix web run build
version=$(git rev-parse --short HEAD)
archive="/tmp/workdsh-admin-${version}.zip"
python3 scripts/pack-admin-release.py --output "$archive"
sha256sum "$archive"
```

通过校验过服务器身份的 SSH/SFTP，把 ZIP 传到服务器的 `/opt/workdsh-admin/incoming/admin.zip`，把同版 `scripts/install-admin-release.py` 放到 `incoming/`。服务器上核对 ZIP 的 SHA-256 与构建侧记录一致后，再用安装器校验包内清单并解包到**新的**版本目录：

```bash
cd /opt/workdsh-admin
sha256sum incoming/admin.zip
```

在同一个服务器 Bash 会话中，将 `version` 设为构建包对应的 Git 短提交号，将 `JAVA_IMAGE` 设为已批准的 Java 17+ 镜像完整引用（包含 `@sha256:` 和摘要），然后执行：

```bash
: "${version:?set the Git revision}" "${JAVA_IMAGE:?set the digest-pinned Java image}"
release="releases/$version"
python3 incoming/install-admin-release.py incoming/admin.zip "$release"
docker build -f "$release/deploy/docker/Admin.Dockerfile" \
  --build-arg "JAVA_IMAGE=$JAVA_IMAGE" -t "workdsh-admin:$version" "$release"
```

不要用浮动标签代替镜像摘要。若官方仓库拉取不通，可使用获准的镜像源，仍需核对对应摘要。安装包不包含运行配置、数据库或凭据。详见[后台候选交付](ADMIN-SERVER-CANDIDATE.md)。

## 2. 配置 Compose 和凭据

将 `compose.yaml` 放在 `/opt/workdsh-admin/`。首次部署可按下列模板填写；替换 PostgreSQL 15 镜像引用、Admin 镜像版本和域名。已有部署保留原项目名和数据库卷，只改本次所需的字段，不要用模板覆盖。

```yaml
name: workdsh-admin
services:
  db:
    image: "<已核对摘要的 PostgreSQL 15 镜像引用>"
    env_file: ./secrets/postgres.env
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U \"$${POSTGRES_USER}\" -d \"$${POSTGRES_DB}\""]
      interval: 5s
      timeout: 3s
      retries: 12
    volumes: [postgres-data:/var/lib/postgresql/data]
    networks: [backend]
  admin:
    image: "workdsh-admin:<构建包版本>"
    env_file: ./secrets/admin.env
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: enterprise-postgres
      PORT: "18890"
      JDBC_URL: jdbc:postgresql://db:5432/workdsh_admin
      JDBC_USER: workdsh_app
      WORKDSH_ADMIN_WEB_ORIGIN: https://admin.example.com
    depends_on:
      db:
        condition: service_healthy
    ports: ["127.0.0.1:18890:18890"]
    networks: [backend, egress]
volumes:
  postgres-data: {}
networks:
  backend:
    internal: true
  egress: {}
```

- `secrets/` 放在服务器部署目录下，由 root 管理（目录 `0700`、文件 `0600`），不复制进 Git 或安装包。校验用 `docker compose config --quiet`，不要打印展开后的配置。
- `secrets/postgres.env`：`POSTGRES_DB` 使用 `workdsh_admin`；配置独立的维护账号 `POSTGRES_USER` 和随机 `POSTGRES_PASSWORD`。这些是新数据库卷的初始化设置，不是以后改数据库密码的办法。
- `secrets/admin.env`：`JDBC_PASSWORD` 为运行角色 `workdsh_app` 的独立密码；`WORKDSH_BOOTSTRAP_ADMIN_EMAIL` 和 `WORKDSH_BOOTSTRAP_ADMIN_PASSWORD` 为首次初始化的企业所有者凭据（密码至少 12 位）；`WORKDSH_ORGANIZATION_NAME` 可选，仅用于新库，默认 `三云Buddy 企业`；升级保留已保存的企业名称。另配置 `WORKDSH_SECRETS_ENCRYPTION_KEY`（32 个随机字节的 Base64）及独立的 `WORKDSH_SECRETS_SERVICE_KEY`。保管好加密密钥，恢复时不能用随意生成的新密钥替代。

## 3. 初始化 PostgreSQL，再启动 Admin

以下建角色步骤只用于**新库**。用维护账号执行迁移；应用不能使用维护账号，也不能创建 schema 或改写迁移记录。已有数据库见[迁移与权限说明](postgres/README.md)，不要重新初始化。

```bash
set -euo pipefail
cd /opt/workdsh-admin
docker compose config --quiet
docker compose up -d --wait db
docker compose exec db sh -ec 'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

在 `psql` 中创建运行角色。`\password` 会提示输入，用与 `JDBC_PASSWORD` 相同的值，不把密码写在 SQL 或命令行上：

```sql
CREATE ROLE workdsh_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
\password workdsh_app
\q
```

在服务器 Bash 中使用第 1 节的 `release` 路径，应用该版本自带的迁移及运行授权，并检查版本账本：

```bash
set -o pipefail
pg_maint() {
  docker compose exec -T db sh -ec \
    'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' sh "$@"
}
python3 "$release/scripts/migrate-postgres.py" --emit-sql | pg_maint
pg_maint < "$release/deploy/postgres/runtime-grants.sql"
pg_maint -c 'SELECT version, filename FROM public.workdsh_schema_history ORDER BY version;'
```

确认迁移、授权都成功后启动 Admin，不重建数据库：

```bash
docker compose up -d --no-deps --no-build --pull never admin
docker compose ps
curl --fail --silent --show-error --output /dev/null http://127.0.0.1:18890/
```

应用就绪后本机首页应为 HTTP 200。公网不开放 `18890` 或 `5432`；这是给本机 Nginx 用的回环入口，不是 Desktop 要填写的地址。

## 4. 在宝塔配置域名、TLS 和反向代理

1. 将域名解析到部署服务器。在宝塔为该域名启用受系统信任的 HTTPS 证书及续期，监听 443。多个域名可以共用已有 Nginx 的 443，不需要改其他站点或加 8443。
2. 为该站点把 `/`（含 `/api/`）反向代理到 `http://127.0.0.1:18890`。保留客户端发来的 `Origin`、`Authorization`（含 Desktop 的 Bearer）和 Cookie，不改写响应的 `Set-Cookie`。改完在宝塔检查 Nginx 配置并重载。不要使用仅用于本地验收的 AdminFront 作为公网代理。
3. `WORKDSH_ADMIN_WEB_ORIGIN` 必须是浏览器实际访问的完整 **origin**（如 `https://admin.example.com`；无需尾部斜杠、`/api` 或其他路径）。浏览器登录或带 Cookie 的写请求若来源不一致，将返回 403。若更换域名，改 Compose 中这个字段，校验后执行 `docker compose up -d --no-deps --no-build --pull never admin`，不动数据库或其他站点。

## 5. 验证与后续维护

```bash
origin=https://admin.example.com  # 换成实际 HTTPS origin
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' "$origin/"
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' "$origin/api/auth/me"
```

- 公网需要正常通过证书校验，首页返回 `200`，未登录的 `/api/auth/me` 返回 `401`；不要用 `curl -k` 掩盖证书问题。
- 用企业所有者在浏览器实际登录，确认可进入控制台、退出后需要重新登录。Desktop 安装并启用企业连接插件后，企业地址填同一个 HTTPS origin，用企业成员账号登录；公司模型 API 地址和访问 Key 在 Desktop 的自定义模型设置中单独配置。公网明文 HTTP 的 IP/端口不是有效的企业登录入口。
- 初始化变量只在空库创建组织及 `OWNER`。`OWNER` 是本组织所有者，不是跨企业超管。已有账号改密码走已认证的 `POST /api/auth/change-password`，提供当前密码及至少 12 位的新密码；旧会话会被撤销。改 `WORKDSH_BOOTSTRAP_ADMIN_*` 或重启容器不会修改数据库里的现有账号。
- 升级前备份 PostgreSQL（如用 `pg_dump`）及包外配置、加密密钥，限制备份权限并验证可恢复。保留上一个可用镜像和 Compose 配置；有 schema 变更时，镜像回退前先核对数据库兼容性。不要执行 `docker compose down -v`，它会删除持久化数据库卷。
