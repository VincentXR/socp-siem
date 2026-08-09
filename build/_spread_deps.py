#!/usr/bin/env python3
"""一次性脚本：把横切平台模块 + prometheus + starter-test 补齐到全部 17 个服务的 pom。

幂等：已存在的 artifactId 不重复插入。插入点为 </dependencies> 之前。
api-gateway 是 WebFlux，单独处理（只加 socp-auth 且 exclude servlet 栈）。
"""
import pathlib
import re
import sys

SERVICES = pathlib.Path(__file__).resolve().parent.parent / "services"

PLATFORM = ["socp-error", "socp-tenant", "socp-auth", "socp-audit",
            "socp-obs", "socp-ratelimit", "socp-data"]

IND = " " * 8


def dep(group, artifact, extra=""):
    return (f"{IND}<dependency>\n"
            f"{IND}    <groupId>{group}</groupId>\n"
            f"{IND}    <artifactId>{artifact}</artifactId>\n"
            f"{extra}"
            f"{IND}</dependency>\n")


PROMETHEUS = dep("io.micrometer", "micrometer-registry-prometheus",
                 f"{IND}    <scope>runtime</scope>\n")
STARTER_TEST = dep("org.springframework.boot", "spring-boot-starter-test",
                   f"{IND}    <scope>test</scope>\n")

# 网关是 WebFlux：spring-boot-starter-web 一旦进 classpath，Boot 会判定成 Servlet 应用，
# Spring Cloud Gateway 直接失效。socp-auth 只取 JwtValidator（零 Web 依赖），
# 因此把 starter-web 整棵子树 exclude 掉（同时挡住 socp-tenant / socp-error 传递进来的那份）。
GATEWAY_AUTH = (
    f"{IND}<!-- 复用 JwtValidator；WebFlux 环境必须 exclude servlet 栈，否则网关退化成 MVC 应用 -->\n"
    f"{IND}<dependency>\n"
    f"{IND}    <groupId>com.socp.platform</groupId>\n"
    f"{IND}    <artifactId>socp-auth</artifactId>\n"
    f"{IND}    <exclusions>\n"
    f"{IND}        <exclusion>\n"
    f"{IND}            <groupId>org.springframework.boot</groupId>\n"
    f"{IND}            <artifactId>spring-boot-starter-web</artifactId>\n"
    f"{IND}        </exclusion>\n"
    f"{IND}    </exclusions>\n"
    f"{IND}</dependency>\n"
)


def has_artifact(pom_text, artifact):
    return re.search(r"<artifactId>\s*%s\s*</artifactId>" % re.escape(artifact), pom_text) is not None


def process(path, blocks):
    text = path.read_text(encoding="utf-8")
    add = [b for name, b in blocks if not has_artifact(text, name)]
    if not add:
        return []
    idx = text.rindex("</dependencies>")
    text = text[:idx] + "".join(add) + text[idx:]
    path.write_text(text, encoding="utf-8")
    return [name for name, b in blocks if not has_artifact(path.read_text(encoding="utf-8")[:0] or "", name)]


def main():
    changed = {}
    for svc_dir in sorted(p for p in SERVICES.iterdir() if p.is_dir()):
        pom = svc_dir / "pom.xml"
        if not pom.exists():
            continue
        name = svc_dir.name
        if name == "api-gateway":
            blocks = [("socp-auth", GATEWAY_AUTH),
                      ("micrometer-registry-prometheus", PROMETHEUS),
                      ("spring-boot-starter-test", STARTER_TEST)]
        else:
            blocks = [(a, dep("com.socp.platform", a)) for a in PLATFORM]
            blocks.append(("micrometer-registry-prometheus", PROMETHEUS))
            blocks.append(("spring-boot-starter-test", STARTER_TEST))

        text = pom.read_text(encoding="utf-8")
        missing = [(a, b) for a, b in blocks if not has_artifact(text, a)]
        if not missing:
            continue
        idx = text.rindex("</dependencies>")
        pom.write_text(text[:idx] + "".join(b for _, b in missing) + text[idx:], encoding="utf-8")
        changed[name] = [a for a, _ in missing]

    for k, v in changed.items():
        print(f"{k}: +{', '.join(v)}")
    print(f"\n共修改 {len(changed)} 个 pom")
    return 0


if __name__ == "__main__":
    sys.exit(main())
