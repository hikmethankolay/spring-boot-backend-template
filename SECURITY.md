# Security policy

## Supported versions

Security fixes go into the latest release. Projects created from this template should pull fixes
from the template's releases and keep their dependencies current (Dependabot is configured).

| Version | Supported |
|---------|-----------|
| 4.0.x (latest) | Yes |
| < 4.0 | No |

## Reporting a vulnerability

Please do **not** open a public issue for a security problem.

Report it privately through GitHub instead: on the repository's **Security** tab, choose
**Report a vulnerability** ([direct link](https://github.com/hikmethankolay/spring-boot-backend-template/security/advisories/new)).
Include the affected version, the steps to reproduce and the impact you expect.

| Step | Expected time |
|------|---------------|
| Acknowledgement | within 7 days |
| Assessment (confirmed or declined, with severity) | within 14 days |
| Fix released, for critical and high severity | within 30 days of confirmation |
| Advisory published | with the fix, crediting you unless you prefer otherwise |

## Scope

In scope: the code in this repository, the published Docker image and the default configuration.
Out of scope: vulnerabilities that require a misconfiguration explicitly warned against in the
[Production guide](https://hikmethankolay.github.io/spring-boot-backend-template/guides/production/),
such as a weak `JWT_SECRET` or a publicly exposed management port.
