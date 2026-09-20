#!/usr/bin/env python3
"""Enforce service boundaries and authorization invariants without loading Spring."""

from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "build" / "architecture-controller-persistence-baseline.txt"
STARTER_AUTOCONFIG = ROOT / "platform/socp-starter/src/main/java/com/socp/platform/starter" / "SocpPlatformAutoConfiguration.java"
STARTER_JPA_REGISTRAR = STARTER_AUTOCONFIG.parent / "SocpPlatformJpaRegistrar.java"
STARTER_POM = ROOT / "platform/socp-starter/pom.xml"
WRITE_MAPPING = re.compile(r"@(Post|Put|Patch|Delete)Mapping\b")
AUTH_BOUNDARY = re.compile(
    r"@(?:[\w.]+\.)?(RequireRole|RequireService|RequireIngestIdentity|RequirePermission)\b"
)
PERSISTENCE_IMPORT = re.compile(r"^import\s+com\.socp\..*\.persistence\.(repository|entity)\.", re.MULTILINE)
REPOSITORY_DECL = re.compile(
    r"public\s+interface\s+(\w+Repository)\s+extends\s+([^\{]+)\{", re.MULTILINE)
SERVICE_DEPENDENCY = re.compile(r"<artifactId>([^<]+)</artifactId>")
STARTER_MANAGED = {
    "socp-auth", "socp-tenant", "socp-audit", "socp-ratelimit",
    "socp-obs", "socp-error", "socp-data",
}
# Platform modules that deliberately stay outside the starter: they are opt-in
# libraries (typed clients, rule engine, test support) or the starter itself, so
# pulling them into every servlet service would widen the dependency surface.
STARTER_OPT_IN = {
    "socp-client": "typed service-to-service clients are opt-in per service",
    "socp-rule": "rule engine is only needed by detection and search",
    "socp-test": "test support is test scope only",
    "socp-starter": "the starter cannot depend on itself",
}
# detect-web owns two explicit persistence units (primary detection plus the
# secondary-analysis unit) with their own EntityManagerFactory/TransactionManager
# beans, so a single global entity-scan list would be wrong there.
JPA_ASSEMBLY_EXCEPTIONS = {
    "detect-web": "two explicit persistence units; see DetectionPersistenceConfiguration",
}
# The gateway is WebFlux: importing the servlet starter would pull WebMvcConfigurer
# beans into a reactive context and fail startup (documented on its main class).
SERVLET_STARTER_EXCEPTIONS = {
    "api-gateway": "WebFlux gateway imports SocpJwtConfig/ProdGuard directly instead",
}
METHOD_END = re.compile(r"^ {4}}\s*$", re.MULTILINE)
PLATFORM_DEPENDENCY = re.compile(
    r"<dependency>\s*<groupId>com\.socp\.platform</groupId>\s*"
    r"<artifactId>([^<]+)</artifactId>", re.DOTALL)


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def service_of(path: Path) -> str:
    return relative(path).split("/")[1]


def declares(content: str, annotation: str) -> bool:
    """True when a type-level annotation is actually applied.

    Line anchored on purpose: prose in these files documents the annotations by name,
    and a substring test would read a javadoc mention as a live declaration."""
    return re.search(rf"^@{re.escape(annotation)}\b", content, re.MULTILINE) is not None


