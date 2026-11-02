"""Operations diagrams: the Kubernetes deployment, the release pipeline and the package dependencies."""

from lib import Diagram


def kubernetes():
    d = Diagram("kubernetes", 720, 584, "Kubernetes deployment",
                "In namespace backend, ingress-nginx reaches the app pods through Service app and Prometheus scrapes "
                "their management port; secrets are mounted as files, a migrate Job runs Flyway as the schema owner, "
                "and the NetworkPolicy lets the pods reach only DNS, PostgreSQL, Redis, SMTP and HTTPS.")
    d.zone(24, 116, 672, 272, "NAMESPACE BACKEND")
    d.zone(24, 420, 672, 104, "NETWORKPOLICY EGRESS")
    d.wire((170, 84), (170, 156))
    d.wire((170, 216), (170, 264), accent=True)
    d.label(180, 244, "PORT 8080", anchor="start", on="zone")
    d.wire((330, 84), (330, 264), dashed=True)
    d.label(320, 140, "PORT 8081", anchor="end", on="zone")
    d.wire((470, 186), (380, 186), (380, 264), dashed=True)
    d.label(425, 177, "MOUNT", on="zone")
    d.wire((570, 216), (570, 264), dashed=True)
    d.label(580, 244, "MOUNT", anchor="start", on="zone")
    d.wire((200, 376), (200, 460))
    d.wire((390, 376), (390, 460))
    d.wire((410, 360), (560, 360), (560, 460))
    d.wire((620, 336), (620, 460))
    d.node(60, 24, 220, 60, "ingress-nginx", "the only source of traffic", tag="NAMESPACE", kind="box-ext")
    d.node(300, 24, 200, 60, "Prometheus", "/actuator/prometheus", tag="MONITORING", kind="box-ext")
    d.node(70, 156, 200, 60, "Service app", ":80 → port http", tag="SERVICE")
    d.node(470, 156, 200, 60, "Secrets as files", "/etc/app/secrets", tag="SECRET")
    d.node(50, 264, 360, 112, "App pods, database role app",
           ["HPA 2–6 replicas at 70% CPU", "PDB minAvailable 1",
            "rolling update: maxUnavailable 0, maxSurge 1", "probes /actuator/health/{liveness,readiness}"],
           tag="DEPLOYMENT", kind="box-focal")
    d.node(470, 264, 200, 72, "migrate", ["Flyway as app_owner", "app-migrator-secrets"], tag="JOB")
    d.node(115, 460, 170, 56, "Redis", ":6379", kind="box-ext")
    d.node(305, 460, 170, 56, "SMTP and HTTPS", ":587, :465 · :443", kind="box-ext")
    d.node(500, 460, 170, 56, "PostgreSQL", ":5432", kind="box-ext")
    d.legend(564, [("box-ext", "outside the namespace"), ("box-focal", "serves the API"),
                   ("dash", "scrape or volume mount")])
    d.save()


def release_pipeline():
    d = Diagram("release-pipeline", 720, 1020, "Release pipeline",
                "scripts/release.sh checks the repository, stamps the changelog and image tags, commits, pushes main "
                "and starts the release workflow on main, which tests, builds and publishes the image, pins its digest "
                "and tags that commit; the run on the tag checks the pin, signs, creates the GitHub release, SLSA "
                "provenance and the docs site, while an operator migrates and deploys.")
    d.zone(40, 24, 640, 212, "YOUR MACHINE")
    d.zone(40, 268, 640, 288, "GITHUB ACTIONS, ON MAIN")
    d.zone(40, 584, 640, 244, "GITHUB ACTIONS, ON THE TAG")
    d.zone(40, 860, 640, 136, "CLUSTER, BY HAND")
    d.wire((360, 152), (360, 168))
    d.wire((240, 208), (240, 296), accent=True)
    d.label(250, 252, "RUN ON MAIN, VERSION X.Y.Z", anchor="start", accent=True)
    d.wire((240, 356), (240, 384))
    d.wire((240, 444), (240, 472))
    d.wire((240, 532), (240, 612), accent=True)
    d.label(250, 572, "TAG vX.Y.Z, RUN ON IT", anchor="start", accent=True)
    d.wire((400, 502), (620, 502), (620, 900))
    d.label(630, 840, "PULL", anchor="start")
    d.wire((190, 708), (190, 748))
    d.wire((360, 708), (360, 728), (450, 728), (450, 748))
    d.node(120, 56, 480, 96, "scripts/release.sh <version>",
           ["checks: on main, clean, equal to origin/main,", "CHANGELOG notes; stamps the version and image",
            "tags, runs KubernetesManifestTest"], tag="SCRIPT")
    d.node(120, 168, 480, 40, "commit; after a yes, push main and gh workflow run")
    d.node(80, 296, 320, 60, "test", "release.yml: ./mvnw verify", tag="JOB")
    d.node(80, 384, 320, 60, "build", "amd64 + arm64 image, SBOM, provenance", tag="JOB")
    d.node(80, 472, 320, 60, "pin-and-tag", "digest → deploy/kubernetes, tag it", tag="JOB")
    d.node(80, 612, 320, 96, "publish",
           ["checks the tag pins the pushed image", "cosign sign and verify, signed SBOM",
            "GitHub release with notes"], tag="JOB", kind="box-focal")
    d.node(80, 748, 220, 56, "provenance", "SLSA generator v2.1.0", tag="JOB")
    d.node(340, 748, 220, 56, "pages.yml", "docs for the tag", tag="WORKFLOW")
    d.node(80, 900, 560, 64, "Migrate, then deploy",
           ["kubectl apply -f migrate-job.yaml, wait for it", "kubectl apply -k deploy/kubernetes"])
    d.save()


def package_dependencies():
    d = Diagram("package-dependencies", 720, 544, "Package dependencies",
                "auth depends on account, mfa, social and the core of user, security and mail, which import each "
                "other; account, mfa, social and note depend on that core; every package uses audit, common and "
                "config; only auth imports mfa and social.")
    d.wire((194, 140), (229, 140), (229, 68), (264, 68))
    d.wire((194, 164), (264, 164), accent=True)
    d.wire((194, 212), (488, 212))
    d.wire((194, 228), (240, 228), (240, 260), (264, 260), accent=True)
    d.wire((440, 68), (488, 68))
    d.wire((440, 164), (488, 164))
    d.wire((440, 260), (488, 260))
    d.wire((194, 348), (608, 348), (608, 288))
    d.node(24, 120, 170, 120, "auth", ["login · tokens", "sessions"], kind="box-focal")
    d.node(24, 320, 170, 56, "note", "the sample feature")
    d.node(264, 40, 176, 56, "account", "verify email · reset")
    d.node(264, 136, 176, 56, "mfa", "authenticator codes")
    d.node(264, 232, 176, 56, "social", "GitHub and Google")
    d.node(488, 40, 208, 248, "user · security · mail",
           ["users, roles, JWT", "email outbox, alerts", "they import", "each other"])
    d.node(24, 424, 672, 60, "audit · common · config", "used by every package, so not drawn as arrows",
           tag="SHARED")
    d.text(360, 520, "Only auth imports mfa and social, so removing either leaves the other features untouched.",
           anchor="middle")
    d.save()


ALL = [kubernetes, release_pipeline, package_dependencies]