def starter_assembly_checks(errors: list[str]) -> int:
    """The starter keeps three parallel lists; they must describe one surface.

    ``@ComponentScan`` in the auto-configuration, the ``com.socp.platform``
    dependencies of the starter pom, and ``STARTER_MANAGED`` above all encode
    "which platform modules the starter owns". Nothing compared them, so adding a
    platform module silently meant editing one or two of the three and the others
    just stayed wrong: a scanned-but-undeclared package could not resolve its
    classes, and a declared-but-unscanned module was an invisible dependency.
    """
    source = STARTER_AUTOCONFIG.read_text(encoding="utf-8")
    scan = re.search(r"@ComponentScan\(basePackages\s*=\s*\{(.*?)\}\)", source, re.DOTALL)
    if scan is None:
        errors.append(f"{relative(STARTER_AUTOCONFIG)}: @ComponentScan basePackages list no longer parses")
        scanned: list[str] = []
    else:
        scanned = re.findall(r'"([\w.]+)"', scan.group(1))
    if len(scanned) != len(set(scanned)):
        errors.append(f"{relative(STARTER_AUTOCONFIG)}: duplicate @ComponentScan package")

    scanned_modules = {"socp-" + package.rsplit(".", 1)[-1] for package in scanned}
    if scanned_modules != STARTER_MANAGED:
        errors.append(
            "starter @ComponentScan vs STARTER_MANAGED drift: "
            f"scan_only={sorted(scanned_modules - STARTER_MANAGED)} "
            f"gate_only={sorted(STARTER_MANAGED - scanned_modules)}")

    declared = set(PLATFORM_DEPENDENCY.findall(STARTER_POM.read_text(encoding="utf-8")))
    if declared != STARTER_MANAGED:
        errors.append(
            "starter pom vs STARTER_MANAGED drift: "
            f"pom_only={sorted(declared - STARTER_MANAGED)} "
            f"gate_only={sorted(STARTER_MANAGED - declared)}")

    platform_modules = {path.parent.name for path in ROOT.glob("platform/*/pom.xml")}
    root_pom_modules = set(re.findall(
        r"<module>platform/([^<]+)</module>", (ROOT / "pom.xml").read_text(encoding="utf-8")))
    if platform_modules != root_pom_modules:
        errors.append(
            f"platform module vs root pom drift: missing={sorted(platform_modules - root_pom_modules)} "
            f"extra={sorted(root_pom_modules - platform_modules)}")
    unlisted = platform_modules - STARTER_MANAGED - set(STARTER_OPT_IN)
    if unlisted:
        errors.append(
            "platform modules are neither starter-managed nor declared opt-in: "
            + ", ".join(sorted(unlisted))
            + "; add them to STARTER_MANAGED (auto-wired) or STARTER_OPT_IN (with a reason)")
    stale = {name for name in STARTER_OPT_IN if name not in platform_modules}
    if stale:
        errors.append(
            "STARTER_OPT_IN lists platform modules that no longer exist: "
            + ", ".join(sorted(stale)))

    registrar = STARTER_JPA_REGISTRAR.read_text(encoding="utf-8")
    entity_list = re.search(
        r"PLATFORM_ENTITY_PACKAGES\s*=\s*List\.of\((.*?)\);", registrar, re.DOTALL)
    if entity_list is None:
        errors.append(f"{relative(STARTER_JPA_REGISTRAR)}: PLATFORM_ENTITY_PACKAGES no longer parses")
    else:
        for package in re.findall(r'"([\w.]+)"', entity_list.group(1)):
            if not any(package == root or package.startswith(root + ".") for root in scanned):
                errors.append(
                    f"{relative(STARTER_JPA_REGISTRAR)}: entity package {package} is outside the "
                    "starter @ComponentScan whitelist, so its beans would never be registered")
    return len(scanned)


def service_assembly_checks(errors: list[str]) -> int:
    """Every service main class wires the platform the same way, or says why not.

    This convention used to live only in the Chinese comments of three main classes:
    domain scan restricted to the service package, platform beans through the starter,
    JPA entity/repository scanning through ``@EnableSocpPlatformJpa``, and
    configuration properties registered explicitly on the main class."""
    main_classes = [
        path for path in sorted(ROOT.glob("services/*/src/main/java/**/*Application.java"))
        if "@SpringBootApplication" in path.read_text(encoding="utf-8")
    ]
    entity_services: set[str] = set()
    component_properties: dict[str, list[tuple[str, bool]]] = {}
    for path in ROOT.glob("services/*/src/main/java/**/*.java"):
        content = path.read_text(encoding="utf-8")
        service = service_of(path)
        if re.search(r"^@Entity\b", content, re.MULTILINE):
            entity_services.add(service)
        if re.search(r"^@ConfigurationProperties\b", content, re.MULTILINE):
            component_properties.setdefault(service, []).append(
                (path.stem, bool(re.search(r"^@Component\b", content, re.MULTILINE))))

    for path in main_classes:
        service = service_of(path)
        content = path.read_text(encoding="utf-8")
        uses_starter = "SocpPlatformAutoConfiguration.class" in content  # noqa: PLC1802
        if service in SERVLET_STARTER_EXCEPTIONS:
            if uses_starter:
                errors.append(
                    f"{relative(path)}: {service} is documented as a non-servlet starter "
                    "exception but imports SocpPlatformAutoConfiguration anyway")
        elif not uses_starter:
            errors.append(
                f"{relative(path)}: servlet service must @Import(SocpPlatformAutoConfiguration.class); "
                "add the service to SERVLET_STARTER_EXCEPTIONS with a reason instead of skipping it")
        scan = re.search(r"scanBasePackages\s*=\s*\{([^}]*)\}", content, re.DOTALL)
        if scan and "com.socp.platform" in scan.group(1):
            errors.append(
                f"{relative(path)}: services must not component-scan com.socp.platform; "
                "the starter owns the platform bean list")
        if service in JPA_ASSEMBLY_EXCEPTIONS:
            if declares(content, "EnableSocpPlatformJpa"):
                errors.append(
                    f"{relative(path)}: {service} is a documented multi-persistence-unit exception "
                    "and must keep its explicit EntityManagerFactory wiring")
            continue
        if service not in entity_services:
            continue
        if not declares(content, "EnableSocpPlatformJpa"):
            errors.append(
                f"{relative(path)}: JPA service must declare @EnableSocpPlatformJpa "
                "(or list itself in JPA_ASSEMBLY_EXCEPTIONS with a reason)")
        for forbidden in ("@EntityScan", "@EnableJpaRepositories"):
            if re.search(rf"^{re.escape(forbidden)}\b", content, re.MULTILINE):
                errors.append(
                    f"{relative(path)}: {forbidden} duplicates the platform JPA assembly; "
                    "use @EnableSocpPlatformJpa(entityPackages = ...) instead")

    for service, entries in sorted(component_properties.items()):
        main_class = next((path for path in main_classes if service_of(path) == service), None)
        if main_class is None:
            continue
        content = main_class.read_text(encoding="utf-8")
        if declares(content, "ConfigurationPropertiesScan"):
            errors.append(
                f"{relative(main_class)}: @ConfigurationPropertiesScan is not the platform convention; "
                "it silently registers every annotated class it finds (including @Component ones, "
                "which yields a second bean definition) - list properties on "
                "@EnableConfigurationProperties instead, see docs/adding-a-service.md")
        for name, component in entries:
            if component or name in content:
                continue
            errors.append(
                f"{relative(main_class)}: @ConfigurationProperties class {name} is not registered; "
                "add it to @EnableConfigurationProperties or annotate it @Component")
    return len(main_classes)


def controller_checks(errors: list[str]) -> tuple[int, set[str]]:
    controllers = sorted(ROOT.glob("services/*/src/main/java/**/*Controller.java"))
    direct_persistence: set[str] = set()
    public_auth_controllers = {
        "services/api-gateway/src/main/java/com/socp/gateway/api/controller/AuthController.java",
        "services/api-gateway/src/main/java/com/socp/gateway/api/controller/OidcAuthController.java",
    }
    for path in controllers:
        content = path.read_text(encoding="utf-8")
        name = relative(path)
        if PERSISTENCE_IMPORT.search(content):
            direct_persistence.add(name)
        if name in public_auth_controllers:
            continue
        class_match = re.search(r"\b(?:public\s+)?class\s+\w+", content)
        class_authorized = bool(class_match and AUTH_BOUNDARY.search(content[:class_match.start()]))
        for mapping in WRITE_MAPPING.finditer(content):
            if class_authorized:
                continue
            previous_methods = list(METHOD_END.finditer(content, 0, mapping.start()))
            previous_body = previous_methods[-1].end() if previous_methods else 0
            method_match = re.search(r"\bpublic\s+[\w<>,.?\[\]\s]+\s+\w+\s*\(", content[mapping.start():])
            if method_match is None:
                errors.append(f"{name}:{content.count(chr(10), 0, mapping.start()) + 1}: "
                              "cannot resolve mutating controller method")
                continue
            signature_start = mapping.start() + method_match.start()
            annotation_block = content[previous_body:signature_start]
            if not AUTH_BOUNDARY.search(annotation_block):
                line = content.count("\n", 0, mapping.start()) + 1
                errors.append(f"{name}:{line}: mutating method has no explicit authorization boundary")
    return len(controllers), direct_persistence


def dependency_checks(errors: list[str]) -> int:
    poms = sorted(ROOT.glob("services/*/pom.xml"))
    service_artifacts = {pom.parent.name for pom in poms}

    checked = 0
    for pom in poms:
        content = pom.read_text(encoding="utf-8")
        own = pom.parent.name
        dependencies = re.findall(
                r"<dependency>.*?<artifactId>([^<]+)</artifactId>.*?</dependency>",
                content, re.DOTALL)
        for dependency in dependencies:
            if dependency in service_artifacts and dependency != own:
                errors.append(
                    f"{relative(pom)}: service module depends directly on service '{dependency}'; "
                    "use a platform contract/client instead"
                )
        if "socp-starter" in dependencies:
            duplicated = STARTER_MANAGED.intersection(dependencies)
            if duplicated:
                errors.append(
                    f"{relative(pom)}: dependencies already supplied by socp-starter: "
                    + ", ".join(sorted(duplicated))
                )
        checked += 1

    root_pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    if "platform/socp-bom" in root_pom or (ROOT / "platform" / "socp-bom" / "pom.xml").exists():
        errors.append("unused platform/socp-bom must not return; the parent owns dependency management")
    return checked


def tenant_repository_checks(errors: list[str]) -> int:
    """Require tenant-owned JPA repositories to use the fail-closed SDK contract."""
    contract = (ROOT / "platform" / "socp-tenant" / "src" / "main" / "java" / "com" / "socp"
                / "platform" / "tenant" / "persistence" / "TenantScopedRepository.java")
    contract_text = contract.read_text(encoding="utf-8")
    required_fail_closed_methods = (
        "findAll(Pageable pageable)",
        "findAll(Example<S> example, Pageable pageable)",
        "findBy(\n            Example<S> example,",
    )
    for signature in required_fail_closed_methods:
        if signature not in contract_text:
            errors.append(f"{relative(contract)}: missing fail-closed repository method {signature}")

    aspect = contract.parent / "ScheduledSystemScopeAspect.java"
    aspect_text = aspect.read_text(encoding="utf-8")
    if "@annotation(com.socp.platform.tenant.persistence.TenantSystemJob)" not in aspect_text:
        errors.append(f"{relative(aspect)}: system scope must require TenantSystemJob")
    if "@annotation(org.springframework.scheduling.annotation.Scheduled)" in aspect_text:
        errors.append(f"{relative(aspect)}: @Scheduled must never grant system scope implicitly")
    for path in ROOT.glob("services/*/src/main/java/**/*.java"):
        content = path.read_text(encoding="utf-8")
        for marker in re.finditer(r"@TenantSystemJob\b", content):
            if "@Scheduled" not in content[max(0, marker.start() - 300):marker.start()]:
                line = content.count("\n", 0, marker.start()) + 1
                errors.append(
                    f"{relative(path)}:{line}: TenantSystemJob is reserved for scheduled jobs"
                )

    tenant_entities: set[str] = set()
    for path in ROOT.glob("services/*/src/main/java/**/*.java"):
        content = path.read_text(encoding="utf-8")
        if "@Entity" not in content:
            continue
        class_match = re.search(r"\bclass\s+(\w+)", content)
        tenant_owned = bool(re.search(
            r"\btenantId\b|\btenant_id\b|\bgetTenantId\s*\(|\bextends\s+BaseEntity\b",
            content,
        ))
        if class_match and tenant_owned:
            tenant_entities.add(class_match.group(1))

    checked = 0
    for path in ROOT.glob("services/*/src/main/java/**/*Repository.java"):
        content = path.read_text(encoding="utf-8")
        for _, declaration in REPOSITORY_DECL.findall(content):
            entity_match = re.search(r"<(\w+)\s*,", declaration)
            if entity_match is None or entity_match.group(1) not in tenant_entities:
                continue
            checked += 1
            if "TenantScopedRepository" not in declaration:
                errors.append(
                    f"{relative(path)}: tenant entity repository must extend TenantScopedRepository"
                )
    return checked


def main() -> int:
    errors: list[str] = []
    controller_count, current_debt = controller_checks(errors)
    pom_count = dependency_checks(errors)
    tenant_repo_count = tenant_repository_checks(errors)
    scanned_packages = starter_assembly_checks(errors)
    assembled_services = service_assembly_checks(errors)
    expected_debt = {
        line.strip() for line in BASELINE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    }
    introduced = current_debt - expected_debt
    retired = expected_debt - current_debt
    for item in sorted(introduced):
        errors.append(f"{item}: controller introduced a direct repository/entity dependency")
    if retired:
        errors.append(
            "architecture baseline contains retired debt; remove these entries: "
            + ", ".join(sorted(retired))
        )
    if errors:
        print("Architecture gate failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(
        f"Architecture gate passed: {controller_count} controllers, {pom_count} service modules; "
        f"direct-persistence baseline={len(current_debt)}; tenant repositories={tenant_repo_count}; "
        f"starter scans {scanned_packages} platform packages across {assembled_services} main classes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
